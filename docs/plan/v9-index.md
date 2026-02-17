# v9 Index：Provider Retry + Sub-agent 失败交付（OpenCode 对齐）

## Vision（引用）

- PRD：`docs/prd/PRD-0006-provider-retry-and-subagent-resilience.md`
- 本轮聚焦：
  - SDK 层把“瞬断/断流/超时/5xx”纳入统一重试（指数退避 + Retry-After）
  - OpenAI Responses 流式断流（EOF/ECONNRESET）归一为可重试的 `ProviderException`
  - App 层 deep-research：失败不再空交付，且主会话不再触发重试风暴

## Milestones

### M1：SDK 通用 Provider Retry

- PRD Trace：REQ-0006-001，REQ-0006-002
- DoD（硬口径）：
  - `providerRetry.maxRetries>0` 时，遇到 `ProviderTimeoutException`/临时性 `ProviderHttpException(5xx/408/504...)` 会自动重试；
  - 默认配置对齐 Codex：`ProviderRetryOptions.maxRetries=6`（总尝试最多 7 次），可通过 options 覆盖为 0 禁用；
  - 达到 `maxRetries` 后才落 `runtime.error` + `Result(stop_reason="error")`；
  - `cd external/openagentic-sdk-kotlin ; .\\gradlew.bat test --tests "me.lemonhall.openagentic.sdk.runtime.*Retry*"` exit code=0
- Plan：`docs/plan/v9-provider-retry-and-subagent-resilience.md`

### M2：OpenAI Responses 断流归一

- PRD Trace：REQ-0006-010
- DoD（硬口径）：
  - SSE 读取过程中出现 `EOFException/connection reset` 时，会被包装为 `ProviderTimeoutException`（或同类 `ProviderException`）进入重试；
  - 相关单测通过（不引入真实网络）。
- Plan：`docs/plan/v9-provider-retry-and-subagent-resilience.md`

### M3：App deep-research 失败交付 + 防重试风暴

- PRD Trace：REQ-0006-020，REQ-0006-021，REQ-0006-030
- DoD（硬口径）：
  - `Task(deep-research)` 失败时返回 `ok=false`，并包含 `error_type/error_message/stop_reason/report_path/events_path/sub_session_id`；
  - 失败报告文件可读：包含“失败原因/续跑建议/已收集来源指针”，不允许只写 `(empty)`；
  - 主会话在 `Task` 失败时不自动再次启动 deep-research；
  - `.\gradlew.bat :app:testDebugUnitTest` exit code=0
- Plan：`docs/plan/v9-provider-retry-and-subagent-resilience.md`

## Plan Index

- `docs/plan/v9-provider-retry-and-subagent-resilience.md`

## ECN Index

- `docs/ecn/ECN-0003-provider-retry-defaults.md`：Provider Retry 默认开启（maxRetries=6）
