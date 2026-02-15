# v4 Plan：Agent `WebView` 工具（单一语义，多 action）

## Goal

提供一个工具让 Agent 可以驱动 App 内的持久 WebView：导航、执行脚本、读取 DOM、查询状态。保持语义收敛：**一个工具，多 action**。

## PRD Trace

- REQ-0001-051

## Scope

### In

- 工具名：`WebView`
- 输入：JSON（含 `action` 与参数）
- 输出：JSON（含 `ok`、`action`、`data` 或 `error`）
- 所有 WebView 操作在主线程执行（通过 `withContext(Dispatchers.Main)`）

### Out

- 不做“让模型看图”或视觉理解（仅返回文本/结构化数据）
- 不做复杂的 DOM 结构化抽取（先返回 outerHTML/textContent 即可）

## Acceptance（可验证）

- `WebView` 工具可被注册并被允许调用（`allowedTools` 含 `WebView`）
- 支持 action：`goto` / `get_state` / `get_dom` / `run_script` / `back` / `forward` / `reload`
- action 参数缺失/非法时返回结构化错误（不抛出未捕获异常）
- `.\gradlew.bat :app:testDebugUnitTest` 覆盖核心输入校验与分发逻辑

## Files

- `app/src/main/java/com/lsl/kotlin_agent_app/agent/OpenAgenticSdkChatAgent.kt`
- `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/WebViewTool.kt`（新增）
- `app/src/main/java/com/lsl/kotlin_agent_app/web/WebViewController.kt`（新增或复用）
- `app/src/test/java/...`（新增测试）

## Steps（Strict）

1. 写工具输入解析与错误结构（先单测到红）
2. 实现 action 分发（绿）
3. 接入 WebViewController（主线程执行）
4. 把工具注册进 ToolRegistry，并加入 allowedTools + system prompt 简述
5. 运行 `:app:testDebugUnitTest` 绿

## Risks

- WebView 操作跨线程导致崩溃
  - 缓解：统一入口强制 Main dispatcher

