# PRD-0006：Provider Retry + Sub-agent Resilience（对齐 OpenCode）

日期：2026-02-17  
关联 PRD：`docs/prd/PRD-0003-context-overflow-control.md`（子会话隔离与 artifacts）  
参考实现（源头口径）：OpenCode `SessionRetry / SessionProcessor / Task(task_id)`（E:\development\opencode）  
变更记录：`docs/ecn/ECN-0003-provider-retry-defaults.md`（默认重试次数对齐 Codex）  

## Vision

把“网络断流/EOF/超时导致子会话失败、空交付、主会话反复拉起 Task”的随机事故，变成**可恢复、可追溯、可验收**的工程行为：

1. SDK 对可重试的 provider 失败执行自动重试（指数退避 + Retry-After），默认不把瞬断升级为业务失败。
2. deep-research 子会话**永不空交付**：成功交付完整报告；失败也交付“失败报告 + 已收集证据指针 + 续跑指引”。
3. 主会话在 deep-research 失败时不再“自我加压式重试风暴”（避免 100+ turn / 多 session 连锁）。

## Background

已观测到的失败模式（2026-02-17）：

- deep-research 子会话在 provider 流式阶段出现 `EOFException` 后立刻结束，并产生空 `final_text`；
- App 侧仍以 `ok=true` 回传 `Task(deep-research)` 结果，且写入 `(empty)` 报告文件；
- 主会话看到 `report_summary=(empty)` 后倾向再次启动 deep-research，导致多 session 连锁与用户体感“不会结束”。

根因类别：

- **瞬断/断流**：SSE 流被中间链路/系统回收/对端提前关闭导致 EOF/connection reset；
- **可恢复错误**：限流（429）、临时超时、5xx/网关类错误；
- **不可恢复错误**：权限/配额/参数错误、确定性协议不兼容。

## Non-Goals（本 PRD 迭代范围外）

- 不追求对所有 provider 的“完美错误分类”，优先覆盖 OpenAI Responses HTTP provider + 常见瞬断场景。
- 不引入远端持久化或云端任务队列；所有状态仍在 `.agents` 工作区与 session jsonl 内完成。
- 不做复杂的多子任务编排（并发 worker、DAG 依赖）；只治理“单子会话可靠性 + 失败交付”。

## Constraints & Principles

- **证据优先**：任何“成功/失败/可重试/不可重试”必须能从 events.jsonl 与产物文件追溯。
- **不误报成功**：错误态不得返回 `ok=true`；错误必须可见、可诊断、可续跑。
- **失败也可交付**：失败报告必须包含最小可用信息（错误原因 + 已收集来源/搜索词 + 续跑建议）。
- **预算不放松**：重试不得无限；必须有次数上限 + 退避，避免重试风暴。

## Requirements（Req IDs）

### A. SDK：通用 Provider Retry（对齐 OpenCode 的 SessionRetry）

- REQ-0006-001：`OpenAgenticOptions.providerRetry` 从“仅 429”扩展为通用重试策略，覆盖：
  - `ProviderRateLimitException`（429）：优先遵循 `retryAfterMs`（可配置是否启用）。
  - `ProviderTimeoutException`：按指数退避重试。
  - `ProviderHttpException`：对 `408/425/429/500/502/503/504` 等临时性错误按退避重试；其余不重试。
  - `EOFException` / `SocketException(ECONNRESET)` 等断流类：在 provider 层归一为 `ProviderTimeoutException`（或等价的 `ProviderException`）以进入统一重试逻辑。
  - Acceptance：
    - 单测：模拟 provider 第一次 `ProviderTimeoutException`，第二次成功，最终无 `runtime.error` 且输出成功。
    - 单测：模拟 provider 第一次 5xx，第二次成功，最终成功。
    - 单测：达到 `maxRetries` 后应以 `runtime.error` + `Result(stop_reason="error")` 结束。

- REQ-0006-002：重试必须可控，默认不启用（保持兼容），由 `providerRetry.maxRetries > 0` 显式开启。
  - Acceptance：
    - 默认配置下行为不变（已有测试全部通过）。
  - [已由 ECN-0003 变更]：为对齐 Codex/OpenCode 的默认体验，重试默认启用：
    - `ProviderRetryOptions.maxRetries` 默认值为 **6**（失败后最多重试 6 次；总尝试最多 7 次）。
    - 退避策略为指数退避并设置上限（实现细节见 v9 plan 与测试）。

### B. SDK：断流错误归一（对齐 OpenCode 的“retryable network error”）

- REQ-0006-010：`OpenAIResponsesHttpProvider` 在流式读取中出现 EOF/connection reset 时，不直接抛出裸 `EOFException`，而应包装为 `ProviderTimeoutException`（或可识别的 `ProviderException`）。
  - Acceptance：
    - 单测/可控模拟：断流类异常会被分类为 `provider` phase 的 `runtime.error`（error_type 为 ProviderTimeoutException 或同类），且进入重试逻辑。

### C. App：deep-research 子会话“失败也交付”（对齐 OpenCode：失败可重试/可续跑）

- REQ-0006-020：`Task(deep-research)` 失败时必须返回 `ok=false`，并包含：
  - `error_type` / `error_message` / `stop_reason`
  - `sub_session_id` / `events_path` / `report_path`
  - Acceptance：
    - 人工复现 `EOFException` 时，主会话不再把失败当成功；UI 能看到“失败 + 报告链接”。

- REQ-0006-021：deep-research 报告文件必须满足：
  - 成功：包含结构化章节（执行摘要/关键发现/详细分析/参考来源）。
  - 失败：包含错误信息、已收集来源指针（至少 URL 列表或 tool-output 路径）、以及“如何续跑/重试”的明确指引。
  - Acceptance：
    - 失败时报告文件长度 > 0 且包含“失败原因/续跑建议”关键字；不允许 `(empty)` 作为唯一内容。

### D. App：主会话避免“失败重试风暴”

- REQ-0006-030：主会话 system prompt 明确规定：当 `Task(deep-research)` 返回 `ok=false` 或 `stop_reason="error"` 时，**不得**自动再次启动 deep-research；应向用户报告失败并给出重试建议。
  - Acceptance：
    - 复现失败时不再出现短时间内多个 deep-research session 连锁创建。

## Milestones（建议）

- v9：
  - SDK：通用 provider retry（REQ-0006-001/002）
  - SDK：OpenAI Responses 断流归一（REQ-0006-010）
  - App：deep-research 失败交付 + 主会话防重试风暴（REQ-0006-020/021/030）

## Open Questions（待确认）

1. deep-research 的“续跑”形态：是否需要显式暴露 `task_id`（复用同一 sub_session）？本轮先做同 session 重试（SDK 内）+ 失败报告含可重试指引，后续再做 UI 一键续跑。
