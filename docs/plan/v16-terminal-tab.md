# v16 Plan：Terminal Tab（命令历史 + 人类输入执行）

## Goal

让 `terminal_exec` 拥有一个“像终端”的 UI：

- 读取审计文件展示历史
- 人类输入命令执行并立即显示输出

## PRD Trace

- PRD-0016：REQ-0016-001 ~ REQ-0016-004 / REQ-0016-010

## Scope

做：
- 扩展 `terminal_exec` 审计 JSON：写入 stdout/stderr（截断），但绝不写 stdin
- 新增 `TerminalFragment`（RecyclerView 历史 + 输入框运行）
- 接入 bottom nav 与 navigation graph
- 单测覆盖审计内容（最小断言）

不做：
- 交互式 TTY

## Acceptance（硬口径）

1. Terminal 页签可打开，默认展示最近 200 条 run（最新在上）。
2. 手动执行 `hello` 成功，弹窗展示 stdout，且历史新增记录。
3. 审计 JSON 包含 `stdout/stderr` 字段，且不包含 stdin。
4. `.\gradlew.bat :app:testDebugUnitTest` exit code=0

## Files

- `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecTool.kt`
- `app/src/test/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecToolTest.kt`
- `app/src/main/java/com/lsl/kotlin_agent_app/ui/terminal/*`
- `app/src/main/res/layout/fragment_terminal.xml`
- `app/src/main/res/layout/item_terminal_run.xml`
- `app/src/main/res/menu/bottom_nav_menu.xml`
- `app/src/main/res/navigation/mobile_navigation.xml`
- `app/src/main/java/com/lsl/kotlin_agent_app/MainActivity.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/drawable/ic_terminal_24.xml`

## Steps（Strict）

1) TDD Red：为审计 JSON 增加 stdout/stderr 且不含 stdin 的测试
2) TDD Green：修改 `TerminalExecTool` 落盘审计
3) 实现 Terminal 页签 UI + 手动执行（复用 TerminalExecTool）
4) 接入 bottom nav + navigation graph
5) Verify：`.\gradlew.bat :app:testDebugUnitTest`

