# v41 Plan：Radio 离线翻译（TranslationWorker + `translation.json` + 边录边转）

## Goal

在 v40 转录基础上，交付离线翻译闭环：

- 将 `chunk_NNN.transcript.json` 翻译为 `chunk_NNN.translation.json`（时间戳对齐）
- 支持"录制完成后自动转录+翻译"与"边录边转"
- 一个 task 对应一个语言对（source→target），多语言翻译创建多个 task

## PRD Trace

- PRD-0034：REQ-0034-080 / REQ-0034-081

## Scope

做（v41）：

- `TranslationClient` 接口抽象 + `OpenAiTranslationClient` 第一个实现
- `TranslationWorker`（LLM 翻译，按 segment 批处理，每批 10-20 segments，含上下文窗口）
- translation 落盘 schema 固化（与 transcript segment 对齐：同 id/startSec/endSec）
- 录制 meta 支持 `transcriptRequest`（自动转录/翻译参数）
- "边录边转"模式：
  - 修改 v40 的 `SessionStillRecording` 硬拒绝为"允许但需确认"：CLI 需加 `--streaming` flag
  - `transcriptRequest.autoStart=true` 触发时自动进入 streaming 模式
  - TaskManager 知道 chunk 列表动态增长，持续轮询新 chunk
- CLI：`radio transcript start` 扩展 `--target_lang` 参数（必做，非可选）

不做（v41）：

- 不做语言学习 UI（v42）
- 不做实时翻译管线（v44+）
- 不做术语表/摘要增强（后续版本追加）

## 翻译抽象层

```kotlin
interface TranslationClient {
    /**
     * 批量翻译 segments。
     * @param context 前一批最后 2-3 句，用于提高连贯性（可为空）
     */
    suspend fun translateBatch(
        segments: List<TranscriptSegment>,
        context: List<TranscriptSegment>,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationBatchResult
}

data class TranslationBatchResult(
    val translatedSegments: List<TranslatedSegment>,
)

data class TranslatedSegment(
    val id: Int,
    val startSec: Double,
    val endSec: Double,
    val sourceText: String,
    val translatedText: String,
)
```

v41 交付 `OpenAiTranslationClient` 实现（复用 app 已有的 LLM 调用通道，但走独立队列，不与 Chat 对话抢并发）；后续可插入阿里云通义、火山豆包等实现。

## 批处理策略

- 每批 10-20 个 segments（约 30-60 秒内容）
- 每批 prompt 包含前一批最后 2-3 句作为 context，提高跨批连贯性
- 超过 LLM token 限制时自动拆分为更小的批次
- 单批失败最多重试 3 次，超过则标记该批 segments 为 failed，继续下一批

## 语言对口径

一个 task 对应一个语言对（source→target）。用户想同时生成 ja→zh 和 ja→en 两份翻译，需创建两个 task，各自独立目录、独立进度。VFS 结构清晰：

```
transcripts/
  tx_abc_ja2zh/
    _task.json          # sourceLanguage=ja, targetLanguage=zh
    chunk_001.transcript.json
    chunk_001.translation.json
  tx_abc_ja2en/
    _task.json          # sourceLanguage=ja, targetLanguage=en
    chunk_001.transcript.json
    chunk_001.translation.json
```

注意：同一 session 的多个 task 共享转录结果（`transcript.json` 内容相同），但各自独立落盘一份，避免跨目录引用的复杂性。

## 边录边转模式

v40 原有的 `SessionStillRecording` 硬拒绝逻辑调整为：

- CLI 不带 `--streaming`：对 `state=recording` 的 session 仍然报 `SessionStillRecording`（保持 v40 默认行为安全）
- CLI 带 `--streaming`：允许对 `state=recording` 的 session 创建转录任务，`_task.json` 标记 `mode=streaming`
- `transcriptRequest.autoStart=true`：录制开始时自动创建 `mode=streaming` 任务，每产出一个新 `chunk_NNN.ogg`，TaskManager 自动将其加入队列

streaming 模式下的 `_task.json` 额外字段：

```json
{
  "mode": "streaming",
  "totalChunks": null,
  "knownChunks": 5,
  "transcribedChunks": 3,
  "translatedChunks": 2,
  "waitingForMoreChunks": true
}
```

`totalChunks=null` 表示总量未知（录制仍在进行）；录制完成后 TaskManager 收到通知，将 `totalChunks` 设为最终值，`waitingForMoreChunks=false`。

## Acceptance（硬 DoD）

- 对齐：`translation.json` 中每个 segment 必须带 `sourceText/translatedText`，且 `id/startSec/endSec` 与来源 transcript 严格一致。
- 进度可解释：`_task.json` 中 `translatedChunks` 必须单调递增，失败 chunk 必须计入 `failedChunks` 并可继续后续 chunk。
- 自动触发：录制会话 `_meta.json.transcriptRequest.autoStart=true` 时，录制完成（或录制开始，若含 streaming 配置）会自动创建任务并进入队列。
- 边录边转：`--streaming` 模式下，新 chunk 产出后 ≤30 秒内被 TaskManager 感知并加入队列。
- CLI 完整性：`radio transcript start --session <sid> --source_lang ja --target_lang zh` 必须可用；不带 `--target_lang` 则只转录不翻译。
- 多语言对：同一 session 可创建多个不同 target_lang 的 task，互不干扰。
- CLI help：`radio transcript --help` 为 0。

验证命令：

- `.\gradlew.bat :app:testDebugUnitTest`

## 错误码集合

| error_code | 含义 |
|------------|------|
| `InvalidArgs` | 参数缺失或非法（如 source_lang 与 target_lang 相同） |
| `SessionNotFound` | sessionId 对应目录或 `_meta.json` 不存在 |
| `SessionStillRecording` | session `state=recording` 且未指定 `--streaming` |
| `SessionNoChunks` | session 存在但无 chunk 文件 |
| `TaskNotFound` | taskId 对应目录或 `_task.json` 不存在 |
| `TaskAlreadyExists` | 该 session 已有相同 source_lang + target_lang 的进行中任务 |
| `TranscriptNotReady` | 该 chunk 的 transcript.json 尚未生成（翻译依赖转录） |
| `TranslationAlreadyExists` | 该 chunk 的 translation.json 已存在（跳过） |
| `LlmNetworkError` | LLM API 网络不可达 |
| `LlmRemoteError` | LLM API 返回非 2xx |
| `LlmParseError` | LLM 返回内容无法解析为翻译结果 |
| `LlmQuotaExceeded` | LLM API 配额耗尽 |

## Files（规划）

- 翻译抽象层（建议独立子包，未来其他模块可复用）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/translation/*`
    - `TranslationClient.kt`（接口）
    - `TranslationBatchResult.kt` / `TranslatedSegment.kt`
    - `OpenAiTranslationClient.kt`（第一个实现）
- 翻译 Worker（放在 v40 同包）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_transcript/TranslationWorker.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_transcript/TranslationChunkV1.kt`
- 录制 meta 扩展：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_recordings/RecordingMetaV1.kt`（新增 `transcriptRequest` 字段）
- v40 前置校验修改：
  - `TranscriptTaskManager.kt`：`SessionStillRecording` 从硬拒绝改为"无 `--streaming` 时拒绝，有则允许"
- CLI：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/radio/RadioCommand.kt`
    - `radio transcript start` 扩展 `--target_lang` 和 `--streaming` 参数
- Tests：
  - MockWebServer + translation schema 对齐测试 + 批处理拆分测试 + streaming 模式测试

## Steps（Strict / TDD）

1) Analysis：确定翻译 prompt 的最小稳定口径（输入：segments[] + context[] + language pair；输出：逐条翻译 JSON）；确定批处理大小（10-20 segments）与 context 窗口（前一批末尾 2-3 句）；确定多语言对的目录策略（独立落盘 vs 引用共享）。
2) TDD Red：`TranslationClient` 接口 + `OpenAiTranslationClient` mock 测试 — 正常翻译、超时、限流、返回缺字段的鲁棒性。
3) TDD Red：translation schema 对齐单测 — 输入 transcript segments，产出 translation segments，断言 id/startSec/endSec 严格一致。
4) TDD Red：批处理拆分单测 — 验证 >20 segments 自动拆批、context 窗口正确传递。
5) TDD Green：实现 `TranslationWorker` + 与 `TranscriptTaskManager` 的串行调度；每 chunk 翻译完成即落盘 `translation.json` 再更新 `_task.json`。
6) TDD Red：自动触发测试 — `transcriptRequest.autoStart=true` + `targetLanguage` 存在时，录制完成回调创建含翻译的任务。
7) TDD Red：边录边转测试 — `--streaming` 模式下，模拟新 chunk 产出，验证 TaskManager 感知并处理。
8) TDD Green：修改 v40 的 `SessionStillRecording` 逻辑，实现 streaming 模式。
9) Verify：UT 全绿；用 1 个短 chunk 做端到端（转录+翻译）手动验证（可选）。

## Risks

- 翻译一致性（术语、人名）：v41 先保证"可用"，术语表/摘要属于增强（后续版本追加）。
- LLM 成本：10min chunk ≈ 数百 segments，每批 10-20 个，单个 chunk 可能需要 10-30 次 LLM 调用。测试必须用 mock。
- 边录边转的 chunk 感知延迟：依赖文件系统轮询或 `RecordingService` 的回调通知，需在 Analysis 阶段确定机制。回答：用回调通知而非轮询。RecordingService 每完成一个 chunk 的 rename（chunk_NNN.ogg.tmp → chunk_NNN.ogg）时发一个事件（SharedFlow 或 BroadcastChannel），TranscriptTaskManager 订阅即可。轮询有延迟且浪费资源。
- 多 task 共享 transcript 的目录策略：独立落盘