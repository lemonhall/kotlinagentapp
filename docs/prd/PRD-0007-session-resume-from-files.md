# PRD-0007：Files 选会话续聊 + Chat 历史回放（最小闭环）

日期：2026-02-17  
来源调研：`research/resume-research.md`

## Vision

让“从 `.agents/sessions` 里选一个 session → 切换为当前主会话继续聊”成为用户可感知的闭环体验：

1. **会话身份可追溯**：新建 session 的 `meta.json.metadata` 写入最小身份字段（primary/task + parent + agent）。
2. **Files 可续聊**：在 Files 页签对 primary session 提供“续聊”入口，并明确阻止 task session 直接续聊。
3. **Chat 可回放**：切换 session 后，Chat 页签能从 `events.jsonl` 回放历史消息气泡，否则用户只感知“续了但看不到历史”。

## Background

- SDK 的 resume 语义是“读 `events.jsonl` 作为上下文继续 append 同一个 session”，不是“回放 UI”。  
- 当前 App：Chat 只依赖 `KEY_SESSION_ID` 续聊，但 UI 不会自动加载历史消息；Files 页签也没有“点 session → 续聊”的产品层入口。  

## Non-Goals（本轮不做）

- 不做 OpenCode 那种“产品层完整会话回放/子会话树管理/权限模型/标题体系重建”。
- 不做“子会话（Task）一键续跑/复用 task_id 续跑同一子会话”。
- 不引入远端持久化/云同步；仍以 `.agents` 本地工作区为准。

## Constraints & Principles

- **证据优先**：会话身份与回放必须能从 `meta.json` / `events.jsonl` 直接验证。
- **兼容历史数据**：旧 session 的 `meta.json.metadata` 可能为空，需按 primary 处理（向后兼容）。
- **体验优先**：用户点击 primary session 后，应能立刻进入 Chat 并看到历史气泡。

## Requirements（Req IDs）

### A. 会话身份（meta.json.metadata）

- REQ-0007-001：SDK 新建 session 时支持写入 `meta.json.metadata`（由调用方提供 map），用于标识会话身份。
  - primary session 最小字段：`kind=primary`
  - task session 最小字段：`kind=task`、`parent_session_id=<primary sid>`、`agent=<task agent>`
  - Acceptance：
    - SDK 单测：新建 session 时传入 metadata，落盘 `meta.json` 中可读到对应键值。

### B. Files：点 session 续聊（primary only）

- REQ-0007-010：在 `.agents/sessions` 列表中展示会话类型，并对 primary session 提供“续聊”行为。
  - 行为：
    - 点击 primary session：写入 `AppPrefsKeys.CHAT_SESSION_ID=<sid>`，并切换到底部导航 Chat 页签。
    - 点击 task session：不写入 session id（不允许直接续聊），默认进入目录浏览。
  - Acceptance：
    - 人工验证：Files 点击 primary session 后，Chat 页面继续同一个 session（后续发送消息会 resume）。
    - 回归：不影响原有“进入目录/打开文件/删除/分享”能力。

### C. Chat：历史回放为气泡

- REQ-0007-020：Chat 在显示时读取当前 `AppPrefsKeys.CHAT_SESSION_ID` 对应的 `events.jsonl`，回放为气泡列表（至少 user/assistant）。
  - 规则：
    - 优先回放 `assistant.message` 与 `user.message/user.question`；避免重复回放 `Result(final_text)`。
    - 限制 UI 内存：回放消息条数需有上限（例如只取最后 N 条 user/assistant）。
  - Acceptance：
    - App 单测：给定 events 序列，回放结果不重复、不丢失 user/assistant。
    - 人工验证：从 Files 切换 session 后，Chat 能看到历史气泡。

## Milestones（建议）

- v10：
  - M1：SDK 支持创建 session metadata（REQ-0007-001）
  - M2：Files primary session “续聊”入口（REQ-0007-010）
  - M3：Chat 历史回放（REQ-0007-020）

