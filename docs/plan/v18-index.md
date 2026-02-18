# v18 Index：Calendar CLI（Android 本机日历）

日期：2026-02-18

## Vision（引用）

- PRD：`docs/prd/PRD-0018-calendar-cli.md`
- 本轮目标：把 Android 系统日历（Calendar Provider）访问能力以 `terminal_exec` 白名单命令暴露出来，并把权限门禁、写操作 confirm、隐私脱敏与大输出落盘写死在验收与测试里。

## Milestones

### M1：只读能力（calendars / events）

- PRD Trace：
  - PRD-0018：REQ-0018-001 / REQ-0018-002 / REQ-0018-010 / REQ-0018-012 / REQ-0018-013
- DoD（硬口径）：
  - `cal list-calendars` 可用（缺权限返回 `PermissionDenied`）；
  - `cal list-events --from/--to` 可用（缺权限返回 `PermissionDenied`）；
  - `--out` 落盘完整 JSON 并通过 `artifacts[]` 返回引用；
  - Tests：`.\gradlew.bat :app:testDebugUnitTest` exit code=0
- Plan：`docs/plan/v18-calendar-cli.md`

### M2：写入 + 提醒（create/update/delete/reminder）

- PRD Trace：
  - PRD-0018：REQ-0018-003 / REQ-0018-004 / REQ-0018-011
- DoD（硬口径）：
  - `create/update/delete/add-reminder` 缺 `--confirm` 必拒绝（`ConfirmRequired`）；
  - 缺 `WRITE_CALENDAR` 返回 `PermissionDenied`；
  - 写命令默认不在 stdout/result 输出敏感字段；
  - Tests：`.\gradlew.bat :app:testDebugUnitTest` exit code=0

## Plan Index

- `docs/plan/v18-calendar-cli.md`

## ECN Index

- （本轮无）

