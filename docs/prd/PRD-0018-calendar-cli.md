# PRD-0018：Calendar CLI（Android 本机日历）Pseudo Terminal Commands

日期：2026-02-18  
定位：在 `terminal_exec` 的“白名单 pseudo CLI（无 shell / 无外部进程）”框架下，访问 Android 设备的系统日历（Calendar Provider），提供**受控**的事件读取/写入/提醒能力，并把权限与安全护栏写死在验收与测试里。

> 说明：这里的“本机日历”默认指 **手机/平板设备上的系统日历（Calendar Provider / CalendarContract）**，不是 Windows 端 Outlook/Exchange。

## Vision

让 Agent 与人类都能通过 `terminal_exec` 以可审计、可控风险的方式完成：

- 查询日历列表（calendars）
- 查询指定时间范围内的事件（events）
- 创建/更新/删除事件（write operations）
- 为事件添加提醒（reminders）

并且：

- 必须显式授权（Android runtime permissions）
- 默认安全与克制（写操作 `--confirm`、输出脱敏、避免误删/误覆盖）
- 输出可控（超长输出写入 artifacts）

## Non-Goals（v18）

- 不做邀请参会人/RSVP/会议室资源（attendees）
- 不做复杂重复规则（RRULE/recurrence）与例外（EXDATE）
- 不做跨端同步策略与账号登录（只走系统 Provider）
- 不做日历“新建账号/新建 calendar”（通常需要 sync adapter 权限，复杂且不通用）

## Dependencies（Android）

- Android Calendar Provider：`android.provider.CalendarContract`
- 权限：
  - `android.permission.READ_CALENDAR`
  - `android.permission.WRITE_CALENDAR`

> 约束：不得引入外部进程执行；不得通过无障碍/屏幕抓取去操纵第三方日历 App。

## Project Rules（实现必须遵守：来自 paw-cli-add-workflow）

- `TerminalCommands.kt` 只能做“命令注册表/默认 registry 构建”，禁止堆实现逻辑。
- 每个顶层命令必须独立文件/目录：
  - `cal`：`app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/cal/CalCommand.kt`
- `TerminalExecTool` 的 registry 初始化逻辑不改；只在 `TerminalCommands.defaultRegistry(...)` 注册新增命令。

## Command Set（v18）

统一约束：

- 所有命令输出同时提供：`stdout`（摘要）+ `result`（结构化）+（必要时）`artifacts[]`（落盘大输出）。
- 所有会修改系统日历数据的命令必须显式 `--confirm`，否则拒绝（`error_code="ConfirmRequired"`）。
- 若缺少系统权限或用户拒绝授权：拒绝并返回可解释错误（`error_code="PermissionDenied"`）。
- 输出默认脱敏：不输出 `notes/description`、不输出参会人邮箱；需要的话后续版本再加显式开关。

### cal

#### `cal list-calendars`

- 命令：`cal list-calendars [--max <n>] [--out <path>]`
- 行为：
  - 默认 stdout 输出前 `--max`（缺省 50）个日历摘要
  - 若提供 `--out`：写入完整清单 JSON 并返回 artifact 引用
- result（最小字段）：
  - `ok: boolean`
  - `command: "cal list-calendars"`
  - `count_total: number`
  - `count_emitted: number`
  - `truncated: boolean`
  - `calendars: [{id, display_name, account_name, account_type, owner_account, visible, is_primary}]`（可截断）

#### `cal list-events`

- 命令：`cal list-events --from <RFC3339> --to <RFC3339> [--calendar-id <id>] [--max <n>] [--out <path>]`
- 行为：
  - 仅查询时间范围内的事件（用 Instances/Events 视实现选择）
  - 默认 stdout 输出前 `--max`（缺省 200）条摘要
  - 若提供 `--out`：写入完整清单 JSON 并返回 artifact 引用
- result（最小字段）：
  - `ok`
  - `command: "cal list-events"`
  - `from`, `to`
  - `calendar_id`（可选）
  - `count_total`, `count_emitted`, `truncated`
  - `events: [{id, calendar_id, title, start_time_ms, end_time_ms, all_day, location}]`（可截断）

#### `cal create-event`

- 命令：`cal create-event --calendar-id <id> --title <text> --start <RFC3339> --end <RFC3339> [--all-day] [--location <text>] [--remind-minutes <n>] --confirm`
- 行为：
  - 创建一个事件并返回 `event_id`
  - 若提供 `--remind-minutes`：为该事件新增 1 条 reminder（分钟）
- result（最小字段）：
  - `ok`
  - `command: "cal create-event"`
  - `event_id: number`
  - `calendar_id`
  - `reminder_added: boolean`

#### `cal update-event`

- 命令：`cal update-event --event-id <id> [--title <text>] [--start <RFC3339>] [--end <RFC3339>] [--all-day true|false] [--location <text>] --confirm`
- 行为：更新事件字段（未提供的字段保持不变）
- result：`ok`、`event_id`、`updated_fields[]`

#### `cal delete-event`

- 命令：`cal delete-event --event-id <id> --confirm`
- 行为：删除事件
- result：`ok`、`event_id`

#### `cal add-reminder`

- 命令：`cal add-reminder --event-id <id> --minutes <n> --confirm`
- 行为：为事件新增 reminder（分钟）
- result：`ok`、`event_id`、`minutes`、`reminder_id`（如可取）

## Safety（必须）

1) 权限门禁  
- 没有 `READ_CALENDAR`：所有读命令拒绝（`PermissionDenied`）
- 没有 `WRITE_CALENDAR`：所有写命令拒绝（`PermissionDenied`）

2) 写操作双门禁  
- `create/update/delete/add-reminder` 缺失 `--confirm` 必拒绝（`ConfirmRequired`）

3) 输出与隐私  
- stdout/result 默认只输出最小必要字段（不包含描述、参会人、会议链接等敏感字段）
- 审计日志（`.agents/artifacts/terminal_exec/runs/*.json`）不得记录 stdin（沿用现有约束）

## Acceptance（v18 文档口径）

1. `terminal_exec` 新增顶层命令 `cal`，并实现 `list-calendars/list-events/create-event/update-event/delete-event/add-reminder`。
2. 写命令缺失 `--confirm` 必须拒绝（`ConfirmRequired`）。
3. 缺权限时必须拒绝（`PermissionDenied`），并在 stderr 给出“需要在系统设置授予权限”的可解释提示。
4. list 超长输出必须支持 `--out` + artifacts 落盘获取完整清单。
5. `.\gradlew.bat :app:testDebugUnitTest` exit code=0（覆盖 confirm 门禁、`--out` 落盘、基础读写流程的单测；Provider 交互需通过可替换 store/adapter 测试桩保证可测）。

## Requirements（Req IDs）

### A. 命令面

- REQ-0018-001：新增 `cal list-calendars` 并返回结构化 `result`。
- REQ-0018-002：新增 `cal list-events`（时间范围）并返回结构化 `result`。
- REQ-0018-003：新增 `cal create-event/update-event/delete-event`（写操作需 `--confirm`）。
- REQ-0018-004：新增 `cal add-reminder`（写操作需 `--confirm`）。

### B. 安全面

- REQ-0018-010：权限门禁：无权限时返回 `PermissionDenied` 且可解释。
- REQ-0018-011：写操作门禁：缺 `--confirm` 返回 `ConfirmRequired`。
- REQ-0018-012：输出脱敏：默认不输出 notes/attendees 等敏感字段；stdout/result 保持最小必要信息。
- REQ-0018-013：超长输出：`--out` 落盘 + artifacts 引用（list-calendars/list-events）。

### C. 工程规矩

- REQ-0018-020：命令实现必须放在独立目录 `commands/cal/*`；`TerminalCommands.kt` 只做注册表。

