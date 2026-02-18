---
name: rss-cli
description: 通过 `terminal_exec` 提供 RSS/Atom 订阅与抓取的 `rss add/list/remove/fetch` 命令（落盘到 .agents/workspace/rss，建议 --out artifacts）。
---

# rss-cli（RSS/Atom Subscriptions + Fetch / Pseudo CLI）

## Goal

在 App 内 `.agents` 工作区，通过 `terminal_exec` 使用白名单命令 `rss` 完成：

- add：新增/更新订阅（写入 `.agents/workspace/rss/subscriptions.json`）
- list：列出订阅（默认摘要；可用 `--out` 落盘完整 JSON 并通过 artifacts 引用）
- remove：删除订阅（不存在返回 `NotFound`）
- fetch：抓取并解析 RSS/Atom（默认摘要；可用 `--out` 落盘完整条目数组并通过 artifacts 引用）

## Commands

使用工具 `terminal_exec`，严格按单行命令调用（禁止 `;`、`|`、`>` 等 shell token）。

### 1) add

- `rss add --name wapo-politics --url https://www.washingtonpost.com/arcio/rss/category/politics/`

期望：
- `exit_code=0`
- `result.ok=true`
- `result.command="rss add"`

### 2) list（默认摘要；完整清单用 --out）

- `rss list --max 50`
- `rss list --out artifacts/rss/subscriptions.json`

期望：
- 无 `--out`：`result.items` 仅摘要字段
- 有 `--out`：落盘到 `.agents/artifacts/rss/subscriptions.json`，并在 tool output 的 `artifacts[]` 返回该路径

### 3) remove

- `rss remove --name wapo-politics`

期望：
- 不存在：失败，`error_code="NotFound"`
- 存在：成功，`result.command="rss remove"`

### 4) fetch（默认摘要；完整条目用 --out）

- `rss fetch --name wapo-politics --max-items 20`
- `rss fetch --name wapo-politics --max-items 100 --out artifacts/rss/wapo-politics-items.json`
- `rss fetch --url https://www.washingtonpost.com/arcio/rss/category/politics/ --max-items 20`

期望：
- `exit_code=0`
- `result.command="rss fetch"`
- 有 `--out`：落盘到 `.agents/<out_path>`，并在 tool output 的 `artifacts[]` 返回该路径

## Safety / Guardrails（必须）

- 只允许 `http/https` URL；`file://` 必须失败，`error_code="InvalidArgs"`。
- 429：必须失败，`error_code="RateLimited"`；若有 `Retry-After` 且可解析为秒数，应返回 `result.retry_after_ms`。
- 所有 `--out` 路径必须在 `.agents/` 内；拒绝绝对路径与 `..`（`error_code="PathEscapesAgentsRoot"` 或 `InvalidArgs`）。
- 输出较大时优先使用 `--out`，避免 stdout/result 截断误判。

## Rules

- 必须实际调用 `terminal_exec`，不要臆造 stdout/result/artifacts。
- 若工具返回 `exit_code!=0` 或包含 `error_code`，直接把错误信息说明给用户并停止。

