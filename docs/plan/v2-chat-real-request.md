# v2 Plan：Chat 真对话（流式 SSE / OpenAI Responses 风格）最短闭环

## Goal

让 Chat 从 echo/mock 升级为“读取设置 → 调用 SDK（Responses SSE 流式）→ 增量展示回复”的最短闭环；未配置时给出清晰错误提示。

## PRD Trace

- REQ-0001-001
- REQ-0001-004
- REQ-0001-010
- REQ-0001-040

## Scope

做：
- 引入 `AgentService`（封装 SDK 调用），UI/VM 仅依赖接口
- 增加 `AppConfigRepository`（先用简单持久化：SharedPreferences 或 DataStore，择一）
- ChatViewModel 调用 AgentService（流式），assistant 文本随 delta 增量更新；失败进入错误状态（可重试）

不做：
- 不做非流式 fallback（本轮以流式为唯一主路径，符合 ECN-0001）
- 不做 tool calls 真实链路（仍保留 tool trace 占位）
- 不做消息持久化/会话列表

## Acceptance（硬口径）

1. 未配置（base_url/api_key/model 为空）时：`sendUserMessage()` 后 UI 状态出现 `errorMessage`（且不崩溃）。
2. 已配置时：`sendUserMessage()` 后消息列表出现 assistant 气泡占位，并在收到 delta 后逐步增长（可用可注入的 fake streaming AgentService 单测验证）。
3. `.\gradlew.bat :app:testDebugUnitTest` ✅
4. `.\gradlew.bat :app:assembleDebug` ✅

## Steps（Strict）

1) 先写单测（红）：ChatViewModel 在未配置/已配置两种情况下的状态变化（含 delta 增量）
2) 实现配置仓库与 AgentService（绿）
3) 将 ChatViewModel 从 echo 改为调用 AgentService（绿）
4) build gate
