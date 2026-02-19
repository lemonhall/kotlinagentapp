# v40 Plan：Radio 离线转录（TranscriptTaskManager + Whisper Worker + `radio transcript`）

## Goal

对 v39 录制产物提供“离线转录”的后台慢任务闭环：

- 为录制会话创建 `transcripts/`，任务/进度以文件形式可见
- Whisper API 把每个 `chunk_NNN.m4a` 转为 `chunk_NNN.transcript.json`
- CLI：`radio transcript start|status|list|cancel`

## PRD Trace

- PRD-0034：REQ-0034-050 / REQ-0034-051 / REQ-0034-052 / REQ-0034-053

## Scope

做（v40）：

- `TranscriptTaskManager`（串行处理 chunk，避免 API 并发爆炸）
- WorkManager 集成：可恢复、可取消
- `_task.json` + `_tasks.index.json` + `_STATUS.md` 落盘与持续更新
- Whisper API（云端）：
  - 上传 `.m4a` chunk
  - 解析 `verbose_json`（带 timestamps）为 segments
  - 错误码归一：NetworkError/RemoteError/InvalidArgs/…
- CLI：`radio transcript ...`（受控输入：sessionId 或录制目录）

不做（v40）：

- 不做翻译（v41）
- 不做“实时”转录（v44）

## Acceptance（硬 DoD）

- 任务可见性：启动任务后必须产生 `{task_id}/_task.json`，并在其中体现 `totalChunks/transcribedChunks/failedChunks` 进度。  
- 单 chunk 原子性：每完成一个 chunk 的转录，必须先写 `chunk_NNN.transcript.json` 再更新 `_task.json`（避免“进度已走但文件缺失”）。  
- 可恢复：WorkManager 中断后再次启动应能继续未完成 chunks（不重复生成已存在的 transcript）。  
- CLI help：`radio transcript --help` / `radio help transcript` 为 0。  

验证命令：

- `.\gradlew.bat :app:testDebugUnitTest`

## Files（规划）

- 转录模块（建议新包）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_transcript/*`
    - `TranscriptTaskManager.kt`
    - `TranscriptTaskStore.kt`（读写 `_task.json/_tasks.index.json`）
    - `WhisperAsrWorker.kt`（或 `CloudAsrClient`）
    - `TranscriptChunkV1.kt` / `TranscriptTaskV1.kt`
- CLI：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/radio/RadioCommand.kt`（新增 `transcript` 子命令）
- Tests：
  - `app/src/test/java/...`（MockWebServer + store 读写测试 + argv 门禁）

## Steps（Strict / TDD）

1) Analysis：固化 `_task.json` 最小字段与错误码，明确“串行 + 每 chunk 立即落盘”的一致性规则。  
2) TDD Red：CLI help + argv 校验 + sessionId/目录解析门禁测试。  
3) TDD Red：`TranscriptTaskStore` 单测（创建任务、更新进度、生成 `_tasks.index.json`）。  
4) TDD Red：Whisper client 在 MockWebServer 下解析 verbose_json → segments 的单测（含错误映射）。  
5) TDD Green：实现 `TranscriptTaskManager` 串行队列 + WorkManager glue，跑到绿。  
6) Verify：UT 全绿；手动用一个短录音 chunk 做真机转录验证（可选但推荐）。  

## Risks

- Whisper API 费用/网络：测试必须用 mock；真机仅最小验证。  
- JSON 体积：每 chunk transcript 可能较大，UI/CLI 默认输出必须摘要化（大输出走 artifacts/文件浏览）。  

