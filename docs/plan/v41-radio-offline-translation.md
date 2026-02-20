# v41 Plan：Radio 离线翻译（录完自动转录+翻译 Pipeline）

## Goal

在 v40 转录基础上，交付"录完即出译文"的完整闭环：

- 录制开始前设定好目标语言
- 录制结束后自动触发：转录 → 翻译，串行 pipeline，无需手动干预
- 也支持对已有录音手动触发翻译（长按菜单）
- 翻译结果与转录 segment 时间戳对齐，双语对照展示

## PRD Trace

- PRD-0034：REQ-0034-080 / REQ-0034-081

## Scope

做（v41）：

- 录制设置 UI：开始录制前选择"是否翻译"及目标语言，写入 `_meta.json`
- 录制结束回调：自动启动转录 → 翻译串行 pipeline
- `TranslationClient` 接口 + `OpenAgenticTranslationClient`（Response API / SSE）
- `TranslationWorker`：读 transcript.json → 调 LLM → 写 translation.json
- translation.json schema（与 transcript segment 对齐）
- UI：长按录音目录手动触发翻译 + 目标语言选择 + 进度展示 + 双语对照查看
- CLI：`radio translate start --session <sid> --target_lang zh`

不做（v41）：

- 不做边录边转（录制中不触发任何处理）
- 不做语言学习 UI（v42）
- 不做术语表/摘要增强

## 核心流程

```
录制开始
  └→ 用户设定：targetLanguage = "zh"（写入 _meta.json）
录制结束
  └→ 自动触发 Pipeline：
       for each chunk:
         1. ASR 转录 → chunk_NNN.transcript.json
         2. 如果有 targetLanguage：LLM 翻译 → chunk_NNN.translation.json
       pipeline 完成 → 更新 _meta.json state
```

就是一个简单的 for 循环，没有事件总线，没有 SharedFlow，没有动态感知。
录完了，chunks 列表是确定的，挨个处理就完事。

## _meta.json 扩展

```json
{
  "schema": "kotlin-agent-app/radio-recording-meta@v1",
  "sessionId": "rec_20260220_142137_f8jxmg",
  "state": "completed",
  "stationId": "nhk_r1",
  "totalChunks": 6,
  "pipeline": {
    "targetLanguage": "zh",
    "transcriptState": "completed",
    "translationState": "running",
    "transcribedChunks": 6,
    "translatedChunks": 3,
    "failedChunks": 0,
    "lastError": null
  }
}
```

pipeline 字段：
- `targetLanguage`：用户选的目标语言，`null` 表示只转录不翻译
- `transcriptState` / `translationState`：`pending` → `running` → `completed` / `failed`
- 进度字段都是简单的计数器

不再有独立的 translation task 目录和 `_task.json`。pipeline 状态直接挂在 `_meta.json` 上，因为一个 session 就一条 pipeline。

## 目录结构（简化）

```
radio_recordings/
  rec_20260220_142137_f8jxmg/
    _meta.json                         # 含 pipeline 状态
    chunk_001.ogg
    chunk_002.ogg
    transcripts/
      chunk_001.transcript.json
      chunk_002.transcript.json
    translations/
      chunk_001.translation.json       # 翻译结果
      chunk_002.translation.json
```

不再有 `tx_xxx/` `tl_xxx/` 这些 task 子目录。转录结果平铺在 `transcripts/` 下，翻译结果平铺在 `translations/` 下。一个 session 一个语言对，简单直接。

如果用户想换一个目标语言重新翻译？删掉 `translations/` 目录，改 `_meta.json` 的 `targetLanguage`，重新跑就行。不需要多 task 并存的复杂性。

## Translation Chunk Schema

不变，和之前一样：

```json
{
  "schema": "kotlin-agent-app/radio-translation-chunk@v1",
  "sessionId": "rec_20260220_142137_f8jxmg",
  "chunkIndex": 1,
  "sourceLanguage": "ja",
  "targetLanguage": "zh",
  "segments": [
    {
      "id": 0,
      "startMs": 0,
      "endMs": 11920,
      "sourceText": "七款馬年生肖テーマの陶磁器が初お目見え...",
      "translatedText": "七款马年生肖主题陶瓷首次亮相...",
      "emotion": "neutral"
    }
  ]
}
```

## 翻译抽象层

```kotlin
interface TranslationClient {
    suspend fun translateBatch(
        segments: List<TranscriptSegment>,
        context: List<TranslatedSegment>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<TranslatedSegment>
}

data class TranslatedSegment(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val sourceText: String,
    val translatedText: String,
    val emotion: String?,
)
```

## OpenAgenticTranslationClient

复用 openAgentic SDK 通道，走 Response API（SSE 流式接口），复用已有的流式解析能力。独立 agent 实例，不与 Chat 抢并发。

## Pipeline 实现

```kotlin
class RecordingPipeline(
    private val asrClient: AliyunQwenAsrClient,
    private val translationClient: TranslationClient,
    private val store: RecordingStore,
) {
    suspend fun run(sessionId: String) {
        val meta = store.loadMeta(sessionId)
        val chunks = store.listChunks(sessionId)

        // Phase 1: 转录
        store.updatePipelineState(sessionId, transcriptState = "running")
        for (chunk in chunks) {
            if (store.hasTranscript(sessionId, chunk.index)) continue  // 断点续跑
            val transcript = asrClient.transcribe(chunk.file)
            store.saveTranscript(sessionId, chunk.index, transcript)
            store.incrementTranscribedChunks(sessionId)
        }
        store.updatePipelineState(sessionId, transcriptState = "completed")

        // Phase 2: 翻译（如果设了 targetLanguage）
        val targetLang = meta.pipeline?.targetLanguage ?: return
        store.updatePipelineState(sessionId, translationState = "running")
        var context = emptyList<TranslatedSegment>()
        for (chunk in chunks) {
            if (store.hasTranslation(sessionId, chunk.index)) continue
            val transcript = store.loadTranscript(sessionId, chunk.index)
            val translated = translateChunk(transcript.segments, context, meta.detectedLanguage, targetLang)
            store.saveTranslation(sessionId, chunk.index, translated)
            store.incrementTranslatedChunks(sessionId)
            context = translated.takeLast(3)  // 下一个 chunk 的上下文
        }
        store.updatePipelineState(sessionId, translationState = "completed")
    }

    private suspend fun translateChunk(
        segments: List<TranscriptSegment>,
        context: List<TranslatedSegment>,
        sourceLang: String,
        targetLang: String,
    ): List<TranslatedSegment> {
        // 按 4000 字符动态分批
        val batches = splitIntoBatches(segments, maxChars = 4000)
        val results = mutableListOf<TranslatedSegment>()
        var batchContext = context
        for (batch in batches) {
            val translated = translationClient.translateBatch(batch, batchContext, sourceLang, targetLang)
            results.addAll(translated)
            batchContext = translated.takeLast(3)
        }
        return results
    }
}
```

这就是整个核心逻辑。一个 class，两个 phase，一个 for 循环。支持断点续跑（跳过已有的 transcript/translation 文件）。

## 批处理策略

- 每批上限：约 4000 个源语言字符
- context 窗口：前一批最后 3 句的原文+译文
- 单批失败最多重试 3 次，超过则标记 failed，继续下一批
- 跨 chunk 也传 context（上一个 chunk 最后 3 句），保持连贯性

## Prompt 设计

### System Prompt

```
你是一位专业的广播节目翻译员。将以下广播转录文本从{sourceLanguage}翻译为{targetLanguage}。

要求：
1. 保持广播节目的语气和风格
2. 人名、地名保留原文读音的对应翻译
3. 不要合并或拆分 segment
4. 口语化表达保持口语化
5. 输出 JSON 数组：[{"id": 0, "translatedText": "..."}, ...]
6. id 必须与输入严格一致

上下文（前几句翻译，仅供参考）：
{contextJson}
```

### User Prompt

```json
[{"id": 0, "text": "..."}, {"id": 1, "text": "..."}]
```

### Expected Response

```json
[{"id": 0, "translatedText": "..."}, {"id": 1, "translatedText": "..."}]
```

## 目标语言列表

| 代码 | 语言 |
|------|------|
| zh | 中文 |
| ja | 日语 |
| ko | 韩语 |
| en | 英语 |
| fr | 法语 |
| de | 德语 |
| es | 西班牙语 |
| ru | 俄语 |
| it | 意大利语 |
| ar | 阿拉伯语 |
| pt | 葡萄牙语 |

## UI 交互设计

### 录制设置（开始录制前）

```
┌─────────────────────────────────────┐
│  录制设置                            │
│  ─────────────────────────────────── │
│  电台：NHK Radio 1                   │
│  ─────────────────────────────────── │
│  ☑ 录完自动转录+翻译                 │
│  目标语言：[中文 ▾]                   │
│  ─────────────────────────────────── │
│  [开始录制]                           │
└─────────────────────────────────────┘
```

勾选后，录制结束时自动触发 pipeline。不勾选则只录制，后续可手动触发。

### 长按菜单（手动触发）

```
┌─────────────────────────┐
│  rec_20260220_142137     │
│  ─────────────────────── │
│  📝 转录                 │  ← v40 已有
│  🌐 转录+翻译            │  ← v41 新增（一键触发完整 pipeline）
│  ❌ 取消                  │
└─────────────────────────┘
```

选"转录+翻译"后弹出语言选择器，选完直接跑 pipeline。
选"转录"则只跑 Phase 1（v40 行为不变）。

### 语言选择器

```
┌─────────────────────────┐
│  选择目标语言             │
│  ─────────────────────── │
│  🇨🇳 中文                │
│  🇯🇵 日语                │
│  🇰🇷 韩语                │
│  🇬🇧 英语                │
│  🇫🇷 法语                │
│  🇩🇪 德语                │
│  🇪🇸 西班牙语            │
│  🇷🇺 俄语                │
│  🇮🇹 意大利语            │
│  🇸🇦 阿拉伯语            │
│  🇧🇷 葡萄牙语            │
└─────────────────────────┘
```

### 进度展示

```
┌─────────────────────────────────────┐
│  rec_20260220_142137                 │
│  ─────────────────────────────────── │
│  📝 转录  ████████████ 6/6 ✅       │
│  🌐 翻译  ██████░░░░░░ 3/6          │
└─────────────────────────────────────┘
```

### 翻译结果（双语对照）

```
┌─────────────────────────────────────┐
│ [00:00 - 00:11]                      │
│ 七款馬年生肖テーマの陶磁器が初...    │
│ 七款马年生肖主题陶瓷首次亮相...      │
│                                      │
│ [00:12 - 00:19]                      │
│ 中国陶磁芸術大師...                  │
│ 中国陶瓷艺术大师...                  │
└─────────────────────────────────────┘
```

## 错误码集合

| error_code | 含义 |
|------------|------|
| `InvalidArgs` | 参数缺失或非法（如 source 与 target 相同） |
| `SessionNotFound` | session 不存在 |
| `SessionStillRecording` | 录制中，不允许触发 pipeline |
| `SessionNoChunks` | 无 chunk 文件 |
| `PipelineAlreadyRunning` | pipeline 正在运行中 |
| `TranscriptNotReady` | 某 chunk 转录未完成（翻译阶段遇到） |
| `LlmNetworkError` | LLM API 网络不可达 |
| `LlmRemoteError` | LLM API 返回非 2xx |
| `LlmParseError` | LLM 返回无法解析 |
| `LlmQuotaExceeded` | LLM 配额耗尽 |

## Files（规划）

- Pipeline 核心：
  - `app/.../radio_transcript/RecordingPipeline.kt`（转录+翻译串行 pipeline）
- 翻译层：
  - `app/.../translation/TranslationClient.kt`（接口）
  - `app/.../translation/TranslationModels.kt`（TranslatedSegment）
  - `app/.../translation/OpenAgenticTranslationClient.kt`
  - `app/.../translation/TranslationPromptBuilder.kt`
- Schema：
  - `app/.../radio_transcript/TranslationChunkV1.kt`
- _meta.json 扩展：
  - `RecordingMetaV1.kt` 增加 `pipeline` 字段
- CLI：
  - `RadioCommand.kt` 扩展 `radio translate start --session <sid> --target_lang zh`
- UI：
  - 录制设置页：仅录制 / 仅转录 / 转录+翻译（含目标语言选择）
  - 长按菜单增加"转录+翻译"
  - `TranslationLanguagePickerDialog.kt`
  - 录制列表：第一行显示「名称 + 开始时间」，第二行显示「转录/翻译状态」
  - 进度展示 + 双语对照 UI
- Tests：
  - `RecordingPipelineTest.kt`（完整 pipeline 流程、断点续跑、失败处理）
  - `OpenAgenticTranslationClientTest.kt`（mock SSE）
  - `TranslationPromptBuilderTest.kt`
  - `TranslationBatchSplitTest.kt`

## Steps（Strict / TDD）

1. Analysis：确认 openAgentic Response API 的调用方式，确定 prompt 模板。
2. TDD Red：`TranslationClient` + mock 测试。
3. TDD Green：实现 `OpenAgenticTranslationClient`。
4. TDD Red：`TranslationPromptBuilder` 测试。
5. TDD Green：实现 prompt 拼装。
6. TDD Red：`RecordingPipeline` 完整流程测试（mock ASR + mock LLM）。
7. TDD Green：实现 pipeline。
8. TDD Red：断点续跑测试（部分 chunk 已有 transcript/translation）。
9. TDD Green：实现跳过逻辑。
10. TDD Red：动态分批测试。
11. TDD Green：实现分批。
12. `RecordingMetaV1` 扩展 pipeline 字段。
13. CLI 实现。
14. UI 实现。
15. Verify。

## Risks

- LLM 返回 JSON 格式不稳定：需要 robust parsing + 重试。测试用 mock。
- 翻译一致性：v41 先保证可用，术语表后续增强。
- 换语言重新翻译：需要清空 `translations/` 目录，UI 上要有确认提示。

## Implementation Notes（现状说明，2026-02-20）

为避免未来读者误解，这里记录当前实现与本文“理想规划”的差异/细节：

- 调度方式：离线 pipeline 通过 WorkManager 以 unique work 形式排队执行（`RecordingPipelineManager`），不是直接在 UI/Service 里同步 for-loop 跑完。
- 仅录制：`radio record start --record_only` 会写入 `_meta.json` 的 `pipeline=null`；录制结束时 Service 不会自动 enqueue pipeline（也就不会自动转录/翻译）。
- 断点续跑进度：如果某些 `translations/chunk_XXX.translation.json` 已存在，运行中会跳过，但中途不一定每次都把“跳过计数”写回 `_meta.json`；最终完成时会写入 `completed` 的总数。
- 错误码映射：`sourceLanguage == targetLanguage` 这类参数错误在某些路径下可能最终表现为 `LlmNetworkError`（而非 `InvalidArgs`），需以代码为准。
- 换语言重翻译：当前不会自动清理旧的 `translations/` 目录；如果要更换目标语言并确保产物一致，需手动删除 `translations/` 后重跑 pipeline。
