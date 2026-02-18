---
name: ssh-cli
description: 通过 `terminal_exec` 提供 SSH 客户端的 `ssh exec`（远程命令走 stdin、TOFU known_hosts 护栏、dotenv secrets、--out artifacts 落盘）。
---

# ssh-cli（SSH Client / Pseudo CLI）

## Goal

在 App 内 `.agents` 工作区，通过 `terminal_exec` 使用白名单命令 `ssh` 完成一次性远程命令执行（非交互式）：

- 远程命令文本 **必须** 通过 `stdin` 传入（避免触发 `terminal_exec` 的 banned tokens）
- 首次连接必须显式 `--trust-host-key`（TOFU 护栏），并写入 `.agents/workspace/ssh/known_hosts`
- 大输出使用 `--out` 落盘到 `.agents/`，并通过 tool output 的 `artifacts[]` 引用

## Secrets（本地 .env）

认证信息只允许从以下路径读取（不要通过 argv 传入密码/私钥/口令）：

- `.agents/skills/ssh-cli/secrets/.env`

初始化规则：

- App 启动时如果发现 `.agents/skills/ssh-cli/secrets/.env` 不存在，会从内置模板自动生成一份（便于你在 Files 页签直接编辑）。
- 模板文件位于同目录：`.agents/skills/ssh-cli/secrets/env.example`（只读参考，不要改它；请改 `.env`）。

## Commands（v26）

### 1) exec（建议 --out）

使用工具 `terminal_exec` 执行：

- 命令：`ssh exec --host 192.0.2.10 --port 22 --user root --trust-host-key --out artifacts/ssh/exec/last.json`
- stdin：远程命令（可包含 `;` / `|` / `>` 等），例如：
  - `uname -a ; id ; df -h`

期望：

- `exit_code=0`
- `result.ok=true`
- known_hosts 落盘：`.agents/workspace/ssh/known_hosts`
- `artifacts[]` 至少包含：`.agents/artifacts/ssh/exec/last.json`

### 2) 安全门禁（必须）

- stdin 为空：必须失败，`error_code="InvalidArgs"`
- unknown host 且缺 `--trust-host-key`：必须失败，`error_code="HostKeyUntrusted"`
- argv 出现 `--password`/`--passphrase`/`--key`/`SSH_PASSWORD=` 等：必须失败，`error_code="SensitiveArgv"`
- 远程 exit status != 0：必须失败，`error_code="RemoteNonZeroExit"` 并返回 `result.remote_exit_status`

## Rules

- 必须实际调用 `terminal_exec`，不要臆造 stdout/result/artifacts。
- `terminal_exec` 不支持 `;` / `&&` / `|` / 重定向；所有命令必须是单行（远程命令走 stdin）。
- 若工具返回 `exit_code!=0` 或包含 `error_code`，直接把错误信息说明给用户并停止。

