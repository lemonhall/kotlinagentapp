# v19 Plan：QQMail CLI（IMAP/SMTP）

## Goal

实现 PRD-0019 的 `qqmail` 白名单命令（读/发），并把凭据读取策略、发送 confirm、输出脱敏与大输出落盘固化成测试与验收口径，避免“能连通但不安全/不可测”的假交付。

## PRD Trace

- PRD-0019：REQ-0019-001 / REQ-0019-002 / REQ-0019-003
- PRD-0019：REQ-0019-010 / REQ-0019-011 / REQ-0019-012
- PRD-0019：REQ-0019-020

## Scope

做：
- 新增命令：
  - `qqmail fetch`
  - `qqmail send`
- `.env` 凭据读取（仅本地）：
  - 默认读取 `.agents/skills/qqmail-cli/secrets/.env`
- 安全策略（必须）：
  - `send` 强制 `--confirm`
  - 默认摘要输出；完整输出走 `--out` artifacts
  - 禁止 argv 传入授权码/密码

不做（v19）：
- 附件收发、HTML 邮件、复杂过滤规则
- 邮件移动/删除/标记已读等状态写操作
- UI 交互式授权引导（只返回可解释错误，UI 后续版本再补）

## Acceptance（硬口径）

1. `qqmail send` 缺失 `--confirm` 必拒绝（`ConfirmRequired`）。
2. 凭据只从 `.env` 读取；argv 不允许出现密码/授权码字段（防审计泄露）。
3. `qqmail fetch/qqmail send` 支持 `--out`，并通过 `artifacts[]` 返回 `.agents/<out_path>` 引用。
4. `qqmail fetch` 落盘到 `.agents/workspace/qqmail/inbox/`（Markdown），按 `message_id` 去重可重复运行。
5. `.\gradlew.bat :app:testDebugUnitTest` exit code=0。

## Files（规划：遵守 paw-cli-add-workflow）

- 注册表（只注册，不写实现）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalCommands.kt`
- 命令实现（拆分目录）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/qqmail/QqMailCommand.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/mail/*`（可选：IMAP/SMTP 客户端 + .env loader + 落盘器）
- 单测（Robolectric）：
  - `app/src/test/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecToolTest.kt`
- 内置 skill（让 Agent 能按说明书调用）：
  - `app/src/main/assets/builtin_skills/qqmail-cli/SKILL.md`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`

## Steps（Strict）

> 本文件为 v19 施工计划文档；本次任务“只落文档”不写实现。

1) Analysis：锁定 `.env` 读取位置与安全约束（不进 argv/audit），确定最小字段集与脱敏策略  
2) TDD Red：为 `--confirm` 门禁、缺凭据/鉴权失败、`--out` artifacts、落盘与去重写测试并跑红  
3) TDD Green：实现 `qqmail` 命令（IMAP/SMTP + `.env` loader + 输出契约）并跑绿  
4) Refactor：抽取共享落盘/摘要输出工具函数，保持命令文件职责清晰  
5) 接入：注册命令 + 安装内置 skill  
6) Verify：`.\gradlew.bat :app:testDebugUnitTest`

## Risks

- 安全风险：若把授权码写进 argv 或 stdout，会被审计落盘 → 必须硬禁止 argv 传入敏感字段。
- 可测性：真实 IMAP/SMTP 不适合单测直连 → 需要可替换 client + fake（或协议层 adapter）保证 Robolectric 可跑。
- 网络环境：端口阻断/证书/运营商限制 → 错误必须可解释并可重试；必要时提供用户侧排障指引。

