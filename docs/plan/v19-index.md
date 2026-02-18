# v19 Index：QQMail CLI（IMAP/SMTP）

日期：2026-02-18

## Vision（引用）

- PRD：`docs/prd/PRD-0019-qqmail-cli.md`
- 本轮目标：把 QQ 邮箱 IMAP/SMTP 读/发能力以 `terminal_exec` 白名单命令暴露出来，并把凭据读取（.env）、发送 confirm、输出脱敏与大输出落盘写死在验收与测试里。

## Milestones

### M1：读取（IMAP fetch → Markdown 落盘）

- PRD Trace：
  - PRD-0019：REQ-0019-001 / REQ-0019-003 / REQ-0019-010 / REQ-0019-012 / REQ-0019-020
- DoD（硬口径）：
  - `qqmail fetch` 可用，缺凭据返回可解释错误（例如 `MissingCredentials`）；
  - 默认仅输出摘要；完整清单必须 `--out` 落盘并通过 `artifacts[]` 返回引用；
  - 落盘目录：`.agents/workspace/qqmail/inbox/`，可重复运行不重复写入（message_id 去重）；
  - Tests：`.\gradlew.bat :app:testDebugUnitTest` exit code=0
- Plan：`docs/plan/v19-qqmail-cli.md`

### M2：发送（SMTP send + confirm 门禁）

- PRD Trace：
  - PRD-0019：REQ-0019-002 / REQ-0019-010 / REQ-0019-011 / REQ-0019-012
- DoD（硬口径）：
  - `qqmail send` 缺 `--confirm` 必拒绝（`ConfirmRequired`）；
  - 发送正文支持 `--body-stdin`（避免 argv 里塞长正文）；
  - stdout/result 不包含授权码；
  - Tests：`.\gradlew.bat :app:testDebugUnitTest` exit code=0

## Plan Index

- `docs/plan/v19-qqmail-cli.md`

## ECN Index

- （本轮无）

