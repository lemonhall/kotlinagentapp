# PRD-0003：Context Overflow Control（预算化 + Artifacts + Sub-agent）

日期：2026-02-17  
参考报告：`research/2026-02-17-context-overflow-playbook.md`

## Vision

把“上下文总是炸（context_length_exceeded）”从随机事故变成可工程化治理的问题：**任何高噪音链路（尤其 WebView / web_*）默认不污染主会话上下文**，主会话只保留“问题、决策、下一步、证据指针”，并在最坏情况下也能稳定退化而不爆栈。

## Background

- 当前仓库在集成 `external/openagentic-sdk-kotlin` 与 WebView 工具链（`app/.../agent/tools/web/*`）时，遇到多次上下文过载。
- 已具备部分防护（Preflight compaction、Tool output placeholder、BashTool 落盘等），但仍缺少“源头统一预算化”和“过程材料隔离（artifacts/sub-session）”。

## Non-Goals（本 PRD 迭代范围外）

- 不追求“一次性彻底根治所有 tool 输出膨胀”，先覆盖最常炸的链路与最划算的兜底点。
- 不做复杂的多 agent 调度系统（如多层 DAG、并发 worker 池）；先做可控的 `Task(agent=...)` 子会话。
- 不引入新的远程存储/云同步；artifacts 仅落在 App 内部工作区（`.agents`）或 SDK 工作区。

## Constraints & Principles

- **大输出永远不直接进主会话上下文**：能落文件就落文件；主会话只回传摘要 + 指针 + 少量片段。
- **截断策略必须“头+尾”**，并显式标记省略量（避免只留开头导致丢结论/堆栈）。
- **sub-agent（子会话）优先用于高噪音链路**：WebView 自动化必须 sub-agent 化，避免主会话历史永久膨胀。
- 不提交 secrets；不把代理/本机路径硬编码进仓库。

## Requirements（Req IDs）

### A. 统一预算化（SDK 入口兜底）

- REQ-0003-001：SDK 在构建 provider input（responses/legacy）时，对 tool outputs 做统一预算化（不依赖 tool 自觉）。
  - Acceptance：
    - 任意 ToolResult 输出中的超长字符串字段会被“头+尾”截断并带 marker；
    - 即使 tool 输出异常大，也不会导致 provider input 构建阶段 OOM/明显卡死；
    - `external/openagentic-sdk-kotlin` 单测可覆盖一条“超长 tool output”不炸的路径。

### B. Artifacts 化（WebView/高噪音结果落盘）

- REQ-0003-010：`web_snapshot` artifacts 化（可选，延期）。[已由 ECN-0002 变更]
  - 说明：在 `Task(agent="webview")` 子会话隔离到位后，`web_snapshot` 继续做 artifacts 化的收益下降，暂不作为 v5 交付门槛（后续可按需要再启用）。
  - Acceptance（延期到后续版本）：
    - tool.result 返回：preview（有界）+ `snapshot_artifact_path`（可追溯）+ `snapshot_sha256`；
    - preview 的截断为“头+尾”；
    - 同一子会话内连续多次 snapshot 不会显著推高输入 tokens（对比改造前明显下降）。

### C. Sub-agent（子会话）隔离主会话

- REQ-0003-020：主会话提供 `Task(agent, prompt)`，用于把高噪音任务下沉到子会话执行，并把结果以小 JSON 回传主会话。
  - Acceptance：
    - App 侧配置 `taskRunner`，至少支持 `agent="webview"` 与 `agent="deep-research"`；
    - 主会话 `allowedTools` 默认**禁用** `web_*`（只能通过 `Task(webview, ...)` 间接使用）；
    - `Task(webview, ...)` 的返回值包含 `sub_session_id` 与 ≤ 指定上限的 `summary`；
    - `Task(deep-research, ...)` 的返回值包含 `sub_session_id` 与 `report_path`（主会话只需知道报告地址即可）；
    - 子会话的 events.jsonl 与 artifacts 可在 `.agents/sessions/<id>/events.jsonl` 与 `.agents/artifacts/...` 中追溯。

### D. 触发兜底（Compaction 更可靠）

- REQ-0003-030：当 provider usage 缺失/不可靠时，SDK 使用估算 tokens 触发 compaction 兜底（在接近阈值时提前执行）。
  - Acceptance：
    - usage 缺失时不会“等到爆”才处理；
    - 触发阈值可配置（默认 80%~90% 输入预算提前 compact）。

## Milestones（建议）

- v5：WebView 工具链 sub-agent 化（REQ-0003-020）+ SDK 统一截断兜底（REQ-0003-001）
- v6：compaction 估算触发兜底（REQ-0003-030）+ WebFetch artifacts 化（按报告建议补齐）

## Open Questions（待确认）

1. `webview` 子会话是否需要“长寿命”（跨多次 Task 复用同一个 sub_session_id）以提升连续交互成功率？默认先做“一次 Task 一个子会话”以避免累积膨胀。
2. artifacts 路径命名：是否要与 `toolUseId` 绑定（便于一键定位），还是只用 timestamp/sha（更通用）？
