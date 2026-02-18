# v26 Plan：SSH CLI（实现闭环：命令 + 单测 + builtin skill）

## Goal

按 `docs/prd/PRD-0026-ssh-cli.md` 的协议与护栏，把 `terminal_exec` 的白名单命令 `ssh`（至少 `ssh exec`）用 TDD 落地，并交付可用的 builtin skill `ssh-cli`（含 secrets 模板）。

## PRD Trace

- PRD-0026：REQ-0026-001 / REQ-0026-002 / REQ-0026-003
- PRD-0026：REQ-0026-010 / REQ-0026-011 / REQ-0026-012 / REQ-0026-013 / REQ-0026-014

## Scope

做：

- 新增 pseudo CLI：`ssh exec`
  - 远程命令从 `stdin` 读取（允许包含 `;`/`|`/`>` 等），argv 不接受远程命令字符串
  - 基本参数：`--host` / `--port`（默认 22）/ `--user` / `--timeout-ms`（可选、上限）
  - TOFU 护栏：unknown host 必须显式 `--trust-host-key` 才允许执行，并写入 `.agents/workspace/ssh/known_hosts`
  - secrets：仅从 `.agents/skills/ssh-cli/secrets/.env` 读取（password / private key），禁止通过 argv 传入
  - `--out <rel>`：把结构化结果与（可选）完整 stdout/stderr 写入 `.agents/<rel>`，并在 `artifacts[]` 返回 `.agents/<rel>`
- 新增 builtin skill：`ssh-cli`
  - 文档明确 `terminal_exec` + 单行命令用法
  - 包含 secrets 初始化说明（`.env` 自动生成规则）

不做（本次实现阶段仍不做）：

- 交互式 shell / PTY
- 端口转发 / SFTP / scp
- 复杂代理链路（ProxyCommand）

## Acceptance（硬口径）

1. `ssh exec`：stdin 为空必须失败，`error_code="InvalidArgs"`。  
2. `ssh exec`：unknown host 且未传 `--trust-host-key` 必须失败，`error_code="HostKeyUntrusted"`。  
3. `ssh exec --trust-host-key`：成功路径必须写入 `.agents/workspace/ssh/known_hosts`（文件存在且包含该 host:port 记录）。  
4. `ssh exec --out X`：落盘 `.agents/<X>`，且 tool output 的 `artifacts[]` 包含 `.agents/<X>`。  
5. secrets：argv 出现 `--password`/`--passphrase`/`--key`/`SSH_PASSWORD=` 等必须失败，`error_code="SensitiveArgv"`。  
6. 路径约束：`--out` 必须在 `.agents/` 内；绝对路径与 `..` 必须失败（`PathEscapesAgentsRoot` 或 `InvalidArgs`）。  
7. builtin skill：
   - `app/src/main/assets/builtin_skills/ssh-cli/SKILL.md` 存在且内容包含最小闭环命令示例；
   - App 初始化会把该 skill 安装到 `.agents/skills/ssh-cli/`；
   - 若 `.agents/skills/ssh-cli/secrets/.env` 缺失，启动后会从 `env.example` 生成（不覆盖已有 `.env`）。  
8. 反作弊条款（必须）：
   - 单测必须使用 fake transport/client 覆盖至少：`HostKeyUntrusted` / `SensitiveArgv` / `PathEscapesAgentsRoot` / `RemoteNonZeroExit`（或等价失败路径），不能只注册命令返回假数据就宣称完成。

## Files

- 命令注册：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalCommands.kt`
- 命令实现：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/ssh/SshCommand.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/ssh/*`
- 单测：
  - `app/src/test/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecToolTest.kt`
- builtin skill：
  - `app/src/main/assets/builtin_skills/ssh-cli/SKILL.md`
  - `app/src/main/assets/builtin_skills/ssh-cli/secrets/env.example`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`

## Steps（Strict / TDD）

1) TDD Red：在 `TerminalExecToolTest.kt` 增加 `ssh exec` 的成功/失败/越界/敏感 argv 用例，并运行到红  
2) TDD Green：实现 `ssh` 命令 + secrets loader + known_hosts 落盘 + `--out` artifacts，并运行到绿  
3) Refactor：抽取公共解析/落盘小工具（仍绿）  
4) Skill：新增 `builtin_skills/ssh-cli` + `AgentsWorkspace` 安装与 secrets 模板生成，并再次跑单测  
5) Verify：`.\gradlew.bat :app:testDebugUnitTest` 全绿  

## Risks

- SSH 库（JSch）在 Android 的算法/密钥支持不足：必要时走 ECN 切库，但必须保持 CLI 协议稳定。
- 输出过大：避免 stdout/stderr 截断导致误判，`--out` 写盘必须稳定可用。

