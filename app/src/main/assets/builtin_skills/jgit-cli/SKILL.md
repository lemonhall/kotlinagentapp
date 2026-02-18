---
name: jgit-cli
description: 通过 `terminal_exec` 提供 JGit 驱动的 pseudo git CLI（git init/status/add/commit/log），用于在 App 工作区内初始化仓库、提交变更并查询状态与日志。
---

# jgit-cli（Pseudo Git CLI / JGit）

## Goal

在 App 内（`.agents` 工作区）用 `terminal_exec` 执行一组“看起来像 git”的命令，但不会调用外部 `git` 二进制，也不提供 shell。

## Commands（v13 + v14）

### 1) 初始化仓库

使用工具 `terminal_exec` 执行：

- `git init --dir workspace/jgit-demo`

期望：
- `exit_code=0`

### 2) 创建一个新文件（untracked）

使用文件工具 `Write` 写入一个新文件，例如：

- `Write(file_path="workspace/jgit-demo/a.txt", content="hello")`

### 3) 查看状态

使用工具 `terminal_exec` 执行：

- `git status --repo workspace/jgit-demo`

期望：
- `exit_code=0`
- `stdout` 包含 `untracked`

### 4) 暂存并提交

使用工具 `terminal_exec` 依次执行：

- `git add --repo workspace/jgit-demo --all`
- `git commit --repo workspace/jgit-demo --message "init"`

期望：
- `exit_code=0`

### 5) 再次查看状态（clean）并查看日志

使用工具 `terminal_exec` 执行：

- `git status --repo workspace/jgit-demo`
- `git log --repo workspace/jgit-demo --max 1`

期望：
- `exit_code=0`
- `stdout` 包含 `clean`
- `git log` 的 `stdout` 包含 `init`

---

## Local Common（v14）

### 分支与切换

- 列分支：`git branch --repo workspace/jgit-demo`
- 创建并切换：`git checkout --repo workspace/jgit-demo --branch feat --create`

### 查看提交 / 差异（可选 patch）

- 查看当前提交（含 patch，限制输出）：`git show --repo workspace/jgit-demo --commit HEAD --patch --max-chars 8000`
- 两个提交间 diff（含 patch，限制输出）：`git diff --repo workspace/jgit-demo --from HEAD~1 --to HEAD --patch --max-chars 8000`

说明：
- 如果 patch 太长，工具会把完整内容写入 artifact 文件，并在 tool output 的 `artifacts[]` 返回其路径。

### reset

- `git reset --repo workspace/jgit-demo --mode hard --to HEAD~1`

### stash（最小闭环）

- `git stash push --repo workspace/jgit-demo --message "wip"`
- `git stash list --repo workspace/jgit-demo --max 5`
- `git stash pop --repo workspace/jgit-demo --index 0`

## Rules

- 必须实际调用 `terminal_exec`，不要自己臆造 git 输出。
- `terminal_exec` 不支持 `;` / `&&` / `|` / 重定向；所有命令必须是单行。
- 若工具返回 `exit_code!=0` 或包含 `error_code`，直接向用户说明错误并停止。
