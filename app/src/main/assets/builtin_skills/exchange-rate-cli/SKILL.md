---
name: exchange-rate-cli
description: 通过 `terminal_exec` 提供 ExchangeRate-API（open.er-api.com，无需 API key）的 `exchange-rate latest/convert` 汇率查询与换算命令（内建缓存 + --out artifacts）。
---

# exchange-rate-cli（ExchangeRate-API / Pseudo CLI）

## Goal

在 App 内 `.agents` 工作区，通过 `terminal_exec` 使用白名单命令 `exchange-rate` 完成：

- latest：查询基准货币的最新（每日更新）汇率（默认输出子集；可 `--symbols` 过滤；可 `--out` 落盘全量）
- convert：金额换算（`amount * rate`，并按 `--precision` 四舍五入展示）

## Commands

使用工具 `terminal_exec`，严格按单行命令调用（禁止 `;`、`|`、`>` 等 shell token）。

### 1) help（必须 exit 0）

- `exchange-rate --help`
- `exchange-rate help`
- `exchange-rate latest --help`
- `exchange-rate help latest`
- `exchange-rate convert --help`
- `exchange-rate help convert`

期望：
- `exit_code=0`
- `result.ok=true`
- stdout 非空

### 2) latest（默认子集；可 --symbols）

- `exchange-rate latest --base CNY`
- `exchange-rate latest --base CNY --symbols USD,EUR,JPY`

期望：
- `exit_code=0`
- `result.ok=true`
- `result.command="exchange-rate latest"`
- `result.base_code="CNY"`
- `result.rates` 仅包含默认子集或 `--symbols` 指定的 key
- 默认启用缓存：重复调用可能出现 `result.cached=true`

### 3) latest（--out 落盘 + artifacts）

- `exchange-rate latest --base CNY --out artifacts/exchange-rate/latest-CNY.json`

期望：
- `exit_code=0`
- tool output 的 `artifacts[]` 包含 `.agents/artifacts/exchange-rate/latest-CNY.json`

### 4) convert（金额换算）

- `exchange-rate convert --from CNY --to USD --amount 100 --precision 4`

期望：
- `exit_code=0`
- `result.command="exchange-rate convert"`
- `result.rate` 与 `result.converted_amount` 存在

## Safety / Guardrails（必须）

- `--base/--from/--to` 必须是 ISO 4217 三位代码；非法必须失败，`error_code="InvalidArgs"`。
- 指定币种不在 `rates` 中必须失败，`error_code="UnknownCurrency"`。
- 远端返回 `result="error"` 必须失败，`error_code="RemoteError"` 并尽可能携带 `error_type`。
- 所有 `--out` 路径必须在 `.agents/` 内；拒绝绝对路径与 `..`。

## Rules

- 必须实际调用 `terminal_exec`，不要臆造 stdout/result/artifacts。
- 若工具返回 `exit_code!=0` 或包含 `error_code`，直接把错误信息说明给用户并停止。

