# v28 Index：IRC CLI（会话绑定长连接 + 收发 + 指示器）

日期：2026-02-18

## Vision（引用）

- PRD：`docs/prd/PRD-0028-irc-cli.md`
- 本轮目标（v28）：在 `terminal_exec` 下交付 `irc` 白名单命令的最小闭环（`status/send/pull`），并把“长连接与 Agent session 绑定、NICK<=9、confirm 门禁、pull 去重与截断、secrets 只读 .env、Chat 顶部 IRC 三色灯指示器”固化成验收与单测。

## Milestones

### M1：`irc status/send` 最小闭环

- PRD Trace：
  - PRD-0028：REQ-0028-001 / REQ-0028-002
  - PRD-0028：REQ-0028-010 / REQ-0028-011
  - PRD-0028：REQ-0028-020 / REQ-0028-021 / REQ-0028-022
  - PRD-0028：REQ-0028-030
- DoD（硬口径）：
  - `terminal_exec` 可执行：`irc status` / `irc send ...` / `irc pull ...`；
  - 同一 Agent session 内多次 `irc send` 复用连接（不重复建连）；
  - `IRC_NICK` > 9 字符被拒绝（`NickTooLong`）；
  - secrets 只从 `.agents/skills/irc-cli/secrets/.env` 读取，不进入 argv/stdout/audit；
  - `irc send` 非默认目标缺 `--confirm` 被拒绝（`ConfirmRequired`）；
  - `irc pull` 默认只返回“上次 pull 以来”的新消息（**按频道 cursor 去重**），并执行截断策略（head/marker/tail）；
  - Chat 页签右上角存在 `IRC` 三色灯指示器，能反映红/黄/绿状态；
  - （可选）`IRC_AUTO_FORWARD_TO_AGENT=1` 才允许自动递送，默认关闭；
  - Verify：`.\gradlew.bat :app:testDebugUnitTest` 通过；
  - 提交与推送：`git status --porcelain=v1` 为空。

## Plan Index

- `docs/plan/v28-irc-cli.md`

## ECN Index

- （本轮无）
