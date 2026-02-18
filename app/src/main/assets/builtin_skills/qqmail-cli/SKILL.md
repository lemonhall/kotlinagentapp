---
name: qqmail-cli
description: 通过 `terminal_exec` 提供 QQ 邮箱 IMAP/SMTP 的 `qqmail fetch/send`（dotenv 凭据、send 必须 --confirm、默认脱敏、--out 落盘 artifacts）。
---

# qqmail-cli（QQMail IMAP/SMTP / Pseudo CLI）

## Goal

在 App 内 `.agents` 工作区，通过 `terminal_exec` 使用白名单命令 `qqmail` 完成：

- fetch：从 IMAP 拉取最新邮件，落盘为 Markdown（`.agents/workspace/qqmail/inbox/`），并支持 `--out` 落盘完整 JSON 清单
- send：通过 SMTP 发送纯文本邮件（写操作必须 `--confirm`），支持 `--body-stdin`，并落盘发送记录到 `.agents/workspace/qqmail/sent/`

## Secrets（本地 .env）

凭据只允许从以下路径读取（不要通过 argv 传入授权码/密码）：

- `.agents/skills/qqmail-cli/secrets/.env`

初始化规则：

- App 启动时如果发现 `.agents/skills/qqmail-cli/secrets/.env` 不存在，会从内置模板自动生成一份（便于你在 Files 页签直接编辑）。

`.env` 示例（不要提交真实授权码）：

```dotenv
EMAIL_ADDRESS=your@qq.com
EMAIL_PASSWORD=your_qqmail_app_auth_code
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

- `qqmail fetch --folder INBOX --limit 20 --out artifacts/qqmail/inbox.json`

期望：
- `exit_code=0`
- `result.ok=true`
- 邮件 Markdown 落盘到 `.agents/workspace/qqmail/inbox/`
- 若使用 `--out`：tool output 的 `artifacts[]` 包含 `.agents/artifacts/qqmail/inbox.json`

### 2) send（必须 --confirm，建议 --body-stdin）

使用工具 `terminal_exec` 执行（建议使用 `--body-stdin`，避免正文出现在 argv/audit）：

- 命令：`qqmail send --to someone@example.com --subject "Hi" --body-stdin --confirm --out artifacts/qqmail/send.json`
- stdin：邮件正文（纯文本）

期望：
- `exit_code=0`
- `result.ok=true`
- 发送记录落盘到 `.agents/workspace/qqmail/sent/`
- 若使用 `--out`：tool output 的 `artifacts[]` 包含 `.agents/artifacts/qqmail/send.json`

### 3) 安全门禁（必须）

- `qqmail send` 缺 `--confirm`：必须失败，`error_code="ConfirmRequired"`
- 缺 `.env` 或缺字段：必须失败，`error_code="MissingCredentials"`

## Rules

- 必须实际调用 `terminal_exec`，不要臆造 stdout/result/artifacts。
- `terminal_exec` 不支持 `;` / `&&` / `|` / 重定向；所有命令必须单行。
- 若工具返回 `exit_code!=0` 或包含 `error_code`，直接把错误信息说明给用户并停止。
