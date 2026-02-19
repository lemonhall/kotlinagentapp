# v41 Plan：Radio 离线翻译（TranslationWorker + `translation.json` + 边录边转）

## Goal

在 v40 转录基础上，交付离线翻译闭环：

- 将 `chunk_NNN.transcript.json` 翻译为 `chunk_NNN.translation.json`（时间戳对齐）
- 支持“录制完成后自动转录+翻译”与“边录边转”（可逐步启用）

## PRD Trace

- PRD-0034：REQ-0034-080 / REQ-0034-081

## Scope

做（v41）：

- `TranslationWorker`（LLM 翻译，按 segment 批处理）
- translation 落盘 schema 固化（与 transcript segment 对齐：同 id/start/end）
- 录制 meta 支持 `transcriptRequest`（自动转录/翻译参数）
- “边录边转”模式（动态增长 chunks；任务进度可解释）

不做（v41）：

- 不做语言学习 UI（v42）
- 不做实时翻译 UI（v44+）

## Acceptance（硬 DoD）

- 对齐：`translation.json` 中每个 segment 必须带 `sourceText/translatedText`，且 `startSec/endSec` 与来源 transcript 一致。  
- 进度可解释：`_task.json` 中 `translatedChunks` 必须单调递增，失败 chunk 必须计入 `failedChunks` 并可继续后续 chunk。  
- 自动触发：录制会话 `_meta.json.transcriptRequest.autoStart=true` 时，录制完成会自动创建任务并进入队列。  

验证命令：

- `.\gradlew.bat :app:testDebugUnitTest`

## Files（规划）

- 翻译模块（可放在 v40 同包或单独包）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_transcript/TranslationWorker.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_transcript/TranslationChunkV1.kt`
- 录制 meta 扩展：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_recordings/RecordingMetaV1.kt`
- CLI（若 v41 需要暴露参数调试，可选）：
  - 复用 `radio transcript start ... --target_lang ...` 的参数

## Steps（Strict / TDD）

1) Analysis：确定翻译 prompt 的最小稳定口径（输入：segments[] + language pair；输出：逐条翻译），并定义错误码。  
2) TDD Red：translation schema 对齐的单测（输入 transcript，产出 translation，断言 timestamps/id 对齐）。  
3) TDD Red：LLM client 的 mock 测试（超时/限流/返回缺字段的鲁棒性）。  
4) TDD Green：实现 `TranslationWorker` + 与 `TranscriptTaskManager` 的串行调度；每 chunk 完成即落盘。  
5) TDD Red：自动触发（录制完成回调）测试：`autoStart=true` 会创建任务。  
6) Verify：UT 全绿；用 1 个短 chunk 做端到端（转录+翻译）手动验证（可选）。  

## Risks

- 翻译一致性（术语、人名）：v41 先保证“可用”，术语表/摘要属于增强（可在 v42/v43 后追加）。  

