# v6 Plan：Deep-Research 子会话交付（只回传报告地址）

## Goal

把 deep-research 的“过程噪音”（WebFetch/WebSearch/web_* 等多轮工具调用）完全隔离到子会话中；主会话只接收一个 `report_path`，用户或主会话再按需读取报告。

## PRD Trace

- REQ-0003-020

## Scope

### In scope

- 扩展 `TaskRunner` 支持 `agent="deep-research"`
- 子会话强制写 Markdown 报告到稳定路径（`artifacts/reports/deep-research/*.md`）
- `Task(deep-research, ...)` tool.result 只回传 `report_path`、`sub_session_id` 等指针字段

### Out of scope

- 深度研究报告的 UI 渲染（在 Chat 内渲染 Markdown）
- 研究策略优化（来源质量排序、自动去重、引用规范自动校验）

## Acceptance（硬口径）

- `Task(deep-research, ...)` 返回的 JSON 包含 `report_path`
- `<agents_root>/<report_path>` 文件存在且非空
- 主会话不暴露 `web_*`（保持隔离纪律）
- `.\gradlew.bat :app:testDebugUnitTest` exit code=0

## Files

- `app/src/main/java/com/lsl/kotlin_agent_app/agent/OpenAgenticSdkChatAgent.kt`
- `docs/plan/v6-index.md`
- `docs/plan/v6-deep-research-subagent.md`

## Steps

1) 实现 `runSubAgent(agent="deep-research")`：分配 report_path → 运行子会话 → 若未生成则写 fallback → 回传 `report_path`
2) 调整主会话 system prompt：要求 deep-research 只能走 `Task(agent="deep-research")`，并只输出报告地址
3) 运行 `:app:testDebugUnitTest` 验证

## Risks

- 若 WebSearch（Tavily）未配置或 WebView 未初始化，研究质量可能下降；但本计划目标是“隔离上下文污染”，不是保证每次都能搜索成功。

