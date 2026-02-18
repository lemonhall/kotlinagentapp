# PRD-0015：JGit Pseudo Git CLI（远程能力，v15）

日期：2026-02-18  
定位：在 `terminal_exec` 的白名单 pseudo CLI 下补齐远程常用能力（clone/fetch/pull/push/ls-remote），并引入**强约束安全策略**：显式确认、URL 校验、凭据不落审计、不污染仓库配置。

## Vision

Agent 能在 App 工作区（`.agents/...`）内，通过 `terminal_exec` 对远程仓库进行基本同步：

- `git ls-remote`：探测远程 refs
- `git clone`：克隆到 `.agents/workspace/...`
- `git fetch/pull`：拉取与更新
- `git push`：推送本地提交

同时必须满足：

- 默认“需要显式确认”：任何可能触发网络请求的命令必须带 `--confirm`
- 凭据不进入审计：token/密码必须通过 `stdin` 提供（`terminal_exec` 的审计不会记录 stdin）
- URL 安全：拒绝包含 userinfo（`https://user:pass@...`）的 URL；限制 scheme（v15 仅 `https`）
- 允许本地 remote（测试/离线）：支持 `--local-remote <path>`，且路径必须在 `.agents` 根内（用于单测与离线验证）

## Non-Goals（v15 范围外）

- SSH（`ssh://`、`git@host:repo`）与其密钥管理（后续版本再做）。
- 复杂的凭据管理 UI（本轮仅 stdin 方案）。
- 代理策略硬编码进仓库（不得写入 repo 文件；仅运行时注入/配置）。

## Command Set（v15）

### 统一参数

- 远程 URL：
  - `--remote <https_url>`（真实网络）
  - `--local-remote <path>`（离线/测试，本地路径，必须在 `.agents` 根内）
- 授权（可选）：
  - `--auth stdin-token`：`stdin` 为 token（不写入 argv/日志）
  - `--auth stdin-basic`：`stdin` 为 `username:password`

### `git ls-remote`

- 命令：
  - `git ls-remote --remote <https_url> --confirm [--auth ...]`
  - `git ls-remote --local-remote <path> --confirm`
- 输出：
  - `result.refs[] = { name, id }`

### `git clone`

- 命令：
  - `git clone --remote <https_url> --dir <path> --confirm [--auth ...]`
  - `git clone --local-remote <path> --dir <path> --confirm`
- 约束：
  - `--dir` 必须在 `.agents` 根内
  - `--remote` 必须为 https 且无 userinfo

### `git fetch`

- 命令：`git fetch --repo <path> --confirm [--auth ...]`

### `git pull`

- 命令：`git pull --repo <path> --confirm [--auth ...]`

### `git push`

- 命令：`git push --repo <path> --confirm [--auth ...]`

## Safety

- `--confirm`：缺失则拒绝（`error_code="ConfirmRequired"`）。
- URL 校验：
  - scheme 必须 `https`
  - 禁止 userinfo（出现 `@` 形式的用户信息）避免把 token 写进命令与审计
- 错误信息净化：任何异常信息不得回显 stdin 内容；stdout/stderr 中不得出现 token/密码。
- 本地 remote 也必须 `--confirm`（防止误操作写入大量数据），但不产生网络。

## Acceptance

1. `git clone --remote ...` 缺失 `--confirm` 必拒绝（ConfirmRequired）。
2. `git clone --remote https://user:pass@host/repo.git --confirm` 必拒绝（InvalidRemoteUrl）。
3. 本地 remote 最小闭环（单测可跑）：
   - 建一个 bare origin repo（在 `.agents/...`）
   - `git clone --local-remote <origin> --dir <clone1> --confirm`
   - 在 clone1 提交并 `git push --confirm`
   - clone2 `git pull --confirm` 后能看到新 commit
4. `.\gradlew.bat :app:testDebugUnitTest` exit code=0

## Requirements（Req IDs）

- REQ-0015-001：新增 `git ls-remote/clone/fetch/pull/push` 白名单子命令（JGit 实现）。
- REQ-0015-002：远程命令必须显式 `--confirm`。
- REQ-0015-003：凭据必须通过 stdin 提供，禁止通过 URL userinfo / argv 明文传递。
- REQ-0015-004：单测覆盖确认门禁、URL 校验、local-remote 端到端闭环。

