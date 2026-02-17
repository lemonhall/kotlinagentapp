# v11 Index：Pseudo Terminal（白名单 CLI）MVP（最小闭环）

## Vision（引用）

- PRD：`docs/prd/PRD-0011-pseudo-terminal-skill-runtime.md`
- 本轮聚焦（只做最小闭环）：
  - `terminal_exec` 工具（伪终端协议入口）
  - 白名单命令注册表（v1 仅内置 `hello`）
  - `hello-world` 内置 skill 文档能指引并触发命令执行
  - 运行审计落盘（可回放）

## Milestones

### M1：`terminal_exec` + `hello` 闭环

- PRD Trace：
  - REQ-0011-001 / REQ-0011-002
  - REQ-0011-010
  - REQ-0011-020 / REQ-0011-021
- DoD（硬口径）：
  - Tool：新增 `terminal_exec`（白名单命令），输入 `command`，输出包含 `exit_code/stdout/stderr/result`；
  - Command：`hello` 必须输出 `HELLO` ASCII 图，且包含 `lemonhall` 字符签名；
  - Auditing：每次执行写入 `.agents/artifacts/terminal_exec/runs/<run_id>.json`（run_id 为文件名的一部分），且 tool output 返回 `run_id`；
  - Skill：`.agents/skills/hello-world/SKILL.md` 包含 `terminal_exec` 的可执行示例；
  - Tests：`.\gradlew.bat :app:testDebugUnitTest` exit code=0
- Plan：`docs/plan/v11-pseudo-terminal-skill-runtime.md`

## Plan Index

- `docs/plan/v11-pseudo-terminal-skill-runtime.md`

## ECN Index

- （本轮无）

