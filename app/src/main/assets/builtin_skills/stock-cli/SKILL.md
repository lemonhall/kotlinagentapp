---
name: stock-cli
description: 通过 `terminal_exec` 提供 Finnhub 股市数据的 `stock ...` 命令（dotenv 凭据、频控、--out artifacts、禁止 candle）。
---

# stock-cli（Finnhub Stock REST / Pseudo CLI）

## Goal

在 App 内 `.agents` 工作区，通过 `terminal_exec` 使用白名单命令 `stock` 完成：

- quote：实时行情（摘要 + 结构化 result）
- profile：公司简介（摘要 + 结构化 result）
- symbols：美股 symbols 列表（强制 `--out` 落盘 + artifacts 引用）
- company-news/news：公司新闻 / 市场新闻（默认摘要；可选 `--out` 落盘完整列表）
- rec/metric/earnings：推荐趋势 / 基本面指标 / 财报日历（默认摘要；可选 `--out`）

## Secrets（本地 .env）

凭据只允许从以下路径读取：

- `.agents/skills/stock-cli/secrets/.env`

初始化规则：

- App 启动时如果发现 `.agents/skills/stock-cli/secrets/.env` 不存在，会从内置模板自动生成一份（便于你在 Files 页签直接编辑）。
- 模板文件位于同目录：`.agents/skills/stock-cli/secrets/env.example`（只读参考，不要改它；请改 `.env`）。

`.env` 示例（不要提交真实 key）：

```dotenv
FINNHUB_API_KEY=
FINNHUB_BASE_URL=https://finnhub.io/api/v1
```

## Commands（v23）

使用工具 `terminal_exec`，严格按单行命令调用（禁止 `;`、`|`、`>` 等 shell token）。

### 1) quote

- `stock quote --symbol AAPL`

期望：
- `exit_code=0`
- `result.ok=true`
- `result.command="stock quote"`
- stdout/result 不包含 API key

### 2) profile

- `stock profile --symbol AAPL`

期望：
- `exit_code=0`
- `result.ok=true`
- stdout 仅摘要字段

### 3) symbols（强制 --out）

- `stock symbols --exchange US --out artifacts/stock/symbols-us.json`

期望：
- 缺 `--out`：必须失败，`error_code="OutRequired"`
- 有 `--out`：落盘到 `.agents/artifacts/stock/symbols-us.json`
- tool output 的 `artifacts[]` 包含 `.agents/artifacts/stock/symbols-us.json`

### 4) company-news（建议 --out）

- `stock company-news --symbol AAPL --from 2026-01-01 --to 2026-02-18 --out artifacts/stock/aapl-news.json`

### 5) news（建议 --out）

- `stock news --category general --out artifacts/stock/market-news.json`

### 6) rec / metric / earnings（建议 --out）

- `stock rec --symbol AAPL`
- `stock metric --symbol AAPL --metric all --out artifacts/stock/aapl-metric.json`
- `stock earnings --from 2026-02-01 --to 2026-02-28 --out artifacts/stock/earnings-2026-02.json`

## Safety / Guardrails（必须）

- 缺 `.env` 或缺 `FINNHUB_API_KEY`：必须失败，`error_code="MissingCredentials"`。
- 频控/429：必须失败，`error_code="RateLimited"`，并尽可能携带 `retry_after_ms`。
- 禁止 candle：任何 `stock candle*` 必须失败，`error_code="NotSupported"`。
- 所有 `--out` 路径必须在 `.agents/` 内；拒绝绝对路径与 `..`。

## Rules

- 必须实际调用 `terminal_exec`，不要臆造 stdout/result/artifacts。
- 若工具返回 `exit_code!=0` 或包含 `error_code`，直接把错误信息说明给用户并停止。

