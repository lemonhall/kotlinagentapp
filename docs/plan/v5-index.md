# v5 Index：Context Overflow Control（预算化 + Artifacts + Sub-agent）

## Vision（引用）

- PRD：`docs/prd/PRD-0003-context-overflow-control.md`
- 本轮聚焦：先止血——**WebView 工具链 sub-agent 化**，并补一个 SDK 级统一截断兜底，避免“写烂 tool 直接把系统炸了”。

## Milestones

### M1：WebView 工具链 sub-agent 化（Task/webview）

- PRD Trace：REQ-0003-020
- DoD（硬口径）：
  - 主会话 `allowedTools` 不包含任何 `web_*` 工具名
  - 主会话 `allowedTools` 包含 `Task`
  - App 侧配置 `taskRunner`，支持 `Task(agent="webview", prompt=...)`
  - `Task(webview, ...)` 返回包含：`sub_session_id`、有界 `summary`
  - `.\gradlew.bat :app:testDebugUnitTest` exit code=0
- Plan：`docs/plan/v5-context-overflow-control.md`

### M2：SDK 工具输出统一截断兜底（responses input 构建阶段）

- PRD Trace：REQ-0003-001
- DoD（硬口径）：
  - 超长 tool output 不会导致 provider input 构建阶段明显卡死/爆内存
  - `external/openagentic-sdk-kotlin` 增加单测覆盖“超长 tool output”截断仍保持 JSON 可解析
  - `pwsh -Command "Set-Location external/openagentic-sdk-kotlin ; .\\gradlew.bat test"` exit code=0
- Plan：`docs/plan/v5-context-overflow-control.md`

## Plan Index

- `docs/plan/v5-context-overflow-control.md`

## Traceability Matrix（v5）

| Req ID | v5 Plan | Tests / Commands | Evidence |
|---|---|---|---|
| REQ-0003-020 | v5-context-overflow-control | `:app:testDebugUnitTest` | local |
| REQ-0003-001 | v5-context-overflow-control | `external/openagentic-sdk-kotlin: test` | local |

## ECN Index

- `docs/ecn/ECN-0002-web-snapshot-artifacts-deferred.md`
