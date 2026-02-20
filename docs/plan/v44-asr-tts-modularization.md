# v44 Plan：ASR/TTS Service 编排层 + Chat 语音输入 + 通道隔离

## Goal

在 v40（CloudAsrClient）和 v43（TtsClient）已有的 provider 接口之上，新增 service 编排层，统一管理通道隔离、Settings 配置：

- `AsrService`：在 `CloudAsrClient` 之上加通道隔离 + 文件转录 / 流式转录
- `TtsService`：在 `TtsClient` 之上加通道隔离
- Chat 语音输入最小闭环（麦克风 → ASR → 文字填入输入框）
- 通道隔离：Chat 独占通道，永远不被 Radio background 阻塞

## 架构分层

```
AsrService（v44 新增：通道隔离 + Settings）
  └── CloudAsrClient（v40 已有：provider 接口）
        ├── OpenAiWhisperClient（v40 已有）
        ├── AliyunAsrClient（未来）
        └── VolcEngineAsrClient（未来）

TtsService（v44 新增：通道隔离 + Settings）
  └── TtsClient（v43 已有：provider 接口）
        ├── OpenAiTtsClient（v43 已有）
        └── AndroidSystemTtsClient（未来）
```

v44 不修改 v40/v43 已有的 provider 接口，只在其上层做编排。v40 的 `TranscriptTaskManager` 和 v43 的 `TtsWorker` 改为通过 `AsrService` / `TtsService` 调用，而非直接持有 provider 实例。

## PRD Trace

- PRD-0034：REQ-0034-130 / REQ-0034-131 / REQ-0034-132

## Scope

做（v44）：

- `AsrService`：
  - `transcribeFile(file, channel)` — 文件级转录（复用 v40 的 `CloudAsrClient.transcribe()`）
  - `transcribeStream(pcmFlow, sampleRate, channel)` — 流式转录（麦克风 PCM → 实时文字）
- `TtsService`：
  - `synthesize(text, language, voice, channel)` — 复用 v43 的 `TtsClient.synthesize()`
- 通道隔离（3 条独立通道，互不阻塞）
- Settings 配置（provider 选择 + voice 配置 + 启用开关）
- Chat 语音输入最小闭环（仅语音输入，不做语音输出）
- v40 `TranscriptTaskManager` + v43 `TtsWorker` 重构为通过 Service 层调用

不做（v44）：

- 不做 Chat 语音输出 / Agent 回复朗读（v45）
- 不做完整实时翻译 UI（v45+）
- 不做"全语音聊天产品化"

## 通道隔离策略

核心原则：**Chat 通道永远不被 Radio background 阻塞。三条通道完全独立，各自串行，互不干扰。**

```
┌─────────────────────────────────────────────────────┐
│                    App 全局                          │
│                                                     │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────┐ │
│  │ Chat 通道    │  │ Radio 通道 A  │  │ Radio 通道 B│ │
│  │ (独占)       │  │ (background) │  │ (background)│ │
│  │             │  │              │  │             │ │
│  │ 语音输入 ASR │  │ 电台1 转录    │  │ 电台2 转录   │ │
│  │ Agent TTS   │  │ 电台1 TTS    │  │ 电台2 TTS   │ │
│  │             │  │              │  │             │ │
│  │ 并发：1      │  │ 并发：1       │  │ 并发：1      │ │
│  │ 永远可用     │  │ 可排队        │  │ 可排队       │ │
│  └─────────────┘  └──────────────┘  └────────────┘ │
│                                                     │
│  三条通道完全独立，各自内部串行，互不阻塞              │
└─────────────────────────────────────────────────────┘
```

设计要点：

- **Chat 通道**：独占 1 条通道，并发 1。用户发起语音输入时立即可用，无需等待任何 background 任务。Chat 通道内部串行（同一时刻只有一个 ASR 或 TTS 请求在执行）。
- **Radio 通道 A / B**：各独占 1 条通道，并发各 1。对应最多 2 个电台的后台任务（转录/翻译 TTS）。通道内部串行（同一电台的 chunk 按顺序处理）。第 3 个电台的请求被拒绝，返回 `NoChannelAvailable`。
- **通道之间零依赖**：Chat 通道不关心 Radio 通道的状态，Radio 通道之间也互不关心。没有共享信号量、没有共享队列、没有"等待其他通道释放"的逻辑。

```kotlin
/**
 * 三条完全独立的请求通道。
 * 每条通道内部串行（Mutex），通道之间零依赖。
 */
class ChannelDispatcher {

    enum class Channel {
        CHAT,           // 独占，永远可用
        RADIO_A,        // background 电台 1
        RADIO_B,        // background 电台 2
    }

    /**
     * 在指定通道内获取执行许可。
     * - CHAT：立即获取（通道内串行，但不等待 Radio）
     * - RADIO_A / RADIO_B：通道内串行排队
     *
     * @throws NoChannelAvailableException 当 RADIO_A 和 RADIO_B 都被不同 session 占用时，
     *         第三个 session 的请求直接拒绝（不排队）
     */
    suspend fun acquire(channel: Channel): ChannelPermit

    fun release(permit: ChannelPermit)

    /**
     * 为一个 radio session 分配通道。
     * 如果该 session 已有通道则复用，否则分配空闲通道。
     * 两个通道都被占用时返回 null。
     */
    fun assignRadioChannel(sessionId: String): Channel?

    /** 释放 radio session 对通道的占用 */
    fun releaseRadioChannel(sessionId: String)
}

data class ChannelPermit(
    val channel: ChannelDispatcher.Channel,
    val permitId: String,
)
```

### Radio 通道分配规则

Radio 通道的分配以"session"为粒度（一个录制会话 / 一个转录任务 / 一个 live 会话 = 一个 session）：

- 第一个 radio session 启动时，分配 `RADIO_A`
- 第二个 radio session 启动时，分配 `RADIO_B`
- 第三个 radio session 启动时，`assignRadioChannel()` 返回 null → 调用方返回 `NoChannelAvailable` 错误
- session 结束后释放通道，供后续 session 使用
- 同一个 session 的所有请求（多个 chunk 的转录、翻译 TTS）走同一条通道，通道内串行保证顺序

### 与 v39 录制并发上限的关系

v39 录制并发上限 ≤2（`MaxConcurrentRecordings`）。v44 的 Radio 通道数也是 2。这不是巧合——每个录制会话绑定一条 Radio 通道，录制 + 转录 + TTS 在同一通道内串行处理，天然保证不超载。

## 流式转录接口

```kotlin
/**
 * AsrService 的流式转录方法。
 * 输入：麦克风 PCM 数据流
 * 输出：逐步产出的文字片段
 */
suspend fun transcribeStream(
    pcmFlow: Flow<ByteArray>,
    sampleRate: Int,            // 16000
    channelCount: Int,          // 1 (mono)
    language: String?,          // null = 自动检测
    channel: Channel,           // CHAT / RADIO_A / RADIO_B
): Flow<StreamTranscriptEvent>

sealed class StreamTranscriptEvent {
    /** 中间结果（可能被后续结果覆盖） */
    data class Partial(val text: String) : StreamTranscriptEvent()
    /** 最终确认结果 */
    data class Final(val text: String) : StreamTranscriptEvent()
    /** 错误 */
    data class Error(val errorCode: String, val message: String) : StreamTranscriptEvent()
}
```

底层实现策略（Analysis 阶段确定）：

- 方案 A：真流式 — 对接火山引擎流式 ASR WebSocket（`format=ogg, codec=opus`），延迟最低
- 方案 B：伪流式 — 每 5 秒攒一段 PCM，调用 Whisper API 文件转录，拼接结果
- v44 先实现方案 B（伪流式，固定 5 秒窗口），方案 A 作为后续优化
- 5 秒窗口与 v45 AudioSplitter 的切段粒度对齐，避免集成时调整

## Chat 语音输入交互

```
Chat 输入框 → 点击麦克风按钮 → 进入录音状态
  → 实时显示 partial 文字（灰色，在输入框上方浮层）
  → 松开 / 点击停止 → final 文字填入输入框
  → 用户可编辑后发送
```

最小实现：

- 输入框右侧新增麦克风图标按钮
- 按下后开始录音（`AudioRecord` → PCM Flow）
- PCM Flow 喂给 `AsrService.transcribeStream(channel=CHAT)`
- partial 结果实时显示，final 结果填入输入框
- 录音时长上限 60 秒（超时自动停止）
- ASR 未启用时（`asr.enabled=false`），麦克风按钮灰显，点击弹 toast 提示去 Settings 开启
- Chat 通道独占，语音输入期间不受任何 Radio 后台任务影响

## Settings 配置项

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `asr.enabled` | bool | `false` | 是否启用云端 ASR（首次开启时弹隐私提示） |
| `asr.provider` | enum | `openai_whisper` | ASR 提供商：`openai_whisper` / `aliyun` / `volcengine` |
| `tts.enabled` | bool | `false` | 是否启用云端 TTS（首次开启时弹隐私提示） |
| `tts.provider` | enum | `openai_tts` | TTS 提供商：`openai_tts` / `android_system` |
| `tts.voice.ja` | string | `alloy` | 日语默认 voice |
| `tts.voice.zh` | string | `alloy` | 中文默认 voice |
| `tts.voice.en` | string | `alloy` | 英语默认 voice |

provider 切换后，对应的 API key 配置项自动出现（如 `openai.apiKey` 已在现有 Settings 中）。

provider 切换的生效时机：已提交到通道内排队的请求用旧 provider 跑完，新请求用新 provider。不做热切换。

## 错误码集合

| error_code | 含义 |
|------------|------|
| `AsrDisabled` | ASR 未启用（Settings 中关闭） |
| `TtsDisabled` | TTS 未启用（Settings 中关闭） |
| `ProviderNotConfigured` | 选择的 provider 缺少 API key 或必要配置 |
| `NoChannelAvailable` | 2 条 Radio 通道都被占用，第 3 个 session 被拒绝 |
| `RecordingPermissionDenied` | 麦克风权限未授予 |
| `RecordingTimeout` | 录音超过 60 秒上限，自动停止 |
| `StreamConnectionFailed` | 流式 ASR 连接失败 |
| `StreamInterrupted` | 流式 ASR 连接中断 |
| `AsrNetworkError` | 网络不可达 |
| `AsrRemoteError` | 云端 API 返回非 2xx |
| `ProviderQuotaExceeded` | API 配额耗尽 |

注意：不再有 `ConcurrencyLimitReached`。Chat 通道永远可用不排队；Radio 通道内部串行排队是正常行为不报错；只有第 3 个 session 抢不到通道时才报 `NoChannelAvailable`。

## Acceptance（硬 DoD）

- 分层正确：`AsrService` 通过 `CloudAsrClient` 调用 provider，不直接持有 HTTP client；`TtsService` 同理通过 `TtsClient`。
- 通道隔离：2 个 Radio 通道各跑一个长任务时，Chat 通道的 ASR 请求立即执行，零等待。这是最核心的验收条件。
- 通道独立性：Radio 通道 A 的任务失败/超时不影响 Radio 通道 B 和 Chat 通道。
- 通道上限：第 3 个 radio session 请求通道时，返回 `NoChannelAvailable`。
- 文件转录：`AsrService.transcribeFile()` 行为与 v40 直接调用 `CloudAsrClient` 一致（透传，不改变结果）。
- 流式转录：`AsrService.transcribeStream()` 能产出 `Partial` 和 `Final` 事件（伪流式：每 5 秒一个 Final）。
- Chat 语音输入：麦克风按钮可用 → 录音 → ASR → 文字填入输入框，端到端可用。
- Settings：`asr.enabled=false` 时麦克风按钮灰显；切换 provider 后新请求使用新 provider，已排队请求不受影响。
- 隐私提示：首次开启 `asr.enabled` 或 `tts.enabled` 时弹出提示"音频数据将发送到 [provider 名称] 云端处理"。
- v40/v43 回归：重构后 `radio transcript` 和 `radio tts` 功能不退化。
- CLI help：无新增 CLI（v44 的 ASR/TTS 通过 Service 层被现有 CLI 间接使用）。

验证命令：

- `.\gradlew.bat :app:testDebugUnitTest`
- 真机冒烟：Chat 页签 → 麦克风按钮 → 说一句话 → 文字出现在输入框（同时后台有 Radio 转录任务在跑）

## Files（规划）

- Service 编排层：
  - `app/src/main/java/com/lsl/kotlin_agent_app/asr/AsrService.kt`（编排层，持有 `CloudAsrClient` + `ChannelDispatcher`）
  - `app/src/main/java/com/lsl/kotlin_agent_app/asr/StreamTranscriptEvent.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/tts/TtsService.kt`（编排层，持有 `TtsClient` + `ChannelDispatcher`）
- 通道调度：
  - `app/src/main/java/com/lsl/kotlin_agent_app/media/ChannelDispatcher.kt`
- Settings 扩展：
  - 现有 Settings 结构中新增 ASR/TTS 配置项
- Chat 语音输入：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/chat/VoiceInputButton.kt`（Compose 组件）
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/chat/VoiceInputViewModel.kt`
- v40/v43 重构（改为通过 Service 层调用）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_transcript/TranscriptTaskManager.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_tts/TtsBilingualWorker.kt`
- Tests：
  - `ChannelDispatcher` 通道隔离单测
  - `AsrService` / `TtsService` mock provider 单测
  - `VoiceInputViewModel` 状态机单测
  - v40/v43 回归测试

## Steps（Strict / TDD）

1) Analysis：确定 `ChannelDispatcher` 的内部实现（3 个独立 Mutex，无共享状态）；确定流式转录的伪流式窗口（固定 5 秒）；列出 Settings 配置项的 UI 布局；确认麦克风权限申请流程（Android 13+ `RECORD_AUDIO`）。
2) TDD Red：`ChannelDispatcher` 通道隔离单测 —— Chat 通道在 Radio 通道满载时仍立即获取许可；Radio 通道 A/B 各自内部串行；第 3 个 session 分配通道返回 null；session 释放后通道可复用。
3) TDD Green：实现 `ChannelDispatcher`（3 个独立 `Mutex`，`assignRadioChannel` 用 `ConcurrentHashMap<String, Channel>` 跟踪 session→channel 映射）。
4) TDD Red：`AsrService` 单测 — `transcribeFile()` 透传 provider 结果 + 走指定通道；`transcribeStream()` 伪流式每 5 秒产出 Final 事件；`AsrDisabled` / `ProviderNotConfigured` 前置校验。
5) TDD Red：`TtsService` 单测 — `synthesize()` 透传 + 走指定通道；`TtsDisabled` / `ProviderNotConfigured` 前置校验。
6) TDD Green：实现 `AsrService` + `TtsService`，接入 `ChannelDispatcher`。
7) TDD Red：`VoiceInputViewModel` 状态机单测 — idle → recording → transcribing → done；超时 60 秒自动停止；ASR disabled 时拒绝启动；录音期间 Radio 后台任务不影响状态流转。
8) TDD Green：实现 `VoiceInputButton` + `VoiceInputViewModel` + 麦克风录音 → PCM Flow → AsrService（channel=CHAT）。
9) Refactor：v40 `TranscriptTaskManager` + v43 `TtsBilingualWorker` 改为通过 Service 层调用（使用 `assignRadioChannel(sessionId)` 获取通道），跑回归测试确认不退化。
10) Verify：UT 全绿；真机冒烟（Chat 语音输入 + 同时 2 个 Radio 转录任务在跑，Chat 零延迟响应）。

## Risks

- 伪流式延迟：每 5 秒才出一次结果，用户体验不如真流式。v44 先接受，后续版本可升级为火山引擎 WebSocket 真流式。
- 麦克风权限：Android 13+ 需要 `RECORD_AUDIO` 权限，首次使用时的权限申请流程需要测试覆盖。
- v40/v43 回归风险：重构调用链路后必须跑完整回归，确认 `radio transcript` 和 `radio tts` 不退化。
- provider 切换的生效时机：已提交的请求用旧 provider 跑完，新请求用新 provider。实现上需要在 `acquire` 时快照当前 provider 配置，而非在 `execute` 时读取。
- 隐私合规：首次启用 ASR/TTS 的隐私提示措辞需要审慎，明确告知"音频数据将上传到 [provider 名称] 云端处理"。
- 3 条通道 = 最多 3 个并发 HTTP 请求到同一个 API provider：如果 provider 有 rate limit（如 OpenAI Whisper 的 RPM 限制），3 条通道同时发请求可能触发限流。建议在 `CloudAsrClient` / `TtsClient` 实现层做 per-provider 的 rate limit 兜底（429 重试），不在通道层处理。