# PRD-0009：Schedule Trigger + Run Queue（每天 8 点自动跑流程）

日期：2026-02-17  
关联：`docs/prd/PRD-0008-local-first-automation.md`（自动化总 PRD，本 PRD 聚焦“每天 8 点触发/队列/单工具入队”）

## Vision

让用户在不打开 App 的情况下，也能在**每天 08:00（本地时区）**自动触发某条 Automation（或一组 due tasks），完成“检查执行表 → 运行 → 落盘 → 通知”的闭环；同时让 Agent 只需要一个工具就能把任务加入队列（Inbox/Queue），由调度器按计划执行。

## Key Point（Android 现实边界）

- **可以**：每天 8 点在后台拉起进程执行（系统调度 → Worker → 读执行表 → 跑流程 → 发通知）。
- **不保证**：后台自动把 UI “弹到前台”。正确路径是**后台执行 + 通知**，用户点通知进入详情。
- **可能延迟**：省电/Doze/OEM 管控会导致“8 点附近”延后几分钟（v1 默认接受）；若用户强要求“准点”，需要额外策略与用户授权（见 Trigger Matrix）。

## Concepts

- **Schedule Trigger**：每天固定时间触发一次“扫描 + 触发 due runs”。
- **Run Queue**：一个本地队列，承载“待运行任务”（来自 Schedule 扫描、Share Inbox、或 Agent 工具入队）。
- **Inbox Item**：从 Share 或 Agent 工具进入的输入快照（文本/URL/元数据），可映射到某个 Automation。

## Trigger Matrix（与 PRD-0008 形成矩阵）

| Trigger | 用户动作 | 精度 | 权限敏感度 | 可靠性（不同机型） | 适合场景 |
|---|---:|---:|---:|---:|---|
| Schedule（WorkManager，默认） | 0 | 中（可能延迟） | 低 | 中高 | 每日简报、每日检查表 |
| Schedule（Exact Alarm，可选） | 0 | 高（更准点） | 中高（可能需额外授权/设置） | 中（依赖 OEM 与设置） | “必须 8:00” 的提醒/执行 |
| Share（Intent） | 1 | 即时 | 低 | 高 | 看到内容就丢进流程 |
| Manual（快捷方式/按钮） | 1 | 即时 | 低 | 高 | 兜底触发/调试 |

> v1 目标：先把 **WorkManager Schedule + Share Inbox + Run Queue** 打通；Exact Alarm 作为 v2+ 的增强开关。

## Data & Storage（建议约定）

复用 PRD-0008 的目录约定，补充队列与 inbox 的最小数据结构：

- `.agents/queue/pending/<queue_item_id>.json`
- `.agents/queue/running/<queue_item_id>.json`（可选）
- `.agents/queue/done/<queue_item_id>.json`（可选，或仅落 run 目录）
- `.agents/inbox/<inbox_id>.json`（Share/Agent 输入快照）

建议 `queue_item.json` 最小字段：

- `queue_item_id`
- `created_at`
- `source`: `schedule|share|agent`
- `automation_id`
- `input_ref`: 指向 inbox 文件或内联摘要
- `status`: `pending|running|succeeded|failed|canceled`
- `run_id`（开始执行后写入）

## Single Tool：让 Agent “只用一个 Tool 就能加任务”

定义一个统一工具（名称可后续调整）：

- `automation.enqueue`
  - 输入：`automation_id`、`payload`（text/url/json）、`run_at`（可选：立即/某时间/交给 schedule）、`priority`（可选）
  - 输出：`queue_item_id`、`inbox_id`（可选）、`next_run_estimate`（best-effort）

语义：**只负责“落盘 + 入队”**，不直接执行；执行交给调度器/队列 worker，确保可审计与可重试。

## Requirements（Req IDs）

### A. 每日 8 点扫描（Schedule）

- REQ-0009-001：支持配置一个“每日扫描时间”（默认 08:00，本地时区）。
- REQ-0009-002：到点触发后台扫描：读取已启用 automations，挑选 due 的条目并写入 Run Queue。
- Acceptance：
  - App 未打开也能在 08:00 附近触发一次扫描；
  - 扫描结果可在本地落盘（queue_item + run 记录）；
  - 用户至少收到一条通知（成功/失败）。

### B. 队列执行（Run Queue Worker）

- REQ-0009-010：队列执行遵循“单机串行或小并发”策略（v1 建议串行，降低功耗与复杂度）。
- REQ-0009-011：支持取消：当用户取消某个 queue_item，落盘状态并停止后续步骤（best-effort）。
- REQ-0009-012：失败可重试：允许把 failed item 重新入队（从头跑）。

### C. 通知与回到现场

- REQ-0009-020：每次队列开始/完成（或合并摘要）发本地通知，点按进入 Run 详情或产物。

### D. 机型/省电兜底（文案与引导）

- REQ-0009-030：在设置页提供“后台执行可靠性”提示（如电池优化/后台限制），并能引导用户到系统设置（不强制）。

## Notes（实现提示，文档层）

- v1 推荐采用 WorkManager 来做“每日扫描 + 队列执行”，让系统负责重启后恢复与调度。
- 若未来要“准点 08:00”，再评估引入 Exact Alarm，并把它作为用户可见的高级开关（清晰告知代价与设置要求）。

