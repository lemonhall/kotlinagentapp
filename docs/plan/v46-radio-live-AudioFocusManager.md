# v46 Plan：全链路落盘 + AudioFocusManager + `radio live` 扩展

## Goal

为 v45 实时翻译管线补齐持久化与并发治理：

- 全链路落盘：live 会话的音频切片 + ASR/翻译 JSONL + TTS chunks 写入 VFS
- AudioFocusManager：app 内音频源优先级仲裁 + 接收系统 AudioFocus 事件的统一入口
- `radio live` CLI 扩展落盘开关

## PRD Trace

- PRD-0034：REQ-0034-182

## Scope

做（v46）：

- `LiveSessionStore`：live 会话全链路落盘到 `workspace/radio_recordings/live_*/`
- 落盘内容（均可选，通过 CLI flag 控制）：
  - 原声音频切片（`--save_audio`）
  - ASR 转录 JSONL（`--save_transcript`）
  - 翻译 JSONL（`--save_translation`）
  - TTS 合成音频（`--save_tts`）
- `AudioFocusManager`：统一管理 app 内所有音频输出的优先级仲裁，并作为系统 AudioFocus 事件的 app 内分发中心
- `radio live` CLI 扩展落盘参数
- live 会话目录可被 v42 Files 浏览器正常浏览

不做（v46）：

- 不做费用统计（另立 PRD）
- 不做落盘文件的二次编辑/裁剪

## 全链路落盘结构

```
workspace/radio_recordings/
  live_20260219_2000_a1b2c3/
    _meta.json
    _STATUS.md
    audio/                          # --save_audio
      chunk_001.ogg
      chunk_002.ogg
      ...
    transcript/                     # --save_transcript
      transcript.jsonl              # 追加写，每行一个 segment
    translation/                    # --save_translation
      translation.jsonl             # 追加写，每行一个 translated segment
    tts/                            # --save_tts
      tts_001.ogg
      tts_002.ogg
      ...
```

### _meta.json Schema

```json
{
  "schema": "kotlin-agent-app/live-session@v1",
  "sessionId": "live_20260219_2000_a1b2c3",
  "stationId": "nhk_world",
  "stationName": "NHK World",
  "mode": "interleaved",
  "sourceLanguage": "ja",
  "targetLanguage": "zh",
  "saveFlags": {
    "audio": true,
    "transcript": true,
    "translation": true,
    "tts": false
  },
  "state": "completed",
  "startedAt": "2026-02-19T20:00:00+08:00",
  "stoppedAt": "2026-02-19T20:35:00+08:00",
  "durationSec": 2100,
  "stats": {
    "audioChunks": 42,
    "segmentsTranscribed": 210,
    "segmentsTranslated": 205,
    "segmentsSkipped": 5,
    "ttsChunksGenerated": 0
  },
  "asrProvider": "openai_whisper",
  "translationProvider": "openai_gpt4",
  "ttsProvider": "openai_tts"
}
```

`state` 取值：`running | completed | stopped_by_user | failed`

### JSONL 格式

transcript.jsonl（每行一个 segment）：

```jsonl
{"seq":1,"chunkIndex":1,"id":0,"startSec":0.0,"endSec":3.2,"text":"こんにちは、NHKワールドニュースです","timestamp":"2026-02-19T20:00:05+08:00"}
{"seq":2,"chunkIndex":1,"id":1,"startSec":3.2,"endSec":7.8,"text":"今日のトップニュースをお伝えします","timestamp":"2026-02-19T20:00:08+08:00"}
```

translation.jsonl（每行一个 translated segment）：

```jsonl
{"seq":1,"chunkIndex":1,"id":0,"startSec":0.0,"endSec":3.2,"sourceText":"こんにちは、NHKワールドニュースです","translatedText":"你好，这里是NHK世界新闻","timestamp":"2026-02-19T20:00:12+08:00"}
{"seq":2,"chunkIndex":1,"id":1,"startSec":3.2,"endSec":7.8,"sourceText":"今日のトップニュースをお伝えします","translatedText":"为您播报今天的头条新闻","timestamp":"2026-02-19T20:00:15+08:00"}
```

选择 JSONL 而非 JSON 数组的理由：

- 追加写友好（不需要维护数组闭合括号）
- 进程崩溃时已写入的行不丢失
- 逐行解析，内存友好（live 会话可能持续数小时）

### 落盘写入原子性

- 音频 chunk：先写临时文件 `chunk_NNN.ogg.tmp`，写完后 rename 为 `chunk_NNN.ogg`
- JSONL：直接 append + flush，每行写入后立即 flush 到磁盘
- `_meta.json`：每次状态变更时全量重写（文件小，原子性通过 write-to-tmp + rename 保证）
- `_STATUS.md`：与 `_meta.json` 同步更新

## AudioFocusManager

### 职责

统一管理 app 内部多个音频输出源的优先级仲裁，同时作为 Android 系统 AudioFocus 事件在 app 内的分发中心。

### 双层 AudioFocus 架构

```
┌─────────────────────────────────────────────────────────┐
│  Android 系统 AudioFocus                                 │
│  （来电、其他 App 播放音乐、导航语音等）                    │
│                                                         │
│  MusicPlaybackService 已通过 MediaSession 注册系统焦点     │
│  系统事件 → MusicPlaybackService → AudioFocusManager      │
└────────────────────────┬────────────────────────────────┘
                         │ onSystemFocusChanged(event)
                         ▼
┌─────────────────────────────────────────────────────────┐
│  AudioFocusManager（v46：app 内仲裁 + 系统事件分发）       │
│                                                         │
│  app 内仲裁：                                            │
│    CHAT_TTS > RADIO_TTS > RADIO_PLAYBACK                │
│                                                         │
│  系统事件分发：                                           │
│    FOCUS_LOSS       → 全部暂停                           │
│    FOCUS_LOSS_DUCK  → 全部 duck                          │
│    FOCUS_GAIN       → 恢复到系统中断前的 app 内状态        │
│                                                         │
│  ├── CHAT_TTS → 直接控制 Chat TTS 播放器                 │
│  ├── RADIO_TTS → 通知 MixController 暂停/恢复 TTS        │
│  └── RADIO_PLAYBACK → 通知 MixController 调整原声音量     │
│        └── MixController（v45：模式内音量控制）            │
└─────────────────────────────────────────────────────────┘
```

设计要点：

- `MusicPlaybackService` 继续持有系统 AudioFocus 的注册/释放（通过 MediaSession，这是 v38 已有的行为，不改动）
- `MusicPlaybackService` 收到系统 AudioFocus 变化时，调用 `AudioFocusManager.onSystemFocusChanged(event)` 转发
- `AudioFocusManager` 内部维护两层状态：
  - `systemFocusState`：来自系统的焦点状态（`FOCUS_GAIN` / `FOCUS_LOSS` / `FOCUS_LOSS_DUCK` / `FOCUS_LOSS_TRANSIENT`）
  - `appSourceStates`：app 内各音频源的状态（`PLAYING` / `DUCKED` / `PAUSED` / `IDLE`）
- 系统事件优先级高于 app 内仲裁：系统 `FOCUS_LOSS` 时，即使 CHAT_TTS 正在播放也必须暂停
- 系统 `FOCUS_GAIN` 恢复时，`AudioFocusManager` 恢复到系统中断前的 app 内状态（而非全部恢复为 PLAYING）

```kotlin
class AudioFocusManager {

    enum class AudioSource {
        CHAT_TTS,       // Chat Agent 语音回复（app 内最高优先级）
        RADIO_TTS,      // Radio live 实时翻译 TTS
        RADIO_PLAYBACK, // Radio 电台原声播放
    }

    /**
     * 请求 app 内音频焦点。
     * 如果有更高优先级的源正在播放，排队等待。
     * 如果有更低优先级的源正在播放，触发其 duck/pause。
     * 如果系统焦点已丢失，挂起直到系统焦点恢复。
     */
    suspend fun requestFocus(source: AudioSource): FocusGrant

    /**
     * 释放 app 内音频焦点。
     * 恢复被 duck/pause 的低优先级源。
     */
    fun releaseFocus(grant: FocusGrant)

    /**
     * 由 MusicPlaybackService 调用，转发系统 AudioFocus 事件。
     * AudioFocusManager 据此暂停/duck/恢复 app 内所有音频源。
     */
    fun onSystemFocusChanged(event: SystemFocusEvent)

    /** 当前各源的状态（综合系统焦点 + app 内仲裁的最终结果） */
    val sourceStates: StateFlow<Map<AudioSource, AudioSourceState>>

    /** 当前系统焦点状态（供 UI 或诊断使用） */
    val systemFocusState: StateFlow<SystemFocusEvent>
}

enum class AudioSourceState {
    PLAYING,    // 正常播放
    DUCKED,     // 被降音量（仍在播放）
    PAUSED,     // 被暂停（app 内仲裁或系统焦点丢失）
    IDLE,       // 未播放
}

enum class SystemFocusEvent {
    FOCUS_GAIN,             // 系统焦点恢复
    FOCUS_LOSS,             // 永久丢失（如其他 app 开始播放音乐）
    FOCUS_LOSS_TRANSIENT,   // 短暂丢失（如来电）
    FOCUS_LOSS_DUCK,        // 短暂丢失但允许降音量（如导航语音）
}

data class FocusGrant(
    val source: AudioSource,
    val grantId: String,
)
```

### app 内优先级规则

| 事件 | RADIO_PLAYBACK | RADIO_TTS | CHAT_TTS |
|------|----------------|-----------|----------|
| CHAT_TTS 开始 | DUCKED（20%） | PAUSED | PLAYING |
| CHAT_TTS 结束 | 恢复原音量 | 恢复播放 | IDLE |
| RADIO_TTS 开始（interleaved） | DUCKED（20%） | PLAYING | 不受影响 |
| RADIO_TTS 开始（target_only） | PAUSED | PLAYING | 不受影响 |
| RADIO_TTS 结束 | 恢复 | IDLE | 不受影响 |

### 系统焦点事件的 app 内响应

| 系统事件 | app 内响应 | 恢复行为 |
|----------|-----------|----------|
| `FOCUS_LOSS` | 全部 PAUSED | 不自动恢复（用户需手动恢复） |
| `FOCUS_LOSS_TRANSIENT` | 全部 PAUSED | `FOCUS_GAIN` 时恢复到中断前的 app 内状态 |
| `FOCUS_LOSS_DUCK` | 全部 DUCKED（在当前音量基础上再降至 30%） | `FOCUS_GAIN` 时恢复到中断前的音量 |
| `FOCUS_GAIN` | 恢复到系统中断前的 app 内状态快照 | — |

关键规则：

- CHAT_TTS 优先级最高，到达时 RADIO_TTS 立即暂停（不是 duck，因为两个 TTS 同时播放会混乱）
- CHAT_TTS 结束后，RADIO_TTS 恢复播放（从暂停点继续，不跳过）
- RADIO_PLAYBACK 被 duck 时音量降至 20%，被 pause 时完全静音
- 所有恢复操作带 300ms 渐变（避免突兀的音量跳变）
- 系统 `FOCUS_LOSS_TRANSIENT` 恢复时，`AudioFocusManager` 先恢复 app 内状态快照，再让 app 内仲裁规则生效（例如：中断前 CHAT_TTS 正在播放导致 RADIO_TTS 被暂停，恢复后仍然是 CHAT_TTS 播放 + RADIO_TTS 暂停，而非全部恢复为 PLAYING）

### 与 v45 MixController 的关系

v45 的 `MixController` 负责 interleaved/target_only 模式下原声与 Radio TTS 的音量控制。v46 的 `AudioFocusManager` 在其上层，处理跨源仲裁（app 内 Chat TTS 抢占 + 系统焦点事件）。

`MixController` 不直接感知系统 AudioFocus，只接收 `AudioFocusManager` 下发的指令（duck/pause/resume）。

## CLI 扩展

v45 的 `radio live start` 扩展落盘参数：

```
radio live start --station <stationId> --mode interleaved|target_only|subtitle_only \
  --source_lang ja --target_lang zh \
  [--save_audio] [--save_transcript] [--save_translation] [--save_tts]

radio live stop
radio live status
radio live list          # 列出历史 live 会话（已落盘的）
```

不带任何 `--save_*` flag 时，不落盘（纯实时体验，退出后无痕）。

带任何一个 `--save_*` flag 时，自动创建 `live_*/` 目录 + `_meta.json` + `_STATUS.md`。

`radio live list` 的 result：

```json
{
  "sessions": [
    {
      "sessionId": "live_20260219_2000_a1b2c3",
      "stationName": "NHK World",
      "mode": "interleaved",
      "state": "completed",
      "durationSec": 2100,
      "saveFlags": { "audio": true, "transcript": true, "translation": true, "tts": false },
      "startedAt": "2026-02-19T20:00:00+08:00"
    }
  ]
}
```

## 错误码集合

| error_code | 含义 |
|------------|------|
| `LiveSessionNotFound` | 指定的 live 会话目录不存在 |
| `LiveSessionNotActive` | 尝试 stop 但没有活跃的 live 会话 |
| `DiskSpaceLow` | 磁盘剩余空间 < 100MB，拒绝开启落盘 |
| `LiveSessionSaveFailed` | 落盘写入失败（IO 错误） |
| `AudioFocusConflict` | 音频焦点仲裁异常（不应出现，防御性错误码） |

继承 v45 的错误码（`LiveSessionAlreadyActive`、`StationNotPlaying`、`RecordingConflict` 等）。

## Files 浏览集成

live 会话目录在 v42 的 Files 浏览器中可见：

- `live_*/` 目录显示为"实时翻译会话"卡片（复用 v42 的 `VfsRendererRegistry`）
- `_meta.json` 渲染为会话概览卡片（电台名、时长、模式、落盘内容摘要）
- `transcript.jsonl` / `translation.jsonl` 渲染为字幕视图（复用 v42 `SubtitleScreen`，JSONL 逐行解析为 segments）
- `audio/chunk_NNN.ogg` 可点击播放
- `tts/tts_NNN.ogg` 可点击播放

新增渲染器：

| 文件模式 | 渲染器 | 匹配规则 |
|----------|--------|----------|
| `_meta.json`（在 `live_*/` 下） | `LiveSessionCardRenderer` | 文件名为 `_meta.json` 且父路径匹配 `*/live_*/` |
| `*.jsonl`（在 `transcript/` 或 `translation/` 下） | `JsonlSubtitleRenderer` | `.jsonl` 后缀 + 父路径匹配 `*/live_*/transcript/` 或 `*/live_*/translation/` |

### JsonlSubtitleRenderer 懒加载策略

长时间 live 会话的 JSONL 可能达到数 MB（数千行），不能一次性全部加载到内存。采用类似聊天记录的倒序分页加载：

- 首次打开：只加载最后 200 行（最新内容），从文件末尾向前读取
- 用户上滑：触发加载更早的 200 行，追加到列表顶部
- 内存上限：最多保留 1000 行在内存中，超出时释放最早的页
- 加载指示器：列表顶部显示"加载更早内容..."（上滑触发）或"已到达开头"
- 实现方式：`RandomAccessFile` 从文件末尾向前扫描换行符，定位到目标行范围后逐行解析

```kotlin
class JsonlPagingSource(
    private val file: File,
    private val pageSize: Int = 200,
) {
    /** 加载最后 N 行（首次打开） */
    suspend fun loadTail(): List<JsonlLine>

    /** 加载更早的 N 行（用户上滑） */
    suspend fun loadPrevious(): List<JsonlLine>?  // null = 已到达文件开头

    /** 当前已加载的行范围 */
    val loadedRange: IntRange

    data class JsonlLine(
        val lineNumber: Int,    // 文件中的行号（从 1 开始）
        val content: String,    // 原始 JSON 字符串
    )
}
```

## Acceptance（硬 DoD）

- 落盘完整性：开启 `--save_audio --save_transcript --save_translation` 后，live 会话结束时目录内文件齐全（audio chunks + transcript.jsonl + translation.jsonl + _meta.json + _STATUS.md）。
- 落盘原子性：进程崩溃后重启，已写入的 JSONL 行和已完成的 audio chunk 不丢失、不损坏。
- 不落盘模式：不带 `--save_*` flag 时，`workspace/radio_recordings/` 下不产生任何新目录。
- 磁盘保护：磁盘剩余 < 100MB 时拒绝开启落盘，返回 `DiskSpaceLow`。
- AudioFocus — Chat 抢占：Chat TTS 播放时，Radio TTS 暂停、原声降至 20%；Chat TTS 结束后，Radio TTS 恢复、原声恢复。
- AudioFocus — 恢复：Chat TTS 抢占后恢复，Radio TTS 从暂停点继续（不跳过 segment）。
- AudioFocus — 渐变：所有音量变化带 300ms 渐变（可通过 UT 验证调用参数）。
- AudioFocus — 系统焦点：来电（`FOCUS_LOSS_TRANSIENT`）时全部暂停；挂断后恢复到中断前的 app 内状态。
- AudioFocus — 系统 duck：导航语音（`FOCUS_LOSS_DUCK`）时全部降音量；导航结束后恢复。
- AudioFocus — 永久丢失：其他 app 播放音乐（`FOCUS_LOSS`）时全部暂停，不自动恢复。
- JSONL 懒加载：打开一个 2000 行的 JSONL 文件，首次只加载最后 200 行；上滑可加载更早内容；不 OOM。
- Files 浏览：live 会话目录可在 Files 中正常浏览，`_meta.json` 渲染为卡片，JSONL 渲染为字幕视图。
- CLI：`radio live list` 列出历史会话；`radio live --help` 为 0。
- 清空确认：删除 live 会话目录需二次确认（复用现有 Files 删除确认机制）。

验证命令：

- `.\gradlew.bat :app:testDebugUnitTest`
- 真机：开启 live + 落盘 5 分钟 → 停止 → Files 浏览落盘内容 → 播放 audio chunk → 查看字幕 → 来电测试暂停/恢复

## Files（规划）

- 落盘模块：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_live/LiveSessionStore.kt`（落盘管理：目录创建、JSONL 追加、chunk 写入、meta 更新）
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_live/LiveSessionMetaV1.kt`（kotlinx.serialization）
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_live/JsonlWriter.kt`（JSONL 追加写 + flush 工具）
- AudioFocusManager：
  - `app/src/main/java/com/lsl/kotlin_agent_app/media/AudioFocusManager.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/media/FocusGrant.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/media/SystemFocusEvent.kt`
- Files 渲染器：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/renderer/LiveSessionCardRenderer.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/renderer/JsonlSubtitleRenderer.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/renderer/JsonlPagingSource.kt`（JSONL 倒序分页加载）
- v45 集成：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_live/LiveTranslationPipeline.kt`（接入 LiveSessionStore + AudioFocusManager）
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_live/MixController.kt`（接入 AudioFocusManager 回调）
- MusicPlaybackService 桥接：
  - `app/src/main/java/com/lsl/kotlin_agent_app/media/MusicPlaybackService.kt`（新增：系统 AudioFocus 事件转发给 AudioFocusManager）
- CLI：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/radio/RadioCommand.kt`（`radio live` 扩展 `--save_*` flags + `list` 子命令）
- Tests：
  - `LiveSessionStore` 单测（目录创建、JSONL 追加、崩溃恢复模拟、磁盘空间检查）
  - `JsonlWriter` 单测（追加写、flush、并发安全）
  - `JsonlPagingSource` 单测（loadTail 200 行、loadPrevious 分页、到达文件开头返回 null、空文件处理）
  - `AudioFocusManager` 单测（app 内仲裁：Chat 抢占 → Radio 暂停 → Chat 释放 → Radio 恢复；系统焦点：LOSS → 全部暂停 → GAIN → 恢复快照；LOSS_DUCK → 全部降音量 → GAIN → 恢复；渐变参数验证）
  - `LiveSessionCardRenderer` / `JsonlSubtitleRenderer` canRender 单测
  - CLI argv 门禁 + `--save_*` flag 组合测试

## Steps（Strict / TDD）

1) Analysis：确定 JSONL flush 策略（每行 flush vs 每 N 行 flush，权衡性能与崩溃安全）；确定 `MusicPlaybackService` 转发系统 AudioFocus 事件的接口契约（回调 vs SharedFlow）；确定磁盘空间检查频率（启动时一次 + 每 60 秒周期检查，低于 100MB 时停止落盘并写 `_STATUS.md` 警告）；在目标设备（Nova 9）上做 IO benchmark（每 5 秒 flush JSONL + 写 audio chunk 的耗时）。
2) TDD Red：`JsonlWriter` 单测 — 追加写、flush、文件不存在时自动创建、并发写入安全。
3) TDD Green：实现 `JsonlWriter`。
4) TDD Red：`JsonlPagingSource` 单测 — 1000 行文件 loadTail 返回最后 200 行；连续 loadPrevious 返回更早的 200 行；到达开头返回 null；空文件 loadTail 返回空列表；单行文件正确处理。
5) TDD Green：实现 `JsonlPagingSource`。
6) TDD Red：`LiveSessionStore` 单测 — 创建目录结构、写入 `_meta.json`、追加 JSONL、写入 audio chunk（tmp + rename）、磁盘空间不足拒绝、崩溃后已写入内容完整。
7) TDD Green：实现 `LiveSessionStore`。
8) TDD Red：`AudioFocusManager` app 内仲裁单测 — requestFocus/releaseFocus 状态流转；Chat 抢占 Radio 场景；多次连续抢占/释放；渐变参数传递。
9) TDD Red：`AudioFocusManager` 系统焦点单测 — `FOCUS_LOSS_TRANSIENT` → 全部暂停 → `FOCUS_GAIN` → 恢复到中断前快照；`FOCUS_LOSS_DUCK` → 全部降音量 → `FOCUS_GAIN` → 恢复；`FOCUS_LOSS` → 全部暂停 → 不自动恢复；系统中断期间 app 内 requestFocus 挂起直到系统焦点恢复。
10) TDD Green：实现 `AudioFocusManager`。
11) TDD Red：v45 `LiveTranslationPipeline` 集成 `LiveSessionStore` 的单测 — 管线产出 segment 时 store 被正确调用；`--save_*` flag 控制哪些内容落盘。
12) TDD Green：集成 `LiveSessionStore` 到 `LiveTranslationPipeline`。
13) TDD Red：v45 `MixController` 集成 `AudioFocusManager` 的单测 — Chat TTS 到达时 MixController 收到暂停通知；Chat TTS 结束时收到恢复通知；系统 FOCUS_LOSS 时 MixController 收到全部暂停通知。
14) TDD Green：集成 `AudioFocusManager` 到 `MixController` + `MusicPlaybackService` 桥接。
15) TDD Red：Files 渲染器 `canRender` 单测（精确匹配 `*/live_*/` 路径模式）+ CLI `--save_*` flag 组合 + `radio live list` 测试。
16) TDD Green：实现渲染器 + CLI 扩展。
17) Verify：UT 全绿；真机冒烟（live + 落盘 5 分钟 → Files 浏览 → Chat TTS 抢占恢复 → 来电暂停/恢复 → JSON