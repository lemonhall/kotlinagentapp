---
name: archive-zip
description: 通过 `terminal_exec` 提供 zip list/extract/create（纯 Java），并固化 confirm/越界/覆盖/输出落盘护栏。
---

# archive-zip（Pseudo Zip CLI）

## Goal

在 App 内 `.agents` 工作区，通过 `terminal_exec` 使用白名单命令 `zip` 完成：

- list：列出 zip 内容（支持 `--out` 落盘完整清单）
- create：从目录/文件创建 zip（写操作必须 `--confirm`）
- extract：解压 zip 到目录（写操作必须 `--confirm`，并防 ZipSlip）

## Commands（v17）

### 1) 准备源目录与文件

使用文件工具 `Write` 写入（示例）：

- `Write(file_path="workspace/archive-zip-demo/src/a.txt", content="hello zip")`
- `Write(file_path="workspace/archive-zip-demo/src/sub/b.txt", content="sub file")`

### 2) create（必须 --confirm）

使用工具 `terminal_exec` 执行：

- `zip create --src workspace/archive-zip-demo/src --out workspace/archive-zip-demo/out.zip --confirm`

期望：
- `exit_code=0`
- `result.ok=true`
- `workspace/archive-zip-demo/out.zip` 文件真实存在

### 3) list（支持 --out 落盘完整清单）

使用工具 `terminal_exec` 执行：

- `zip list --in workspace/archive-zip-demo/out.zip --max 10 --out artifacts/archive/archive-zip-demo-list.json`

期望：
- `exit_code=0`
- tool output 的 `artifacts[]` 包含 `.agents/artifacts/archive/archive-zip-demo-list.json`

### 4) extract（必须 --confirm，默认不覆盖）

使用工具 `terminal_exec` 执行：

- `zip extract --in workspace/archive-zip-demo/out.zip --dest workspace/archive-zip-demo/dest --confirm`

期望：
- `exit_code=0`
- `result.ok=true`
- `workspace/archive-zip-demo/dest/a.txt` 存在，内容为 `hello zip`

## Rules

- 必须实际调用 `terminal_exec`，不要臆造 stdout/result/artifacts。
- `zip extract/create` 缺失 `--confirm` 必须视为错误并停止。
- `terminal_exec` 不支持 `;` / `&&` / `|` / 重定向；命令必须单行。
- 若工具返回 `exit_code!=0` 或包含 `error_code`，直接把错误信息说明给用户并停止。

