# v15 Index：JGit Pseudo Git CLI（远程能力）

日期：2026-02-18

## Vision（引用）

- PRD：`docs/prd/PRD-0015-jgit-remote-ops.md`
- 本轮目标：补齐远程常用能力（ls-remote/clone/fetch/pull/push），并引入强安全策略（--confirm + https-only + stdin 凭据 + 审计不泄密）。

## Milestones

### M1：远程能力最小闭环（含 local-remote 单测）

- PRD Trace：
  - PRD-0011：REQ-0011-001 / REQ-0011-002 / REQ-0011-010
  - PRD-0015：REQ-0015-001 ~ REQ-0015-004
- DoD（硬口径）：
  - 所有远程命令必须 `--confirm`；
  - 远程 URL 仅支持 `https` 且禁止 userinfo；
  - 凭据仅来自 `stdin`（不进入 argv/审计），错误输出不得泄露 stdin；
  - `local-remote` 端到端单测闭环可跑（clone → commit → push → pull）；
  - Tests：`.\gradlew.bat :app:testDebugUnitTest` exit code=0
- Plan：`docs/plan/v15-jgit-remote-ops.md`

## Plan Index

- `docs/plan/v15-jgit-remote-ops.md`

## ECN Index

- （本轮无）

