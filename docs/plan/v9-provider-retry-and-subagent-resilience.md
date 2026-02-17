# v9 Plan：Provider Retry + Sub-agent 失败交付（OpenCode 对齐）

PRD：`docs/prd/PRD-0006-provider-retry-and-subagent-resilience.md`

## Goal

在不引入真实网络依赖的前提下，把“瞬断/断流导致空交付与重试风暴”收敛为可控行为：

- SDK：对可恢复 provider 失败执行自动重试（指数退避 + Retry-After）。
- App：deep-research 永不空交付；失败显式失败（`ok=false`）并产出可读失败报告。
- 主会话：deep-research 失败不自动连锁重试。

## PRD Trace

- REQ-0006-001 / REQ-0006-002 / REQ-0006-010 / REQ-0006-020 / REQ-0006-021 / REQ-0006-030

## Scope

做：
- 扩展 SDK 的 providerRetry：从“仅 429”升级为通用重试（timeout/5xx/断流归一）。
- OpenAI Responses provider：断流类异常（EOF/ECONNRESET）归一为 `ProviderTimeoutException`。
- App deep-research：失败返回 `ok=false`；报告文件包含失败说明与证据指针；主会话失败时不自动再开 Task。

不做：
- 不新增远端任务队列/云存储。
- 不做 UI 一键续跑（本轮只在报告与返回 JSON 中提供续跑指引与指针）。

## Acceptance（硬口径）

### SDK

1. 单测：模拟 `ProviderTimeoutException` 首次失败、第二次成功 → 最终成功且不产生 `runtime.error`。
2. 单测：模拟 `ProviderHttpException(503)` 首次失败、第二次成功 → 最终成功。
3. 单测：默认配置对齐 Codex：`ProviderRetryOptions.maxRetries=6`（总尝试最多 7 次）。
4. 单测：`maxRetries=0` 时保持原行为（不重试）。

验证命令（SDK 仓库内）：

```powershell
cd external/openagentic-sdk-kotlin ; .\gradlew.bat test --tests "me.lemonhall.openagentic.sdk.runtime.*Retry*"
```

### App

1. 单测：deep-research `Task` 失败返回 `ok=false` 时，仍能捕获 `report_path` 并产生可读报告链接（失败报告不为 `(empty)`）。
2. system prompt：明确禁止在 `Task(deep-research)` 失败时自动重试。

验证命令（App 仓库根目录）：

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

## Files（预期改动范围）

- SDK：
  - `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/runtime/OpenAgenticSdk.kt`
  - `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/providers/OpenAIResponsesHttpProvider.kt`
  - `external/openagentic-sdk-kotlin/src/test/kotlin/me/lemonhall/openagentic/sdk/runtime/*Retry*.kt`
- App：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/OpenAgenticSdkChatAgent.kt`
  - `app/src/test/java/com/lsl/kotlin_agent_app/*`（新增/修改与失败交付相关的纯逻辑单测）

## Steps（塔山循环：Red → Green → Refactor）

1. **TDD Red（SDK）**：新增 runtime 单测覆盖 timeout/5xx 可重试。
2. **TDD Green（SDK）**：实现通用 provider retry + 断流归一；跑测试到绿。
3. **Refactor（SDK）**：整理重试判定逻辑，避免误重试不可恢复错误。
4. **TDD Red（App）**：新增纯逻辑单测覆盖“失败不再写 `(empty)` / `ok=false`”判定。
5. **TDD Green（App）**：实现 deep-research 失败交付与主会话防重试风暴；跑 app 单测到绿。
6. **Review（证据）**：补齐 `docs/plan/v9-index.md` 的 DoD 证据（命令 + exit code）。

## Risks

- 重试可能导致重复输出：本轮只对 SDK “一次 model call”级别重试，不重放 tool 执行，避免重复副作用。
- 误把不可恢复错误重试：通过 `ProviderHttpException` 状态码白名单与测试约束避免。
