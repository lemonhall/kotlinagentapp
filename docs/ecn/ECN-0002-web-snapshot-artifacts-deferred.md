# ECN-0002: web_snapshot artifacts 化延期（改回内联 snapshot_text）

## 基本信息

- **ECN 编号**：ECN-0002
- **关联 PRD**：PRD-0003
- **关联 Req ID**：REQ-0003-010
- **日期**：2026-02-17

## 变更原因

在 v5 中已将 WebView 自动化强制下沉到 `Task(agent="webview")` 子会话，主会话不再暴露 `web_*` 工具，从根上隔离了高噪音链路对主会话上下文的污染。

在此约束下，`web_snapshot` 再额外做“完整快照落盘 + preview + 指针”的 artifacts 化收益下降，反而增加实现与调试复杂度。

## 变更内容

### 原设计（PRD-0003）

`web_snapshot` 不返回完整 `snapshot_text`，改为落盘 artifact，并在 tool.result 中仅返回 preview + path 指针。

### 新设计（本 ECN）

- `web_snapshot` 恢复为在 tool.result 中返回内联 `snapshot_text`（仍保持既有 `renderMaxCharsTotal` 预算）。
- artifacts 化作为后续可选优化项，暂不作为 v5 的交付门槛。

## 影响范围

- 受影响的 Req ID：REQ-0003-010
- 受影响的 v5 计划：`docs/plan/v5-index.md`、`docs/plan/v5-context-overflow-control.md`
- 受影响的代码：`app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/web/WebTools.kt`

