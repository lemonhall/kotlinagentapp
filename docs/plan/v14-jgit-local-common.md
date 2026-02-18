# v14 Plan：JGit Pseudo Git CLI（本地常用命令）

## Goal

补齐 v14 本地常用 git 命令（branch/checkout/diff/show/reset/stash），并保持：

- 输出可控（patch 超长走 artifacts）
- 路径边界安全（`.agents` 根内）
- 单测覆盖关键 happy path

## PRD Trace

- PRD-0014：REQ-0014-001 ~ REQ-0014-004 / REQ-0014-010

## Scope

做：
- 扩展 `GitCommand` 支持 `branch/checkout/diff/show/reset/stash`
- 更新内置 skill `jgit-cli` 覆盖 v14 命令
- 单测覆盖 v14 最小闭环

不做：
- 远程能力（v15）

## Acceptance（硬口径）

1. `git checkout --create` 能创建并切换分支；`git branch` 输出包含 current。
2. `git show --commit HEAD --patch` 输出或 artifact 包含 commit message。
3. `git diff --from HEAD~1 --to HEAD --patch` 输出或 artifact 非空。
4. `git reset --mode hard --to HEAD~1` 后，`git log --max 1` 不再是最新提交。
5. `git stash push/list/pop` 可用。
6. `.\gradlew.bat :app:testDebugUnitTest` exit code=0

## Files

- `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalCommands.kt`
- `app/src/test/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecToolTest.kt`
- `app/src/main/assets/builtin_skills/jgit-cli/SKILL.md`

## Steps（Strict）

1) TDD Red：为 branch/checkout/diff/show/reset/stash 增加失败测试与 happy path 测试
2) TDD Green：实现对应命令
3) Refactor：抽取重复参数解析与 artifacts 写入
4) Verify：`.\gradlew.bat :app:testDebugUnitTest`

