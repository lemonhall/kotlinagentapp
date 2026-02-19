```markdown
# v44 Plan：ASR/TTS Service 编排层 + Chat 语音输入 + 并发隔离

## Goal

在 v40（CloudAsrClient）和 v43（TtsClient）已有的 provider 接口之上，新增 service 编排层，统一管理并发、优先级、队列和 Settings 配置：

- `AsrService`：在 `CloudAsrClient` 之上加并发控制 + 优先级队列 + 文件转录 / 流式转录
- `TtsService`：在 `TtsClient` 之上加并发控制 + 优先级队列
- Chat 语音输入最小闭环（麦克风 → ASR → 文字填入输入框）
- 并发隔离：Chat 高优先级，Radio background 低优先级，互不拖死

## 架构分层

```
AsrService（v44 新增：并发控制 + 优先级 + 队列 + Settings）
  └── CloudAsrClient（v40 已有：provider 接口）
        ├── OpenAiWhisperClient（v40 已有）
        ├── AliyunAsrClient（未来）
        └── VolcEngineAsrClient（未来）

TtsService（v44 新增：并发控制 + 优先级 + 队列 + Settings）
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
  - `transcribeFile(file, priority)` — 文件级转录（复用 v40 的 `CloudAsrClient.transcribe()`）
  - `transcribeStream(pcmFlow, sampleRate, priority)` — 流式转录（麦克风 PCM → 实时文字）
- `TtsService`：
  - `synthesize(text, language, voice, priority)` — 复用 v43 的 `TtsClient.synthesize()`
- 并发隔离（双队列 + 预留槽位）
- Settings 配置（provider 选择 + voice 配置 + 启用开关）
- Chat 语音输入最小闭环（仅语音输入，不做语音输出）
- v40 `TranscriptTaskManager` + v43 `TtsWorker` 重构为通过 Service 层调用

不做（v44）：

- 不做 Chat 语音输出 / Agent 回复朗读（v45）
- 不做完整实时翻译 UI（v45+）
- 不做"全语音聊天产品化"

## 并发隔离策略

```
总并发上限：3 个 ASR/TTS 请求同时执行

chatQueue（高优先级）：
  - 预留 1 个并发槽位（始终可用，不被 background 占满）
  - 来源：Chat 语音输入、Chat 语音输出（v45）

backgroundQueue（低优先级）：
  - 最多 2 个并发
  - 来源：Radio 转录（v40）、Radio TTS（v43）

调度规则：
  - background 任务最多占用 2 个槽位
  - chat 任务到达时，如果 3 个槽位都被 background 占满，
    等待最近一个 background 任务完成后立即插入（不抢占正在执行的请求）
  - chat 任务之间 FIFO
  - background 任务之间 FIFO
```

```kotlin
class MediaServiceDispatcher(
    private val maxTotal: Int = 3,
    private val maxBackground: Int = 2,
) {
    enum class Priority { CHAT, BACKGROUND }

    /**
     * 获取执行许可。chat 优先级保证至少 1 个槽位可用。
     * 挂起直到获得许可。
     */
    suspend fun acquire(priority: Priority)

    fun release()
}
```

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
    priority: Priority,         // CHAT / BACKGROUND
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
- 方案 B：伪流式 — 每 3-5 秒攒一段 PCM，调用 Whisper API 文件转录，拼接结果
- v44 先实现方案 B（伪流式），方案 A 作为后续优化

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
- PCM Flow 喂给 `AsrService.transcribeStream(priority=CHAT)`
- partial 结果实时显示，final 结果填入输入框
- 录音时长上限 60 秒（超时自动停止）
- ASR 未启用时（`asr.enabled=false`），麦克风按钮灰显，点击弹 toast 提示去 Settings 开启

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

## 错误码集合

| error_code | 含义 |
|------------|------|
| `AsrDisabled` | ASR 未启用（Settings 中关闭） |
| `TtsDisabled` | TTS 未启用（Settings 中关闭） |
| `ProviderNotConfigured` | 选择的 provider 缺少 API key 或必要配置 |
| `ConcurrencyLimitReached` | background 队列并发槽位已满（排队等待中） |
| `RecordingPermissionDenied` | 麦克风权限未授予 |
| `RecordingTimeout` | 录音超过 60 秒上限，自动停止 |
| `StreamConnectionFailed` | 流式 ASR 连接失败 |
| `StreamInterrupted` | 流式 ASR 连接中断 |
| `AsrNetworkError` | 网络不可达 |
| `AsrRemoteError` | 云端 API 返回非 2xx |
| `ProviderQuotaExceeded` | API 配额耗尽 |

## Acceptance（硬 DoD）

- 分层正确：`AsrService` 通过 `CloudAsrClient` 调用 provider，不直接持有 HTTP client；`TtsService` 同理通过 `TtsClient`。
- 并发隔离：同时发起 2 个 background + 1 个 chat 请求，chat 请求不被阻塞；同时发起 3 个 background 请求，第 3 个排队等待。
- 文件转录：`AsrService.transcribeFile()` 行为与 v40 直接调用 `CloudAsrClient` 一致（透传，不改变结果）。
- 流式转录：`AsrService.transcribeStream()` 能产出 `Partial` 和 `Final` 事件（伪流式：每 3-5 秒一个 Final）。
- Chat 语音输入：麦克风按钮可用 → 录音 → ASR → 文字填入输入框，端到端可用。
- Settings：`asr.enabled=false` 时麦克风按钮灰显；切换 provider 后下次 ASR 调用使用新 provider。
- 隐私提示：首次开启 `asr.enabled` 或 `tts.enabled` 时弹出提示"音频数据将发送到云端服务"。
- v40/v43 回归：重构后 `radio transcript` 和 `radio tts` 功能不退化。
- CLI help：无新增 CLI（v44 的 ASR/TTS 通过 Service 层被现有 CLI 间接使用）。

验证命令：

- `.\gradlew.bat :app:testDebugUnitTest`
- 真机冒烟：Chat 页签 → 麦克风按钮 → 说一句话 → 文字出现在输入框

## Files（规划）

- Service 编排层：
  - `app/src/main/java/com/lsl/kotlin_agent_app/asr/AsrService.kt`（编排层，持有 `CloudAsrClient` + `MediaServiceDispatcher`）
  - `app/src/main/java/com/lsl/kotlin_agent_app/asr/StreamTranscriptEvent.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/tts/TtsService.kt`（编排层，持有 `TtsClient` + `MediaServiceDispatcher`）
- 并发调度：
  - `app/src/main/java/com/lsl/kotlin_agent_app/media/MediaServiceDispatcher.kt`
- Settings 扩展：
  - 现有 Settings 结构中新增 ASR/TTS 配置项
- Chat 语音输入：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/chat/VoiceInputButton.kt`（Compose 组件）
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/chat/VoiceInputViewModel.kt`
- v40/v43 重构（改为通过 Service 层调用）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_transcript/TranscriptTaskManager.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_tts/TtsBilingualWorker.kt`
- Tests：
  - `MediaServiceDispatcher` 并发单测
  - `AsrService` / `TtsService` mock provider 单测
  - `VoiceInputViewModel` 状态机单测
  - v40/v43 回归测试

## Steps（Strict / TDD）

1) Analysis：确定 `MediaServiceDispatcher` 的槽位策略（预留 vs 抢占）；确定流式转录的实现方案（伪流式 vs 真流式）；列出 Settings 配置项的 UI 布局；确认麦克风权限申请流程。
2) TDD Red：`MediaServiceDispatcher` 并发单测 — 2 background + 1 chat 不阻塞；3 background 第 3 个排队；chat 到达时 background 满载的等待行为。
3) TDD Green：实现 `MediaServiceDispatcher`。
4) TDD Red：`AsrService` 单测 — `transcribeFile()` 透传 provider 结果 + 并发控制；`transcribeStream()` 伪流式产出 Partial/Final 事件。
5) TDD Red：`TtsService` 单测 — `synthesize()` 透传 + 并发控制；`TtsDisabled` / `ProviderNotConfigured` 错误码。
6) TDD Green：实现 `AsrService` + `TtsService`，接入 `MediaServiceDispatcher`。
7) TDD Red：`VoiceInputViewModel` 状态机单测 — idle → recording → transcribing → done；超时自动停止；ASR disabled 时拒绝启动。
8) TDD Green：实现 `VoiceInputButton` + `VoiceInputViewModel` + 麦克风录音 → PCM Flow → AsrService。
9) Refactor：v40 `TranscriptTaskManager` + v43 `TtsBilingualWorker` 改为通过 Service 层调用，跑回归测试确认不退化。
10) Verify：UT 全绿；真机冒烟（Chat 语音输入 + Radio 转录并发不冲突）。

## Risks

- 伪流式延迟：每 3-5 秒才出一次结果，用户体验不如真流式。v44 先接受，后续版本可升级为火山引擎 WebSocket 真流式。
- 麦克风权限：Android 13+ 需要 `POST_NOTIFICATIONS` + `RECORD_AUDIO` 权限，首次使用时的权限申请流程需要测试覆盖。
- v40/v43 回归风险：重构调用链路后必须跑完整回归，确认 `radio transcript` 和 `radio tts` 不退化。
- provider 切换的热生效：用户在 Settings 切换 provider 后，正在执行的任务是否受影响？建议"已提交的任务用旧 provider 跑完，新任务用新 provider"。
- 隐私合规：首次启用 ASR/TTS 的隐私提示措辞需要审慎，明确告知"音频数据将上传到 [provider 名称] 云端处理"。
```