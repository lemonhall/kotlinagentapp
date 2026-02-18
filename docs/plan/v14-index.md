# v14 Index：JGit Pseudo Git CLI（本地常用命令）

日期：2026-02-18

## Vision（引用）

- PRD：`docs/prd/PRD-0014-jgit-local-common.md`
- 本轮目标：在 v13 的 `git init/status/add/commit/log` 基础上，尽快补齐本地常用命令（branch/checkout/diff/show/reset/stash），并保持可测试、可审计、输出可控。

## Milestones

### M1：本地常用命令补齐

- PRD Trace：
  - PRD-0011：REQ-0011-001 / REQ-0011-002 / REQ-0011-010
  - PRD-0014：REQ-0014-001 ~ REQ-0014-004 / REQ-0014-010
- DoD（硬口径）：
  - `git branch/checkout`：支持创建与切换分支（单测断言 current branch）；
  - `git diff/show`：支持 `--patch`，超长必须 artifacts 落盘；
  - `git reset`：支持 soft/mixed/hard（单测验证回退）；
  - `git stash push/list/pop`：最小闭环可用（push 后 clean，pop 后恢复变更）；
  - Tests：`.\gradlew.bat :app:testDebugUnitTest` exit code=0
- Plan：`docs/plan/v14-jgit-local-common.md`

## Plan Index

- `docs/plan/v14-jgit-local-common.md`

## ECN Index

- （本轮无）

