# v11 Plan：Pseudo Terminal（白名单 CLI）Skill Runtime（MVP）

## Goal

实现 PRD-0011 的最小闭环：Agent 可通过 `terminal_exec` 运行白名单命令；内置 `hello-world` skill 文档能指引调用并获得可验证输出；每次执行有审计落盘。

## PRD Trace

- REQ-0011-001 / REQ-0011-002
- REQ-0011-010
- REQ-0011-020 / REQ-0011-021

## Scope

做：
- 新增工具：`terminal_exec`（伪终端入口，白名单命令路由）
- 新增最小命令：`hello`（输出 HELLO ASCII + `lemonhall` 签名）
- 新增审计落盘：`.agents/artifacts/terminal_exec/runs/<run_id>.json`
- 更新内置 skill：`hello-world/SKILL.md` 写清楚调用方式

不做：
- 不做 `doc extract` 等实际文档命令（留到后续 v12+）
- 不做真正 shell 语义（pipe/redirection/cd/ls 等）
- 不做 UI 终端面板（先用 Chat 的 ToolResult 展示验证闭环）

## Acceptance（硬口径）

1. `terminal_exec` 对 `command` 含换行/多命令串联（如 `\n` 或 `;`）返回 `exit_code!=0` 且错误可解释。
2. 未注册命令返回 `exit_code!=0` 且 `error_code="UnknownCommand"`。
3. `terminal_exec` 执行 `hello`：
   - `exit_code=0`
   - `stdout` 包含 `HELLO` ASCII 图
   - `stdout` 额外包含 `lemonhall` 字符签名
4. 每次执行都写入 `.agents/artifacts/terminal_exec/runs/<run_id>.json`，且 tool output 返回同一个 `run_id`。
5. `hello-world` 的 `SKILL.md` 包含可直接执行的示例命令（调用 `terminal_exec` → `hello`）。
6. `.\gradlew.bat :app:testDebugUnitTest` exit code=0

## Files

- `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecTool.kt`
- `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalCommands.kt`
- `app/src/main/assets/builtin_skills/hello-world/SKILL.md`
- `app/src/main/java/com/lsl/kotlin_agent_app/agent/OpenAgenticSdkChatAgent.kt`
- `app/src/main/java/com/lsl/kotlin_agent_app/ui/chat/ChatViewModel.kt`
- `app/src/test/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecToolTest.kt`

## Steps（Strict）

1) TDD Red：新增 `TerminalExecToolTest`（unknown command / hello 输出 / 审计落盘）
2) TDD Green：实现 `terminal_exec` 工具与命令注册表，跑测试到绿
3) Refactor：抽取解析/审计为小函数，保持绿
4) 接入：把 `terminal_exec` 加到工具注册、allowedTools、system prompt 与 UI 进度文案
5) Verify：`.\gradlew.bat :app:testDebugUnitTest`

## Risks

- OpenAI function name 不允许 `terminal.exec`：本轮按 PRD 注记使用 `terminal_exec`，后续可在 UI 层展示为 `terminal.exec`。
- 工具输出过长污染上下文：本轮输出固定短文本；后续命令必须做截断 + artifact 落盘。

