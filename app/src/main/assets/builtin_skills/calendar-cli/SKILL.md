---
name: calendar-cli
description: 通过 `terminal_exec` 提供 Android 本机日历（Calendar Provider）访问：calendars/events 读取 + 事件写入/提醒（写操作强制 --confirm），并支持 --out 落盘 artifacts。
---

# calendar-cli（Android Calendar / Pseudo CLI）

## Goal

在 App 内（`.agents` 工作区）用 `terminal_exec` 执行白名单命令 `cal`，完成：

- 读取：列日历 / 按时间范围列事件（缺权限必须拒绝）
- 写入：create/update/delete/add-reminder（缺 `--confirm` 必须拒绝）
- 大输出：`--out` 落盘 JSON，并在 tool output 的 `artifacts[]` 返回 `.agents/<path>` 引用

## Commands（v18）

### 1) list-calendars（可选 --out）

使用工具 `terminal_exec` 执行：

- `cal list-calendars --max 50`

（可选）落盘完整清单：

- `cal list-calendars --max 50 --out artifacts/cal/calendars.json`

期望：
- `exit_code=0`
- `result.ok=true`
- 若使用 `--out`：tool output 的 `artifacts[]` 包含 `.agents/artifacts/cal/calendars.json`

### 2) list-events（必须 --from/--to，可选 --out）

使用工具 `terminal_exec` 执行：

- `cal list-events --from 2026-02-18T00:00:00Z --to 2026-02-19T00:00:00Z --max 200`

（可选）落盘完整清单：

- `cal list-events --from 2026-02-18T00:00:00Z --to 2026-02-19T00:00:00Z --out artifacts/cal/events.json`

期望：
- `exit_code=0`
- `result.ok=true`
- 若使用 `--out`：tool output 的 `artifacts[]` 包含 `.agents/artifacts/cal/events.json`

### 3) 写操作（必须 --confirm）

创建事件：

- `cal create-event --calendar-id 1 --title "Demo" --start 2026-02-18T10:00:00Z --end 2026-02-18T11:00:00Z --remind-minutes 15 --confirm`

更新事件：

- `cal update-event --event-id <id> --location "Room" --confirm`

添加提醒：

- `cal add-reminder --event-id <id> --minutes 30 --confirm`

删除事件：

- `cal delete-event --event-id <id> --confirm`

期望：
- `exit_code=0`
- `result.ok=true`

## Safety / Guardrails

- 无 `READ_CALENDAR`：`list-calendars/list-events` 必须失败，`error_code="PermissionDenied"`。
- 无 `WRITE_CALENDAR`：写操作必须失败，`error_code="PermissionDenied"`。
- 写操作缺 `--confirm`：必须失败，`error_code="ConfirmRequired"`。
- stdout/result 默认只包含最小必要字段（不输出 notes/attendees 等敏感字段）。

## Rules

- 必须实际调用 `terminal_exec`，不要臆造 stdout/result/artifacts。
- `terminal_exec` 不支持 `;` / `&&` / `|` / 重定向；所有命令必须单行。
- 若工具返回 `exit_code!=0` 或包含 `error_code`，直接把错误信息说明给用户并停止。

