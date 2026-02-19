# v40 Plan：Radio 离线转录（TranscriptTaskManager + CloudAsrClient + `radio transcript`）

## Goal

对 v39 录制产物提供"离线转录"的后台慢任务闭环：

- 为录制会话创建 `transcripts/`，任务/进度以文件形式可见
- 云端 ASR 把每个 `chunk_NNN.ogg` 转为 `chunk_NNN.transcript.json`
- CLI：`radio transcript start|status|list|cancel`

## 录制会话与转录任务的目录关系

v39 录制产物与 v40 转录任务、v41 翻译产物的完整嵌套结构：

```
workspace/radio_recordings/
  rec_20260219_140000_a1b2c3/           # v39 录制会话
    _meta.json                           # 录制元信息（state/stationId/...）
    _STATUS.md
    chunk_001.ogg                        # 10min 音频切片
    chunk_002.ogg
    ...
    transcripts/                         # v40 转录任务根目录
      _tasks.index.json                  # 该 session 下所有转录任务索引
      tx_abc_ja/                         # v40 转录任务（source_lang=ja）
        _task.json                       # 任务状态/进度
        _STATUS.md
        chunk_001.transcript.json        # v40 转录产物
        chunk_001.translation.json       # v41 翻译产物（若指定了 target_lang）
        chunk_002.transcript.json
        chunk_002.translation.json
      tx_abc_ja2zh/                      # v41 多语言对任务（ja→zh）
        _task.json
        _STATUS.md
        chunk_001.transcript.json
        chunk_001.translation.json
      tx_abc_ja2en/                      # v41 多语言对任务（ja→en）
        _task.json
        _STATUS.md
        chunk_001.transcript.json
        chunk_001.translation.json
```

关键规则：

- `transcripts/` 位于录制会话目录内部（`rec_*/transcripts/`）
- 每个转录任务有独立的 `tx_*/` 子目录
- `_tasks.index.json` 位于 `transcripts/` 根，索引该 session 下所有任务
- transcript/translation 文件与源 chunk 同名（只是后缀不同），便于定位对应音频

## PRD Trace

- PRD-0034：REQ-0034-050 / REQ-0034-051 / REQ-0034-052 / REQ-0034-053

## Scope

做（v40）：

- `TranscriptTaskManager`（串行处理 chunk，避免 API 并发爆炸）
- WorkManager 集成：可恢复、可取消
- `_task.json` + `_tasks.index.json` + `_STATUS.md` 落盘与持续更新
- 云端 ASR 抽象层：
  - `CloudAsrClient` 接口（输入 audio file + 语言，输出 segments）
  - `OpenAiWhisperClient` 作为第一个实现
  - 上传 `.ogg` chunk（MIME type: `audio/ogg`）
  - 解析 `verbose_json`（带 timestamps）为 segments
  - 错误码归一：AsrNetworkError / AsrRemoteError / InvalidArgs / …
- CLI：`radio transcript ...`（受控输入：sessionId 或录制目录）

不做（v40）：

- 不做翻译（v41）
- 不做"实时"转录（v44）
- 不做对 `state=recording`（仍在录制中）的 session 发起转录（明确拒绝，错误码 `SessionStillRecording`）

## ASR 提供商抽象

```kotlin
interface CloudAsrClient {
    /** 转录单个音频文件，返回带时间戳的 segments */
    suspend fun transcribe(
        audioFile: File,
        mimeType: String,       // "audio/ogg"
        language: String?,      // null = 自动检测
    ): AsrResult
}

data class AsrResult(
    val segments: List<AsrSegment>,
    val detectedLanguage: String?,
)

data class AsrSegment(
    val id: Int,
    val startSec: Double,
    val endSec: Double,
    val text: String,
)
```

v40 交付 `OpenAiWhisperClient` 实现；后续版本可插入 `AliyunAsrClient`、`VolcEngineAsrClient`，无需改动 TaskManager。

## Acceptance（硬 DoD）

- 前置校验：`radio transcript start --session <sessionId>` 必须校验 `_meta.json` 存在且 `state` 为 `completed`（或 `cancelled`/`failed` 但已有 chunks）；对 `state=recording` 的 session 必须返回 `error_code=SessionStillRecording`。
- 任务可见性：启动任务后必须产生 `{task_id}/_task.json` 和 `{task_id}/_STATUS.md`，`_task.json` 中体现 `totalChunks/transcribedChunks/failedChunks` 进度。
- 单 chunk 原子性：每完成一个 chunk 的转录，必须先写 `chunk_NNN.transcript.json` 再更新 `_task.json`（避免"进度已走但文件缺失"）。
- 可恢复：WorkManager 中断后再次启动时，扫描 `{task_id}/` 目录下已存在的 `chunk_NNN.transcript.json`，跳过已完成的 chunk；`_task.json` 的 `transcribedChunks` 计数基于实际文件存在性重算，不依赖内存状态。
- 取消语义：`radio transcript cancel --task <taskId>` 后，`_task.json` 的 `state=cancelled`，已完成的 chunk transcript 文件保留不删。
- CLI help：`radio transcript --help` / `radio help transcript` 为 0。

验证命令：

- `.\gradlew.bat :app:testDebugUnitTest`

## Files（规划）

- 转录模块（新包）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_transcript/*`
    - `TranscriptTaskManager.kt`（串行队列 + WorkManager 调度）
    - `TranscriptTaskStore.kt`（读写 `_task.json` / `_tasks.index.json` / `_STATUS.md`）
    - `TranscriptChunkV1.kt` / `TranscriptTaskV1.kt`（kotlinx.serialization data class）
- ASR 抽象层（建议独立子包，未来 Chat 语音输入可复用）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/asr/*`
    - `CloudAsrClient.kt`（接口）
    - `AsrResult.kt` / `AsrSegment.kt`
    - `OpenAiWhisperClient.kt`（第一个实现）
- CLI：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/radio/RadioCommand.kt`（新增 `transcript` 子命令）
- Tests：
  - `app/src/test/java/...`（MockWebServer + store 读写测试 + argv 门禁 + 恢复逻辑测试）

## `radio transcript` CLI 命令

```
radio transcript start --session <sessionId> --source_lang ja
radio transcript status --task <taskId>
radio transcript list --session <sessionId>
radio transcript cancel --task <taskId>
```

`radio transcript list` 粒度为 session 级（列出该 session 下所有转录任务），不提供全局列表。

`radio transcript start` 的 result：

```json
{
  "task_id": "tx_20260219_1600_x1y2z3",
  "session_id": "rec_20260219_140000_a1b2c3",
  "state": "pending",
  "source_language": "ja",
  "total_chunks": 12,
  "message": "Transcript task created, 12 chunks queued"
}
```

前置校验失败时：

```json
{
  "error_code": "SessionStillRecording",
  "error_message": "Session rec_... is still recording. Stop recording first."
}
```

## 错误码集合

| error_code | 含义 |
|------------|------|
| `InvalidArgs` | 参数缺失或非法 |
| `SessionNotFound` | sessionId 对应目录或 `_meta.json` 不存在 |
| `SessionStillRecording` | session `state=recording`，拒绝转录 |
| `SessionNoChunks` | session 存在但无 chunk 文件 |
| `TaskNotFound` | taskId 对应目录或 `_task.json` 不存在 |
| `TaskAlreadyExists` | 该 session 已有相同 source_lang 的进行中任务 |
| `AsrNetworkError` | ASR API 网络不可达 |
| `AsrRemoteError` | ASR API 返回非 2xx |
| `AsrParseError` | ASR 返回内容无法解析为 segments |

## Steps（Strict / TDD）

1) Analysis：固化 `_task.json` 最小字段与错误码集合；确认 OpenAI Whisper API 对 `.ogg`（`audio/ogg`）上传的 MIME type 与 `response_format=verbose_json` 的返回结构；明确"串行 + 每 chunk 立即落盘"的一致性规则。
2) TDD Red：CLI help + argv 校验 + sessionId 解析门禁 + `SessionStillRecording` / `SessionNoChunks` 前置校验测试。
3) TDD Red：`TranscriptTaskStore` 单测 — 创建任务（`_task.json` + `_STATUS.md`）、更新进度、生成 `_tasks.index.json`、取消后状态变更。
4) TDD Red：`OpenAiWhisperClient` 在 MockWebServer 下解析 `verbose_json` → segments 的单测（含 NetworkError / RemoteError / AsrParseError 映射）。
5) TDD Red：恢复逻辑单测 — 模拟 `{task_id}/` 下已有部分 `chunk_NNN.transcript.json`，验证 TaskManager 跳过已完成 chunk 并正确重算进度。
6) TDD Green：实现 `TranscriptTaskManager` 串行队列 + WorkManager glue + `CloudAsrClient` 接口 + `OpenAiWhisperClient`，跑到绿。
7) Verify：UT 全绿；手动用一个短录音 chunk 做真机转录验证（可选但推荐）。

## Risks

- Whisper API 费用/网络：测试必须用 MockWebServer；真机仅最小验证。
- JSON 体积：每 chunk transcript 可能较大（10min 语音 ≈ 数百 segments），UI/CLI 默认输出必须摘要化（`transcribedChunks/totalChunks`），完整内容走文件浏览。
- OGG MIME type：OpenAI Whisper API 上传时需确认 `audio/ogg` 被正确识别为 Opus 编码；Analysis 阶段做一次手动 curl 验证。