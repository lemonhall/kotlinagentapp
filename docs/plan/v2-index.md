# v2 Index：SDK 引入 + Chat 真对话（最短闭环）

## Vision（引用）

- PRD：`docs/prd/PRD-0001-kotlin-agent-app.md`
- 本轮聚焦：把 `openagentic-sdk-kotlin` 以 composite build 引入工程，并让 Chat 从 echo/mock 升级为“读取设置 → 调用 SDK → 展示回复”的最短闭环。

## Milestones

### M1：SDK 以 composite build 引入

- PRD Trace：REQ-0001-040
- DoD（硬口径）：
  - `.\gradlew.bat :app:assembleDebug` exit code=0
  - `.\gradlew.bat :app:testDebugUnitTest` exit code=0
  - app 模块中可以通过 `implementation(...)` 引用 SDK 的核心 API（编译期可见）
- Plan：`docs/plan/v2-sdk-composite-build.md`

### M2：Chat 真对话最短闭环（流式 SSE / OpenAI Responses 风格）

- PRD Trace：REQ-0001-001 / REQ-0001-010 / REQ-0001-040
- DoD（硬口径）：
  - 在未配置 `base_url/api_key/model` 时，Chat 发送会得到明确错误提示（不崩溃）
  - 在配置正确时，发送一条消息可收到 assistant 回复并展示到列表
  - `.\gradlew.bat :app:assembleDebug` ✅
  - `.\gradlew.bat :app:testDebugUnitTest` ✅（含核心逻辑单测）
- Plan：`docs/plan/v2-chat-real-request.md`（已由 ECN-0001 调整为 streaming）

## Plan Index

- `docs/plan/v2-sdk-composite-build.md`
- `docs/plan/v2-chat-real-request.md`

## Traceability Matrix（v2）

| Req ID | v2 Plan | Tests / Commands | Evidence |
|---|---|---|---|
| REQ-0001-040 | v2-sdk-composite-build | `:app:assembleDebug` | pending |
| REQ-0001-010 | v2-chat-real-request | `:app:testDebugUnitTest` | pending |
| REQ-0001-001 | v2-chat-real-request | `:app:testDebugUnitTest` | pending |
| REQ-0001-004 | v2-chat-real-request | `:app:testDebugUnitTest` | pending |

## ECN Index

- `docs/ecn/ECN-0001-streaming-only-provider.md`

## Differences（愿景 vs 现实）

- 流式输出与 tool calls 真实链路暂不进入 v2（后续 v3 迭代）。

## Evidence（本地）

- 2026-02-15：
  - `.\gradlew.bat :app:testDebugUnitTest` ✅
  - `.\gradlew.bat :app:assembleDebug` ✅
