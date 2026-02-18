# v15 Plan：JGit Pseudo Git CLI（远程能力）

## Goal

实现 `terminal_exec git` 的远程能力（ls-remote/clone/fetch/pull/push），并通过 `--confirm`、https-only、stdin 授权等手段保证安全与可审计性。

## PRD Trace

- PRD-0015：REQ-0015-001 ~ REQ-0015-004

## Scope

做：
- 扩展 `GitCommand` 支持：
  - `git ls-remote`
  - `git clone`
  - `git fetch`
  - `git pull`
  - `git push`
- 安全策略：
  - 远程命令必须 `--confirm`
  - `--remote` 仅允许 https 且禁止 userinfo
  - `--auth` 仅允许 stdin（token/basic）
  - 提供 `--local-remote` 用于离线/单测闭环（路径仍受 `.agents` 根约束）
- 新增内置 skill：`jgit-remote`（写清楚 stdin 授权与 confirm 门禁）
- 单测覆盖：ConfirmRequired / InvalidRemoteUrl / local-remote 端到端闭环

不做：
- SSH（后续版本）
- 复杂凭据 UI（后续版本）

## Acceptance（硬口径）

1. 所有 `--remote` 网络命令缺失 `--confirm` 必拒绝（ConfirmRequired）。
2. 包含 userinfo 的 https URL 必拒绝（InvalidRemoteUrl）。
3. local-remote 单测闭环全绿（clone → commit → push → pull）。
4. `.\gradlew.bat :app:testDebugUnitTest` exit code=0

## Files

- `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalCommands.kt`
- `app/src/test/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecToolTest.kt`
- `app/src/main/assets/builtin_skills/jgit-remote/SKILL.md`
- `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`

## Steps（Strict）

1) TDD Red：先写 ConfirmRequired / InvalidRemoteUrl / local-remote 闭环测试
2) TDD Green：实现远程命令与安全校验
3) Refactor：抽取 URL 校验与 stdin credentials 解析
4) Verify：`.\gradlew.bat :app:testDebugUnitTest`

