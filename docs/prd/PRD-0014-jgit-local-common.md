# PRD-0014：JGit Pseudo Git CLI（本地常用命令，v14）

日期：2026-02-18  
定位：在 `terminal_exec` 的“白名单 pseudo CLI”框架下，把常用 **本地** git 能力尽快补齐到可用水平（不依赖外部 `git`）。

## Vision

Agent 能在 App 工作区（`.agents/...`）内，使用 `terminal_exec` 执行常用本地 git 工作流：

- 管理分支与切换（branch/checkout）
- 查看提交与差异（log/show/diff）
- 回退/重置（reset）
- 暂存临时变更（stash）

输出必须同时包含：

- 面向人的 `stdout` 摘要
- 面向 Agent 的结构化 `result`（可稳定消费）

## Non-Goals（v14 范围外）

- 远程网络能力（clone/fetch/pull/push/ls-remote）留到 v15。
- 完整覆盖所有 git 边角行为；以“常用命令可用 + 行为可测试 + 安全可控”为优先。
- shell 语义（pipe/重定向/多命令串联）依旧禁止。

## Command Set（v14）

> 统一约束：所有 `--repo` 路径必须限制在 `.agents` 根目录内（canonical 校验），否则拒绝。

### `git branch`

- 命令：`git branch --repo <path>`
- 输出 `result`：
  - `current: string|null`
  - `branches: string[]`

### `git checkout`

- 命令：
  - `git checkout --repo <path> --branch <name>`
  - `git checkout --repo <path> --branch <name> --create`
- 输出 `result`：
  - `branch: string`
  - `created: boolean`

### `git diff`

v14 提供“可验证 + 可控输出”的 diff：

- 命令：
  - `git diff --repo <path> --from <rev> --to <rev> [--patch] [--max-chars <n>]`
- 行为：
  - 默认输出 name-status 摘要
  - `--patch` 才输出补丁文本；超长必须落盘 artifact，并在 `artifacts[]` 返回引用

### `git show`

- 命令：`git show --repo <path> --commit <rev> [--patch] [--max-chars <n>]`
- 输出 `result`：
  - `commit: { id, message, author_name, author_email, timestamp_ms }`
  - `files?: string[]`（至少 name-status 或文件列表）

### `git reset`

- 命令：`git reset --repo <path> --mode soft|mixed|hard --to <rev>`
- 输出 `result`：`{ to, mode }`

### `git stash`

- 命令：
  - `git stash push --repo <path> --message "<msg>"`
  - `git stash list --repo <path> --max <n>`
  - `git stash pop --repo <path> [--index <n>]`

## Safety

- 路径边界：拒绝 `.agents` 根外路径（含 `..` 与 canonical 出界）。
- 输出控制：`--patch` 输出必须受 `--max-chars` 控制；超限写入 artifacts。

## Acceptance

1. `git branch` 能列出分支，`git checkout --create` 能创建并切换新分支。
2. `git show --commit HEAD --patch` 至少能产出包含提交 message 的 stdout（或 artifact）。
3. `git diff --from HEAD~1 --to HEAD --patch` 能产出非空输出（或 artifact）。
4. `git reset --mode hard --to HEAD~1` 可回退一个提交（可由 `git log --max 1` 验证）。
5. `git stash push/list/pop` 最小闭环可用（push 后 clean，pop 后恢复变更）。
6. `.\gradlew.bat :app:testDebugUnitTest` exit code=0（单测覆盖上述场景）。

## Requirements（Req IDs）

- REQ-0014-001：新增 `git branch/checkout` 白名单子命令（本地分支管理）。
- REQ-0014-002：新增 `git diff/show`（支持 `--patch` + artifacts 截断策略）。
- REQ-0014-003：新增 `git reset`（soft/mixed/hard）。
- REQ-0014-004：新增 `git stash push/list/pop`。
- REQ-0014-010：单测覆盖 v14 的关键 happy path 与边界拒绝。

