# PRD-0005：Explore Sub-agent + Tool Output Truncation（OpenCode 对齐）

日期：2026-02-17  
参考：OpenCode（`opencode`）的 `Truncate.output()` + `explore` agent 设计

## Vision

把“工具输出太大 → 历史膨胀 → 上下文炸 / UI 卡死 / 子任务无响应”从偶发事故变成**可工程化治理**：

1) 任意工具输出（tool.result）都必须**有界**写入 session（`events.jsonl`）与 provider input（避免 OOM / context_length_exceeded）。  
2) 超出预算的完整输出自动**落盘为 artifact**，并在 tool.result 中返回**preview + artifact_path + 下一步建议**。  
3) 提供一个 SDK 内置的 `explore` 子 agent（通过 `Task(agent="explore", ...)` 调用），专门用于“读/Grep/定位/总结”落盘的大输出，避免主会话被大块内容污染。

## Background

当前仓库已经具备：

- `Task(agent="webview")`、`Task(agent="deep-research")` 子会话隔离（App 侧 runner 实现）。
- SDK 层 “provider input 前的 tool output JSON 截断 wrapper”（`_openagentic_truncated`）以保证 provider input 有界。

但仍缺少 OpenCode 式的“**完整输出落盘 + 只回 preview**”这一关键环节，导致：

- `events.jsonl` 仍可能积累巨大 tool output（尤其 web / fetch / snapshot / 搜索结果等）。
- 主会话很难把“超大输出”交给专职 agent 处理（缺少内置 explore agent + 标准化提示词与 next-steps）。

## Non-Goals（本 PRD 迭代范围外）

- 不做复杂的多 agent DAG / worker pool 调度。
- 不追求把所有工具都默认 sub-agent 化；目标是“**大输出外置**”，而不是“所有工具都下沉”。
- 不强制引入新的 UI 交互（仅 SDK + App 接入）；UI 的“可点击 artifact 打开”后续单独 PRD。

## Reference: OpenCode 对齐点（可追溯）

### A) 全局截断 + 落盘

- `opencode`：统一在工具 wrapper 层做截断与落盘
  - `packages/opencode/src/tool/tool.ts`：对 `result.output` 统一调用 `Truncate.output(...)`
  - `packages/opencode/src/tool/truncation.ts`：`MAX_LINES=2000`、`MAX_BYTES=50KB`、落盘到 `tool-output/`，tool.result 返回 preview + full path + hint（含 Task/explore 建议）

### B) explore 专职 agent

- `opencode`：`explore` 子 agent 提供“只读搜索/定位/总结”的系统提示词
  - `packages/opencode/src/agent/prompt/explore.txt`
  - `packages/opencode/src/agent/agent.ts`：`explore` 权限白名单（Read/Glob/Grep/List/...），禁止写文件

## Requirements（Req IDs）

### A. SDK：ToolResult 有界 + 完整输出落盘（Artifacts）

- REQ-0005-001：当工具输出超过预算时，SDK 必须把**完整输出**写入工作区 artifact（默认：`<root>/.agents/tool-output/*`），并将 `ToolResult.output` 替换为有界 wrapper（不再把完整输出写入 `events.jsonl`）。
  - wrapper 字段（最小集合）：
    - `_openagentic_truncated=true`
    - `reason="tool_output_too_large"`
    - `tool_name`、`tool_use_id`
    - `original_chars`（或 bytes，至少一个）
    - `preview`（有界文本）
    - `artifact_path`（可被 Read/Grep 读取的路径）
    - `hint`（包含“下一步建议”，可引导使用 `Task(agent="explore")`）
  - Acceptance：
    - `events.jsonl` 中不出现超大 tool output（> 200KB 级别）原文；
    - provider input 构建仍保持有界（既有单测继续通过）；
    - artifact 文件内容为完整输出（可被 `Read` 读取并复现）。

- REQ-0005-002：工具输出落盘目录需有稳定命名与可追溯性（至少包含 `tool_use_id`）。
  - Acceptance：
    - 任意 wrapper 的 `artifact_path` 必须可逆定位到同一次 tool_use；
    - 文件名无非法字符（跨平台可用）。

### B. SDK：内置 explore 子 agent（Task(agent="explore")）

- REQ-0005-010：SDK 内置 `explore` 子 agent prompt（对齐 OpenCode `explore.txt`），专注于：
  - `Glob/Grep/Read/List` 找文件、找行、读局部内容
  - 输出“结论 + 证据指针（文件路径/行号）”
  - 禁止写文件/编辑/破坏性命令
  - Acceptance：
    - SDK 提供可复用的 `explore` prompt 常量/构造器；
    - App 侧仅需启用即可让 `Task(agent="explore")` 可运行。

- REQ-0005-011：SDK 提供 `TaskRunner` 的组合/路由能力（至少支持“内置 explore runner + App 自定义 runner”组合），避免 App 为 explore 写重复样板。
  - Acceptance：
    - 当 `agent=="explore"` 时走 SDK 内置 runner；
    - 其它 agent 走 App 自定义 runner；
    - runner 不支持的 agent 必须返回明确错误（不会静默失败）。

### C. SDK：Task 工具描述自动注入“可用 sub-agent 列表”

- REQ-0005-020：`Task` tool 的 schema description 必须展示可用 sub-agent 列表（名字 + 简短描述 + 推荐用途），并与参数名一致（`agent`/`prompt`）。
  - 背景：当前 `task.txt` 存在 `{agents}` 占位符与 `subagent_type` 文案，与 schema 不一致，容易误导模型。
  - Acceptance：
    - `{agents}` 占位符被正确渲染（或替换为 `{{agents}}` 以匹配模板引擎）；
    - 文案使用 `Task(agent="...", prompt="...")`；
    - 当配置了 `taskAgents` 列表时，description 中可见其内容。

### D. 自动提示（对齐 OpenCode 的“自动触发”）

- REQ-0005-030：当 tool output 被截断并落盘时，wrapper 的 `hint` 必须自动包含：
  - “完整输出已保存到 artifact_path”
  - “不要把整份文件读进上下文；应使用 `Task(agent=\"explore\")` + `Grep/Read offset/limit` 做局部提取与总结”
  - Acceptance：
    - 不依赖 App 主提示词也能在 tool.result 中看到 next-steps；
    - 当 `Task` 不可用（未允许或无 runner）时，hint 退化为 “用 Grep/Read 局部读取”。

## Examples（行为示例）

### Example 1：WebFetch 返回超大 HTML/Markdown

1) 模型调用 `WebFetch(url=...)`
2) 工具返回巨大输出
3) SDK 落盘并在 `tool.result` 返回 wrapper：

```json
{
  "_openagentic_truncated": true,
  "reason": "tool_output_too_large",
  "tool_name": "WebFetch",
  "tool_use_id": "call_123",
  "original_chars": 183421,
  "preview": "<html>...…182000 chars truncated…</html>",
  "artifact_path": "/data/user/0/.../.agents/tool-output/tool_call_123.json",
  "hint": "Full output saved to artifact_path. Use Task(agent=\"explore\") to grep/read only the relevant parts."
}
```

### Example 2：主会话自动“建议 explore”，但不强制执行

模型看到 `hint` 后，自行决定调用：

`Task(agent="explore", prompt="请在 artifact_path=... 中定位 <关键词> 并用文件/行号给出结论")`

## Milestones（建议）

- v8：SDK 落盘截断（REQ-0005-001/002）+ SDK 内置 explore runner/提示词（REQ-0005-010/011）+ Task 描述注入 agents 列表（REQ-0005-020/030）

## Open Questions（待确认）

1. tool-output 的 retention（清理策略）是否要在 SDK 内置（如 OpenCode：保留 7 天）？本 PRD 先不强制。
2. artifact 文件格式：默认存 JSON 字符串，还是存原始类型（如 text/markdown）？本 PRD 先统一存 JSON 字符串。

