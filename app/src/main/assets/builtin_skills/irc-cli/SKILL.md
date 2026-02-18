---
name: irc-cli
description: 通过 `terminal_exec` 提供 IRC 的 `irc status/send/pull`（dotenv 凭据、NICK<=9、send 非默认目标需 --confirm、pull cursor 去重 + 截断）。
---

# irc-cli（IRC / Pseudo CLI）

## Goal

在 App 内 `.agents` 工作区，通过 `terminal_exec` 使用白名单命令 `irc` 完成：

- `irc status`：查看当前 session 的 IRC 状态（不泄露 secret）
- `irc send`：发送消息（写操作门禁：非默认目标必须 `--confirm`；正文建议用 `--text-stdin`）
- `irc pull`：拉取新消息（按频道 cursor 去重，默认只返回上次 pull 以来的新消息，并执行截断策略）

## Secrets（本地 .env）

凭据只允许从以下路径读取（不要通过 argv 传入 password/key）：

- `.agents/skills/irc-cli/secrets/.env`

初始化规则：

- App 启动时如果发现 `.agents/skills/irc-cli/secrets/.env` 不存在，会从内置模板自动生成一份（便于你在 Files 页签直接编辑）。
- 模板文件位于同目录：`.agents/skills/irc-cli/secrets/env.example`（只读参考，不要改它；请改 `.env`）。

## Commands（v28）

### 1) status

使用工具 `terminal_exec` 执行：

- `irc status`

期望：
- `exit_code=0`
- `result.command="irc status"`
- `result.state` 为 `not_initialized|connecting|connected|joined|reconnecting|disconnected|error`

### 2) send（建议 --text-stdin；非默认目标必须 --confirm）

发送到默认频道（不需要 `--confirm`）：

- 命令：`irc send --text-stdin`
- stdin：消息正文（纯文本）

发送到非默认目标（例如私聊或其他频道，必须 `--confirm`）：

- 命令：`irc send --to "#other" --text-stdin --confirm`
- stdin：消息正文（纯文本）

期望：
- `exit_code=0`
- `result.command="irc send"`
- `result.session_bound=true`

### 3) pull（cursor 去重 + 截断）

默认频道拉取最近新消息（只返回上次成功 pull 以来的新消息）：

- `irc pull --limit 20`

只查看不推进 cursor：

- `irc pull --limit 20 --peek`

期望：
- `exit_code=0`
- `result.command="irc pull"`
- `result.messages` 为数组（每条含 `id/ts/nick/text`）
- 可能出现 `result.truncated=true`（截断策略：head + marker + tail）

## Rules

- 必须实际调用 `terminal_exec`，不要臆造 stdout/result/artifacts。
- `terminal_exec` 不支持 `;` / `&&` / `|` / 重定向；所有命令必须单行。
- 若工具返回 `exit_code!=0` 或包含 `error_code`，直接把错误信息说明给用户并停止。

