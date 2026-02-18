---
name: archive-tar
description: 通过 `terminal_exec` 提供 tar list/extract/create（commons-compress，纯 Java），并固化 confirm/越界/拒绝链接/覆盖/输出落盘护栏。
---

# archive-tar（Pseudo Tar CLI）

## Goal

在 App 内 `.agents` 工作区，通过 `terminal_exec` 使用白名单命令 `tar` 完成：

- list：列出 tar 内容（支持 `--out` 落盘完整清单）
- create：从目录/文件创建 tar（写操作必须 `--confirm`）
- extract：解压 tar 到目录（写操作必须 `--confirm`，防 TarSlip，默认拒绝 symlink/hardlink）

## Commands（v17）

### 1) 准备源目录与文件

使用文件工具 `Write` 写入（示例）：

- `Write(file_path="workspace/archive-tar-demo/src/a.txt", content="hello tar")`
- `Write(file_path="workspace/archive-tar-demo/src/sub/b.txt", content="sub file")`

### 2) create（必须 --confirm）

使用工具 `terminal_exec` 执行：

- `tar create --src workspace/archive-tar-demo/src --out workspace/archive-tar-demo/out.tar --confirm --format tar`

期望：
- `exit_code=0`
- `result.ok=true`
- `workspace/archive-tar-demo/out.tar` 文件真实存在

### 3) list（支持 --out 落盘完整清单）

使用工具 `terminal_exec` 执行：

- `tar list --in workspace/archive-tar-demo/out.tar --max 10 --out artifacts/archive/archive-tar-demo-list.json --format tar`

期望：
- `exit_code=0`
- tool output 的 `artifacts[]` 包含 `.agents/artifacts/archive/archive-tar-demo-list.json`

### 4) extract（必须 --confirm，默认不覆盖）

使用工具 `terminal_exec` 执行：

- `tar extract --in workspace/archive-tar-demo/out.tar --dest workspace/archive-tar-demo/dest --confirm --format tar`

期望：
- `exit_code=0`
- `result.ok=true`
- `workspace/archive-tar-demo/dest/a.txt` 存在，内容为 `hello tar`

## Rules

- 必须实际调用 `terminal_exec`，不要臆造 stdout/result/artifacts。
- `tar extract/create` 缺失 `--confirm` 必须视为错误并停止。
- v17 默认拒绝 symlink/hardlink 条目（避免 link 逃逸）；若 `result.skipped.unsafe_link>0` 属于预期防护表现。
- `terminal_exec` 不支持 `;` / `&&` / `|` / 重定向；命令必须单行。
- 若工具返回 `exit_code!=0` 或包含 `error_code`，直接把错误信息说明给用户并停止。

