# v13 Index：JGit Pseudo Git CLI（git init/status/add/commit/log）

日期：2026-02-18

## Vision（引用）

- PRD：`docs/prd/PRD-0013-jgit-pseudo-git-cli.md`
- 本轮目标：引入 JGit（Java 版 Git 实现）并将其 CLI 化为 `terminal_exec git ...` 白名单命令，优先打通本地核心工作流（init/status/add/commit/log）+ 单测验证。

## Milestones

### M1：`terminal_exec git init/status/add/commit/log` 最小闭环

- PRD Trace：
  - PRD-0011：REQ-0011-001 / REQ-0011-002 / REQ-0011-010
  - PRD-0013：REQ-0013-001 ~ REQ-0013-007
- DoD（硬口径）：
  - `git init --dir <path>`：仅允许 `.agents` 根内路径，成功返回 `exit_code=0`；
  - `git status --repo <path>`：返回结构化 `result.counts.*`，且 untracked 场景可被单测断言；
  - `git add --repo <path> --all` + `git commit --repo <path> --message "<msg>"`：commit 后 `git status` clean；
  - `git log --repo <path> --max 1`：返回包含该 commit 的结构化记录；
  - 安全：路径逃逸（含 `..` 或 canonical 出界）必拒绝且错误可解释；
  - Skill：新增内置 skill `jgit-cli`，文档包含可执行示例（init → 写文件 → add → commit → status → log）；
  - Tests：`.\gradlew.bat :app:testDebugUnitTest` exit code=0
- Plan：`docs/plan/v13-jgit-pseudo-git-cli.md`

## Plan Index

- `docs/plan/v13-jgit-pseudo-git-cli.md`

## ECN Index

- （本轮无）
