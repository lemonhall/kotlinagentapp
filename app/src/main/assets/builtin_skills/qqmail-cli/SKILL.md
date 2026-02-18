---
name: qqmail-cli
description: 通过 `terminal_exec` 提供 QQ 邮箱 IMAP/SMTP 的受控读/发：fetch 落盘 Markdown，send 强制 --confirm，支持 --out artifacts，并从技能目录 secrets/.env 读取授权码。
---

# qqmail-cli（QQ Mail / Pseudo CLI）

## Goal

在 App 内（`.agents` 工作区）用 `terminal_exec` 执行白名单命令 `qqmail`，完成：

- 读取：IMAP 拉取最新邮件并落盘到 `workspace/qqmail/inbox/`（Markdown）
- 发送：SMTP SSL 发送纯文本邮件（写操作必须 `--confirm`）
- 大输出：`--out` 落盘 JSON/回执，并在 tool output 的 `artifacts[]` 返回 `.agents/<path>` 引用

## Secrets（本地化）

把凭据放到（本机，不要提交 git）：

- `.agents/skills/qqmail-cli/secrets/.env`

格式（示例）：

```dotenv
EMAIL_ADDRESS=your@qq.com
EMAIL_PASSWORD=xxxxxx_app_auth_code_xxxxxx
SMTP_SERVER=smtp.qq.com
SMTP_PORT=465
IMAP_SERVER=imap.qq.com
IMAP_PORT=993
```

## Commands（v19）

### 1) fetch（可选 --out）

使用工具 `terminal_exec` 执行：

- `qqmail fetch --folder INBOX --limit 20`

（可选）落盘完整清单：

- `qqmail fetch --folder INBOX --limit 50 --out artifacts/qqmail/fetch.json`

期望：
- `exit_code=0`
- `result.ok=true`
- 默认 stdout 只包含摘要（数量、落盘路径等）
- 若使用 `--out`：tool output 的 `artifacts[]` 包含 `.agents/artifacts/qqmail/fetch.json`

### 2) send（必须 --confirm；建议用 stdin 传正文）

使用工具 `terminal_exec` 执行（短正文）：

- `qqmail send --to "someone@example.com" --subject "hi" --body "hello" --confirm`

或（长正文）用 tool 的 `stdin`：

- 命令：`qqmail send --to "someone@example.com" --subject "hi" --body-stdin --confirm --out artifacts/qqmail/send.json`
- stdin：你要发送的正文文本

期望：
- `exit_code=0`
- `result.ok=true`
- 若使用 `--out`：tool output 的 `artifacts[]` 包含 `.agents/artifacts/qqmail/send.json`

## Safety / Guardrails

- 禁止通过 argv 传入授权码/密码（避免审计落盘泄露）。
- `qqmail send` 缺 `--confirm` 必须失败，`error_code="ConfirmRequired"`。
- stdout/result 默认只包含最小必要字段；完整内容走 `--out` 落盘。

## Rules

- 必须实际调用 `terminal_exec`，不要臆造 stdout/result/artifacts。
- `terminal_exec` 不支持 `;` / `&&` / `|` / 重定向；所有命令必须单行。
- 若工具返回 `exit_code!=0` 或包含 `error_code`，直接把错误信息说明给用户并停止。

