# v5 Plan：Context Overflow Control（WebView sub-agent + web_snapshot artifacts + SDK 截断兜底）

## Goal

在不重构整套 Agent 架构的前提下，把“WebView 高噪音链路导致主会话炸上下文”的问题先按工程手段止血：

1) WebView 交互改走 `Task(agent="webview")` 子会话  
2) SDK 侧对 tool outputs 做统一截断兜底，避免输入构建阶段爆炸  

## PRD Trace

- REQ-0003-020
- REQ-0003-001

## Scope

### In scope

- App：拆分主会话/子会话 system prompt；主会话禁用 `web_*` 并启用 `Task`
- App：实现 `taskRunner`（至少支持 `agent=webview`）
- SDK：responses input 构建时对 tool outputs 做统一截断（优先截断字符串字段，保持 JSON 可解析）

### Out of scope

- 研究子会话（WebFetch/WebSearch）全面改造（留到 v6）
- 多 sub-agent 并发调度与优先级（留到 v6+）

## Acceptance（硬口径）

1. 主会话无法直接调用 `web_*`（schema 不暴露 / tool not allowed）  
2. 主会话可调用 `Task(webview, ...)` 并得到有界 JSON 结果（≤ 4000 chars 级别）  
3. SDK 单测覆盖“超长 tool output”截断行为，且截断后 JSON 仍可 parse  

## Files（预期改动）

- `app/src/main/java/com/lsl/kotlin_agent_app/agent/OpenAgenticSdkChatAgent.kt`
- `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/runtime/OpenAgenticSdk.kt`
- `external/openagentic-sdk-kotlin/src/test/kotlin/me/lemonhall/openagentic/sdk/runtime/*`

## Steps（严格顺序）

1) **TDD Red（SDK）**：新增单测，构造超长 tool output，断言 provider 接收到的 `function_call_output.output` 可解析且已被截断（包含 marker）。  
   - 命令：`pwsh -Command "Set-Location external/openagentic-sdk-kotlin ; .\\gradlew.bat test --tests \\\"me.lemonhall.openagentic.sdk.runtime.*\\\""`

2) **TDD Green（SDK）**：实现 tool outputs 统一截断兜底（头+尾 + marker；优先截断字符串字段）。  

3) **App 实现（sub-agent）**：主会话 prompt 瘦身；主会话禁用 `web_*`、启用 `Task`；实现 `taskRunner` 运行 `webview` 子会话。  

4) **验证（App）**：  
   - `.\gradlew.bat :app:assembleDebug`  
   - `.\gradlew.bat :app:testDebugUnitTest`

5) **验证（SDK）**：  
   - `pwsh -Command "Set-Location external/openagentic-sdk-kotlin ; .\\gradlew.bat test"`

## Risks

- 子会话默认“一次 Task 一个 session”会降低跨多轮网页操作的连续性；但能最大化减小膨胀风险（后续可在 v6 讨论“webview 持久子会话”）。
- （可选）未来若重新启用 `web_snapshot` artifacts 化，需要通过 ECN 明确字段/行为变化（见 `docs/ecn/ECN-0002-web-snapshot-artifacts-deferred.md`）。
