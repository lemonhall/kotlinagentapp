# v18 Plan：Calendar CLI（Android 本机日历）

## Goal

实现 PRD-0018 的 `cal` 白名单命令（读/写/提醒），并把权限门禁、写操作 confirm、输出脱敏与大输出落盘固化成测试与验收口径，避免“能跑但不安全/不可测”的假交付。

## PRD Trace

- PRD-0018：REQ-0018-001 / REQ-0018-002 / REQ-0018-003 / REQ-0018-004
- PRD-0018：REQ-0018-010 / REQ-0018-011 / REQ-0018-012 / REQ-0018-013
- PRD-0018：REQ-0018-020

## Scope

做：
- 新增命令：
  - `cal list-calendars`
  - `cal list-events`
  - `cal create-event`
  - `cal update-event`
  - `cal delete-event`
  - `cal add-reminder`
- Android 权限处理：
  - READ/WRITE 日历权限缺失 → `PermissionDenied`
- 安全策略（必须）：
  - 所有写操作强制 `--confirm`
  - list 超长输出支持 `--out` 落盘 + artifacts 引用
  - 输出默认脱敏（不输出 notes/attendees）

不做（v18）：
- recurrence/attendees/会议邀请
- 新建 calendar/账号层面的管理（不通用且权限复杂）
- UI 交互式授权引导（仅返回可解释错误，UI 后续版本再补）

## Acceptance（硬口径）

1. 写命令（create/update/delete/add-reminder）缺失 `--confirm` 必拒绝（`ConfirmRequired`）。
2. 无 `READ_CALENDAR` 时，`list-calendars/list-events` 必拒绝（`PermissionDenied`）。
3. 无 `WRITE_CALENDAR` 时，写命令必拒绝（`PermissionDenied`）。
4. `list-calendars/list-events` 支持 `--out`，并通过 `artifacts[]` 返回 `.agents/<out_path>` 引用。
5. `.\gradlew.bat :app:testDebugUnitTest` exit code=0。

## Files（规划：遵守 paw-cli-add-workflow）

- 注册表（只注册，不写实现）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalCommands.kt`
- 命令实现（拆分目录）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/cal/CalCommand.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/calendar/*`（可选：Provider adapter / store 抽象，便于单测）
- 单测（Robolectric）：
  - `app/src/test/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecToolTest.kt`
- 内置 skill（让 Agent 能按说明书调用）：
  - `app/src/main/assets/builtin_skills/calendar-cli/SKILL.md`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`

## Steps（Strict）

1) Analysis：确认 Android Calendar Provider 能力边界（权限、字段、ROM 差异），锁定最小字段集与脱敏策略  
2) TDD Red：为 confirm 门禁、权限拒绝、`--out` artifacts、基本读写流程（使用可替换 store fake）加测试并跑红  
3) TDD Green：实现 `cal` 命令（Provider adapter + 输出契约）并跑绿  
4) Refactor：抽取共享输出/落盘工具函数，保持命令实现文件职责清晰  
5) 接入：注册命令 + 安装内置 skill  
6) Verify：`.\gradlew.bat :app:testDebugUnitTest`

## Risks

- Provider 可测性：Robolectric 对 Calendar Provider shadow 支持有限 → 必须用 adapter + fake store 保证单测可跑。
- 隐私风险：日历数据敏感 → 默认脱敏 + 写操作 `--confirm`，并避免在 stdout 直接回显长文本。
- ROM/账号差异：不同厂商 Provider 字段/行为可能差异 → 失败必须可解释，逐步完善兼容性。

