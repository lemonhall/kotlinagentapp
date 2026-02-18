# PRD-0013：JGit Pseudo Git CLI（尽量完整的 Git 能力，terminal_exec）

日期：2026-02-18  
定位：在不引入外部 `git` 可执行程序、不提供 shell 的前提下，为 App 内 Agent 提供**尽量完整**的 Git 能力（优先本地工作流，逐步扩展到远程），用于“工作区版本化 / 自动化产物追踪 / 可回放审计 / 协作同步”。

## Vision

让 Agent 可以通过 `terminal_exec` 执行一组“看起来像 git 的命令”，但实际实现为 App 内置 Kotlin + JGit（纯 Java），并满足：

- 只在 App 工作区（`.agents/...`）内操作
- 默认离线（无网络）
- 每次执行可审计（由 `terminal_exec` 统一落盘 run log）

分阶段交付（塔山循环逐版推进）：

1) v13：打通本地核心工作流（init/status/add/commit/log）  
2) v14+：补齐常用本地命令（branch/checkout/diff/show/reset 等）  
3) v15+：补齐远程能力（clone/fetch/pull/push/ls-remote），并引入严格的授权/代理/审计策略

## Non-Goals（全局）

- 不提供 shell 语义（pipe/重定向/多命令串联），仍由 `terminal_exec` 统一禁止。
- 不提供任意路径访问：命令参数的路径必须被限制在 `.agents` 根目录内（禁止 `..` / canonical 出界 / 软链逃逸）。
- 不在 v13 里承诺完整覆盖所有 git 边角行为（目标是“常用能力尽量齐 + 行为可测试/可审计/可控风险”）。

## One Tool Surface（复用 PRD-0011）

- 对 LLM 暴露工具名：`terminal_exec`（语义等价 `terminal.exec`）
- 本 PRD 只定义 `terminal_exec` 白名单命令：`git ...`

## Command Set（按阶段）

### v13：本地核心工作流

#### `git init`

初始化仓库：

- 命令：`git init --dir <path>`
- 约束：
  - `<path>` 必须在 `.agents` 根目录内
  - 允许目录不存在时创建（若实现选择创建）
- 输出：
  - `exit_code=0`
  - `result` 至少包含：`{ ok, command, repo_path, git_dir }`

#### `git status`

查询仓库状态（结构化 + 可读摘要）：

- 命令：`git status --repo <path>`
- 输出：
  - `exit_code=0`
  - `stdout` 包含可读摘要（分支/clean 与否/untracked 等）
  - `result` 至少包含：
    - `ok: boolean`
    - `command: "git status"`
    - `repo_path: string`
    - `branch: string|null`
    - `is_clean: boolean`
    - `counts: { untracked, modified, added, changed, removed, missing, conflicting }`

#### `git add`

把工作区变更加入暂存区：

- 命令：
  - `git add --repo <path> --all`
  - （后续可扩展）`git add --repo <path> --path <file>`
- 输出：
  - `exit_code=0`
  - `result` 至少包含：`{ ok, command, repo_path, added_patterns[] }`

#### `git commit`

提交暂存区变更：

- 命令：`git commit --repo <path> --message "<msg>"`
- 输出：
  - `exit_code=0`
  - `result` 至少包含：`{ ok, command, repo_path, commit_id }`

#### `git log`

查看提交历史：

- 命令：`git log --repo <path> --max <n>`
- 输出：
  - `exit_code=0`
  - `result` 至少包含：`{ ok, command, repo_path, commits[] }`

### v14+：常用本地命令（规划）

- `git branch` / `git checkout` / `git diff` / `git show` / `git reset` / `git rm` / `git mv` / `git stash`

### v15+：远程能力（规划）

- `git clone` / `git fetch` / `git pull` / `git push` / `git ls-remote`

## Safety（必须）

- 目录边界：所有 `--dir/--repo` 参数必须解析为 canonical path，并保证在 `.agents` 根目录内。
- v13 默认离线：v13 的 `git` 子命令不得发起网络请求。
- v15+ 远程能力必须增加：显式 `--confirm`、目的地/远程 URL allowlist/denylist、凭据与 token 的打码审计、代理配置对齐（避免在仓库里硬编码）。
- 输出限流：stdout/stderr/result 需保持短小；必要时走 artifacts 落盘并在 `artifacts[]` 返回引用。

## Acceptance（v13 / M1）

1. `terminal_exec` 新增 `git init/status/add/commit/log` 白名单子命令（未注册命令仍返回 `UnknownCommand`）。
2. `git init --dir workspace/jgit-demo` 成功创建仓库（`exit_code=0`）。
3. 在仓库目录内创建一个新文件后，`git status --repo workspace/jgit-demo` 返回：
   - `exit_code=0`
   - `result.counts.untracked >= 1`
4. 执行 `git add --repo workspace/jgit-demo --all` + `git commit --repo workspace/jgit-demo --message "init"` 后：
   - 再次 `git status` 返回 `result.is_clean=true`
   - `git log --max 1` 返回包含该 commit 的结构化记录
5. 任意 `--repo/--dir` 试图逃逸 `.agents` 根目录时必须被拒绝（`exit_code!=0` + 可解释 `error_code`）。
6. `.\gradlew.bat :app:testDebugUnitTest` exit code=0（包含上述场景的单测）。

## Requirements（Req IDs）

- REQ-0013-001：新增 `terminal_exec` 白名单命令 `git init --dir <path>`（本地初始化仓库）。
- REQ-0013-002：新增 `terminal_exec` 白名单命令 `git status --repo <path>`（返回 stdout + 结构化 result）。
- REQ-0013-003：新增 `terminal_exec` 白名单命令 `git add --repo <path> --all`（本地暂存）。
- REQ-0013-004：新增 `terminal_exec` 白名单命令 `git commit --repo <path> --message <msg>`（本地提交）。
- REQ-0013-005：新增 `terminal_exec` 白名单命令 `git log --repo <path> --max <n>`（本地日志）。
- REQ-0013-006：路径必须被限制在 `.agents` 根目录内（canonical 校验，拒绝逃逸）。
- REQ-0013-007：新增 Robolectric 单测覆盖 init/status/add/commit/log + 路径逃逸拒绝。
