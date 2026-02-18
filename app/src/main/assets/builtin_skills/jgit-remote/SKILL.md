---
name: jgit-remote
description: 通过 `terminal_exec` 提供 JGit 远程能力（ls-remote/clone/fetch/pull/push），要求显式 `--confirm`、https-only、并通过 stdin 传递凭据以避免审计泄露。
---

# jgit-remote（Pseudo Git Remote / JGit）

## Goal

在 App 内（`.agents` 工作区）用 `terminal_exec` 执行远程 git 操作，并满足安全约束：

- 所有可能触发网络的命令都必须显式 `--confirm`
- 远程 URL 仅支持 `https://...`，且禁止 `https://user:pass@...` 这种 userinfo
- 需要授权时，只能通过 `stdin` 传递（不会进入 `command/argv` 审计）

## Commands（v15）

### 探测远程 refs

- `git ls-remote --remote https://example.com/repo.git --confirm`

（可选授权）将 token 放到 `stdin`，并在 argv 指定 `--auth`：

- command：`git ls-remote --remote https://example.com/repo.git --confirm --auth stdin-token`
- stdin：`<YOUR_TOKEN>`

### clone

- `git clone --remote https://example.com/repo.git --dir workspace/repo1 --confirm`

（可选授权）
- command：`git clone --remote https://example.com/repo.git --dir workspace/repo1 --confirm --auth stdin-basic`
- stdin：`username:password`

### fetch / pull / push

- `git fetch --repo workspace/repo1 --confirm`
- `git pull --repo workspace/repo1 --confirm`
- `git push --repo workspace/repo1 --confirm`

## Offline / Test（local-remote）

用于离线验证（不走网络），remote 也可以指向 `.agents` 根内的本地 bare repo 路径：

- `git init --dir workspace/origin.git --bare`
- `git clone --local-remote workspace/origin.git --dir workspace/clone1 --confirm`

## Rules

- 远程命令缺失 `--confirm` 时必须停止，并向用户说明需要确认。
- 禁止把 token/密码放在 URL 或命令参数里（会进入审计）；只能用 `stdin`。
- 若工具返回 `exit_code!=0` 或包含 `error_code`，直接向用户说明错误并停止。

