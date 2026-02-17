---
name: hello-world
description: 最小闭环 skill：通过 `terminal_exec` 执行白名单 CLI（hello），验证“SKILL.md → 工具调用 → stdout → 审计落盘”。
---

# Hello World（Pseudo Terminal）

## Goal

验证伪终端（白名单 CLI）运行时最小闭环。

## Command

使用工具 `terminal_exec` 执行以下命令（单行）：

- `hello`

期望输出（stdout）：

- 包含 `HELLO` 的 ASCII 图
- 额外包含 `lemonhall` 字符签名（证明为程序化输出）

另外：工具会返回 `run_id`，并把审计落盘到 `.agents/artifacts/terminal_exec/runs/<run_id>.json`。

## Rules
- 必须实际调用 `terminal_exec`，不要自己手写/臆造 ASCII 图。
- 若工具返回 `exit_code!=0` 或包含 `error_code`，直接把错误信息说明给用户并停止。
