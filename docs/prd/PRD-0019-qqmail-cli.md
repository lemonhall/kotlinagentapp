# PRD-0019：QQ 邮箱 CLI（IMAP/SMTP）Pseudo Terminal Commands

日期：2026-02-18  
定位：在 `terminal_exec` 的“白名单 pseudo CLI（无 shell / 无外部进程）”框架下，为 QQ 邮箱提供**受控**的收取（IMAP）与发送（SMTP）能力，并把凭据读取、输出脱敏、大输出落盘、发送确认门禁写死在验收与测试里。

> 说明：本 PRD 的“QQ 邮箱”默认指 `imap.qq.com` / `smtp.qq.com`，并且使用 **QQ 邮箱授权码**（非登录密码）。

## Vision

让 Agent 与人类都能通过 `terminal_exec` 以可审计、可控风险的方式完成：

- 拉取指定邮箱文件夹（默认 `INBOX`）的最新邮件，落盘为 Markdown（带 YAML 头部元信息）
- 发送纯文本邮件（SMTP SSL），并（可选）落盘发送记录为 Markdown

并且：

- 凭据只从本地 `.env` 读取，不允许通过 argv 传入密码/授权码（避免审计落盘泄露）
- 默认脱敏：stdout/result 只输出摘要，不直接回显邮件正文/长内容
- 大输出（例如完整邮件列表 JSON、原始 RFC822、或全文 Markdown）通过 `--out` 落盘并以 artifacts 引用
- 发送操作必须显式 `--confirm`，否则拒绝（防误发）

## Non-Goals（v19）

- 不做：HTML 发送、附件收发、内嵌图片、富文本解析
- 不做：OAuth 登录、扫码登录、第三方 app 授权流程 UI
- 不做：复杂邮件规则/标签/移动/删除/标记已读（只做“读 + 发”最小闭环）
- 不做：对外网代理/证书注入等“全局网络方案”（仅在错误信息中给出可解释提示）

## Dependencies（Network）

- IMAP over SSL：`imap.qq.com:993`
- SMTP over SSL：`smtp.qq.com:465`
- Android 端网络权限：`android.permission.INTERNET`（已具备）

> 注意：不同网络环境可能对 465/993 端口访问有差异；失败必须返回可解释错误（见 Requirements）。

## Secrets / Credentials

### `.env` 格式（示例）

> 禁止把真实授权码提交到 git；`.env` 必须本地化存放。

```dotenv
# QQ邮箱配置
# 注意：QQ邮箱需要使用授权码而不是密码
# 获取授权码：QQ邮箱设置 -> 账户 -> POP3/IMAP/SMTP服务 -> 生成授权码

EMAIL_ADDRESS=your@qq.com
EMAIL_PASSWORD=xxxxxx_app_auth_code_xxxxxx
SMTP_SERVER=smtp.qq.com
SMTP_PORT=465
IMAP_SERVER=imap.qq.com
IMAP_PORT=993
```

### 存放位置约定（v19）

默认读取：

- `.agents/skills/qqmail-cli/secrets/.env`

理由：技能目录天然“就近存放本技能相关凭据”，且不进入仓库。

## Project Rules（实现必须遵守：来自 paw-cli-add-workflow）

- `TerminalCommands.kt` 只能做“命令注册表/默认 registry 构建”，禁止堆实现逻辑。
- 顶层命令必须独立目录：
  - `qqmail`：`app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/qqmail/*`
- `TerminalExecTool` 的 registry 初始化逻辑不改；只在 `TerminalCommands.defaultRegistry(...)` 注册新增命令。

## Command Set（v19）

统一约束：

- 所有命令输出同时提供：`stdout`（摘要）+ `result`（结构化）+（必要时）`artifacts[]`（落盘大输出）。
- **任何会修改外部世界状态的命令必须显式 `--confirm`**（本 PRD 仅 send 属于写操作）。
- stdout/result 默认脱敏：不直接输出邮箱授权码，不回显大段正文；必要时落盘并返回 artifact。

### qqmail

#### `qqmail fetch`

- 命令：
  - `qqmail fetch [--folder INBOX] [--limit <n>] [--out <path>]`
- 行为：
  - 连接 IMAP，拉取指定 folder 的最新邮件（默认 20）
  - 将每封邮件落盘为 Markdown（YAML 头：subject/from/to/date/message_id 等；正文写入 Markdown body）
  - 默认保存到 `.agents/workspace/qqmail/inbox/`（按日期+主题生成安全文件名）
  - 去重策略：优先用 `message_id` 去重；其次文件名存在则跳过
  - 若提供 `--out`：写入完整 JSON 清单（含落盘文件路径）并返回 artifact 引用
- result（最小字段）：
  - `ok: boolean`
  - `command: "qqmail fetch"`
  - `folder: string`
  - `fetched: number`
  - `skipped: number`
  - `written_files: [{path, message_id, subject, from, date}]`（可截断）
  - `out?: string`

#### `qqmail send`

- 命令：
  - `qqmail send --to <addr> --subject <text> [--body <text> | --body-stdin] --confirm [--out <path>]`
- 行为：
  - 通过 SMTP SSL 发送纯文本邮件
  - `--body-stdin`：从 tool 的 `stdin` 读取正文（避免在 argv 中暴露长正文）
  - 默认将发送记录落盘为 Markdown 到 `.agents/workspace/qqmail/sent/`
  - 若提供 `--out`：将发送记录的完整 Markdown/JSON 回执落盘并返回 artifact
- result（最小字段）：
  - `ok`
  - `command: "qqmail send"`
  - `to`
  - `subject`
  - `saved_path`（发送记录落盘路径）

## Safety（必须）

1) 凭据不出现在 argv / stdout / audit  
- 禁止 `--password/--auth` 等通过 argv 传入；只允许从 `.env` 读取。
- `terminal_exec` 审计会记录 `command/argv/stdout/stderr`，因此**任何敏感信息不得进入这些字段**。

2) 发送确认门禁  
- `qqmail send` 缺 `--confirm` 必拒绝（`ConfirmRequired`）。

3) 输出与隐私  
- stdout/result 仅输出摘要（主题、发件人、日期、落盘路径、数量等）。
- 完整正文/完整列表必须 `--out` 落盘并经 artifacts 引用返回。

## Acceptance（v19 文档口径）

1. `terminal_exec` 新增顶层命令 `qqmail`，并实现 `fetch/send`。
2. `send` 缺 `--confirm` 必须拒绝（`ConfirmRequired`）。
3. 凭据只能从 `.agents/skills/qqmail-cli/secrets/.env` 读取，不允许通过 argv 传入。
4. `fetch` 与 `send` 支持 `--out` 落盘，并通过 `artifacts[]` 返回 `.agents/<out_path>` 引用。
5. `.\gradlew.bat :app:testDebugUnitTest` exit code=0（覆盖：缺凭据、鉴权失败、`--confirm` 门禁、`--out` artifacts、落盘文件存在性）。

## Requirements（Req IDs）

### A. 命令面

- REQ-0019-001：新增 `qqmail fetch` 并返回结构化 `result`，支持落盘为 Markdown。
- REQ-0019-002：新增 `qqmail send`（SMTP SSL）并返回结构化 `result`，支持 `--body-stdin`。
- REQ-0019-003：本地落盘目录约定：`workspace/qqmail/inbox|sent`，文件名安全化且可重复运行不重复写入。

### B. 安全面

- REQ-0019-010：凭据只从 `.env` 读取；禁止 argv 传入敏感字段；stdout/result/audit 不包含授权码。
- REQ-0019-011：发送门禁：`qqmail send` 缺 `--confirm` 返回 `ConfirmRequired`。
- REQ-0019-012：输出脱敏：默认仅摘要；完整输出必须 `--out` 落盘 + artifacts 引用。

### C. 工程规矩

- REQ-0019-020：命令实现必须放在独立目录 `commands/qqmail/*`；`TerminalCommands.kt` 只做注册表。

