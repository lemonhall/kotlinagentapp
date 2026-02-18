# v13 Plan：JGit Pseudo Git CLI（git init/status）

## Goal

引入 JGit，并以 `terminal_exec` 的白名单命令形式提供本地核心 git 工作流：`git init/status/add/commit/log`，使 Agent 能在 `.agents/workspace/...` 内创建仓库、写入文件、提交变更并查询状态/日志；同时确保路径边界安全与单测覆盖。

## PRD Trace

- PRD-0011：REQ-0011-001 / REQ-0011-002 / REQ-0011-010
- PRD-0013：REQ-0013-001 ~ REQ-0013-004

## Scope

做：
- 增加依赖：JGit（纯 Java 实现）
- 新增 `terminal_exec` 白名单命令：`git init`、`git status`、`git add`、`git commit`、`git log`
- 路径安全：`--dir/--repo` 必须限制在 `.agents` 根目录内（canonical 校验）
- 新增内置 skill：`jgit-cli`（写清楚调用方式与可验证期望）
- 单测：Robolectric 覆盖 init/status 与路径逃逸拒绝

不做（v13 范围外）：
- v13 不做网络能力（clone/fetch/push/pull/ls-remote），后续版本补齐
- v13 不做更广的本地命令集（branch/checkout/diff/reset/stash 等），后续版本补齐
- 不做 UI 终端面板（先用 ToolResult 验证闭环）

## Acceptance（硬口径）

1. `terminal_exec` 执行 `git init --dir workspace/jgit-demo`：
   - `exit_code=0`
   - `result.ok=true`
2. 在 `workspace/jgit-demo` 下写入一个新文件后，执行 `git status --repo workspace/jgit-demo`：
   - `exit_code=0`
   - `result.counts.untracked >= 1`
3. 执行 `git add --repo workspace/jgit-demo --all` + `git commit --repo workspace/jgit-demo --message "init"` 后：
   - 再次 `git status` 返回 `result.is_clean=true`
   - `git log --repo workspace/jgit-demo --max 1` 返回包含 commit 的结构化记录
4. `git status` 对非 git 目录必须失败且可解释（例如 `error_code="NotAGitRepo"`）。
5. `--repo/--dir` 传入 `../` 或 canonical 出界路径必须失败且可解释（例如 `error_code="PathEscapesAgentsRoot"`）。
6. `.\gradlew.bat :app:testDebugUnitTest` exit code=0

## Files

- `gradle/libs.versions.toml`
- `app/build.gradle.kts`
- `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecTool.kt`
- `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalCommands.kt`
- `app/src/test/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecToolTest.kt`
- `app/src/main/assets/builtin_skills/jgit-cli/SKILL.md`
- `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`

## Steps（Strict）

1) TDD Red：在 `TerminalExecToolTest` 增加 `git init/status/add/commit/log` 与路径逃逸拒绝测试
2) TDD Green：引入 JGit 依赖并实现 `git init/status/add/commit/log` 命令
3) Refactor：抽取路径解析/错误输出，保持测试全绿
4) 接入：新增内置 skill `jgit-cli` 并在 `AgentsWorkspace` 安装
5) Verify：`.\gradlew.bat :app:testDebugUnitTest`

## Risks

- JGit 版本若升级到更高 Java bytecode 版本会导致 Android 构建失败：本轮固定到 Java 8 兼容版本。
- 路径处理若缺少 canonical 校验可能产生越界读写风险：测试强制覆盖出界拒绝。
