# v8 Index：Explore 子 agent + Tool Output 截断落盘（OpenCode 对齐）

## Vision（引用）

- PRD：`docs/prd/PRD-0005-explore-subagent-and-tool-truncation.md`
- 本轮聚焦：
  - SDK 层把 tool.result “大输出”从 `events.jsonl`/provider input 中**外置**到 `tool-output` artifacts
  - SDK 内置 `explore` 子 agent（Task）用于对 artifacts 做只读检索与总结
  - 对齐 OpenCode 的“自动提示下一步（Task/explore）”逻辑

## Milestones

### M1：SDK ToolResult 截断落盘（Artifacts）

- PRD Trace：REQ-0005-001，REQ-0005-002
- DoD（硬口径）：
  - 当 tool output 超过阈值时：
    - `events.jsonl` 中写入的 `tool.result.output` 为 wrapper（含 `artifact_path` + `preview`）
    - 完整输出落在 `<root>/.agents/tool-output/`（可被 `Read` 打开）
  - `.\gradlew.bat test --tests "me.lemonhall.openagentic.sdk.runtime.*"` exit code=0
- Plan：`docs/plan/v8-explore-subagent-and-tool-truncation.md`

### M2：SDK 内置 explore 子 agent + runner 组合

- PRD Trace：REQ-0005-010，REQ-0005-011
- DoD（硬口径）：
  - App 侧无需再手写 explore 的 system prompt；只需启用 SDK 内置 runner 即可运行 `Task(agent="explore")`
  - explore 子 agent 默认禁止 `Write/Edit/Task`（只读检索）
  - 单测覆盖：`Task(agent="explore")` 能跑通并返回可解析 JSON
- Plan：`docs/plan/v8-explore-subagent-and-tool-truncation.md`

### M3：Task 工具描述注入 agents 列表 + 截断自动提示

- PRD Trace：REQ-0005-020，REQ-0005-030
- DoD（硬口径）：
  - `Task` tool description 中可见 `explore` 等可用 agents（来自配置）
  - 截断 wrapper 的 `hint` 能自动提示 `Task(agent="explore")`（当 Task 可用时）
- Plan：`docs/plan/v8-explore-subagent-and-tool-truncation.md`

## Plan Index

- `docs/plan/v8-explore-subagent-and-tool-truncation.md`

## ECN Index

- （本轮暂无）

