# PRD-0022：Finnhub Stock API 集成（股市数据）Pseudo Terminal Commands

日期：2026-02-18  
定位：在 `terminal_exec` 的“白名单 pseudo CLI（无 shell / 无外部进程）”框架下，集成 Finnhub Stock API 作为工具箱中“股市”模块的数据源；优先覆盖免费 tier 可用的核心 REST 接口，并把密钥存储、频控、输出契约与大输出落盘写死到验收口径中。

## Vision

让用户与 Agent 都可以在 App 内仅通过 `terminal_exec`：

- 查询单只美股的实时行情（quote）
- 查询公司简介（profile）
- 查询分析师推荐趋势、基本面指标
- 查询公司新闻与市场新闻
- 查询财报日历（earnings calendar）
- 获取美股 symbols 列表（仅允许落盘到 artifacts，避免 stdout/result 过大）

并且：

- **密钥本地化**：API Key 存在 `.agents/skills/stock-cli/secrets/.env`，不进代码/日志/git
- **频控内建**：免费 tier 限速约 60 次/分钟，超限（429）必须给出可解释错误
- **输出可控**：stdout/result 默认只输出摘要；大输出必须 `--out` 落盘并通过 artifacts 引用
- **可审计**：每次调用都会被 `terminal_exec` 审计落盘（工具已自带）

## Background（Finnhub）

- 官网：finnhub.io
- Base URL：`https://finnhub.io/api/v1`
- 认证：
  - Header：`X-Finnhub-Token: <API_KEY>`（默认使用）
  - 或 Query：`?token=<API_KEY>`（仅作为兼容兜底）
- 免费 tier：
  - 约 `60 req/min`（必须做 rate limiter）
  - 不支持历史 K 线（Candles）：**禁止调用** `/stock/candle`
  - 外汇/加密 symbols 可能可列出，但行情多为 premium：v22 只保证 US 股票场景
- WebSocket：`wss://ws.finnhub.io?token=<API_KEY>`（v22 不做；未来另开 PRD）

## Non-Goals（v22）

- 不做：历史 K 线（Candles）与任何 `/stock/candle` 调用
- 不做：WebSocket 实时推送
- 不做：登录态、用户账户体系
- 不做：投资建议/自动交易
- 不做：外汇/加密行情保证（免费 tier 覆盖不稳定）
- 不做：本地持仓/收益计算（只做数据查询）

## Dependencies

- 网络权限与 HTTP 客户端：优先使用 OkHttp（Android 内置生态）；禁止使用第三方 Finnhub SDK（Maven 封装过旧）
- JSON 解析：Jackson 或 Gson（二选一，跟随现有工程依赖）
- 密钥存储方式：沿用 `qqmail-cli` 的 skill-local secrets `.env` 机制

## Project Rules（实现必须遵守：来自 paw-cli-add-workflow）

- `TerminalCommands.kt` 只能做“命令注册表/默认 registry 构建”，禁止堆实现逻辑。
- 顶层命令必须独立目录：
  - `stock`：`app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/stock/*`
- `TerminalExecTool` 的 registry 初始化逻辑不改；只在 `TerminalCommands.defaultRegistry(...)` 注册新增命令。

## Secrets（API Key 存储）

Skill 名称：`stock-cli`

- `.env` 位置：`.agents/skills/stock-cli/secrets/.env`
- 变量（最小必填）：
  - `FINNHUB_API_KEY=<your_key>`
- 可选变量：
  - `FINNHUB_BASE_URL=https://finnhub.io/api/v1`（默认此值；仅用于调试/镜像）

实现约束（必须）：

1) stdout/stderr/result **不得**包含 `FINNHUB_API_KEY`（任何形式、任何子串）。  
2) `.env` 与任何密钥文件不得提交到 git；只允许在本地 `.agents/skills/stock-cli/secrets/`。  
3) Debug 首次安装/初始化时，可从 assets 的 `env.example` seed 到 `.env`（参考 `qqmail-cli` 机制）。  

## Rate Limit & Reliability

免费 tier 约 60 req/min，必须提供“可重复、可测试”的限速策略：

- 目标：将全局请求速率限制在 `<= 55 req/min`（留余量避免边界抖动）
- 429 处理：
  - 输出 `error_code = "RateLimited"`，并尽可能携带 `retry_after_ms`（如果可获得）
  - 不自动重试（避免雪崩）；未来版本可加指数退避，但必须可控
- 超时与网络错误：
  - `error_code = "NetworkError"` / `FinnhubHttpError` 等可解释口径

## Command Set（v22 目标命令协议）

统一输出契约：

- `exit_code`：0 成功；非 0 失败
- `stdout`：人类可读摘要
- `result`：结构化 JSON（稳定字段）
- `artifacts[]`：大输出落盘引用（path/mime/description）
- 失败必须提供：`error_code` + `error_message`

统一 guardrails：

- 大输出（symbols/news/earnings/metric 可能超大）不得直接塞 stdout/result；必须 `--out` 落盘
- 所有落盘路径必须在 `.agents/` 内；拒绝绝对路径与 `..`（`PathEscapesAgentsRoot` / `InvalidArgs`）

### 1) `stock quote`

- 命令：
  - `stock quote --symbol AAPL`
- Finnhub：
  - `GET /quote?symbol=AAPL`
- result（最小字段）：
  - `ok`
  - `command: "stock quote"`
  - `symbol`
  - `quote: { c, h, l, o, pc, t }`

### 2) `stock profile`

- 命令：
  - `stock profile --symbol AAPL`
- Finnhub：
  - `GET /stock/profile2?symbol=AAPL`
- result（最小字段）：
  - `ok`
  - `command: "stock profile"`
  - `symbol`
  - `profile`（原样 JSON；但 stdout 只输出 name/ticker/exchange/industry 摘要）

### 3) `stock symbols`（强制 `--out`）

- 命令：
  - `stock symbols --exchange US --out artifacts/stock/symbols-us.json`
- Finnhub：
  - `GET /stock/symbol?exchange=US`
- 约束：
  - 缺 `--out` 必须拒绝（`OutRequired`），避免 result 过大
- result（最小字段）：
  - `ok`
  - `command: "stock symbols"`
  - `exchange`
  - `out`
  - `count_total`（从落盘 JSON 数组长度计算）

### 4) `stock company-news`（建议 `--out`，默认摘要）

- 命令：
  - `stock company-news --symbol AAPL --from 2026-01-01 --to 2026-02-18 --out artifacts/stock/aapl-news.json`
- Finnhub：
  - `GET /company-news?symbol=AAPL&from=YYYY-MM-DD&to=YYYY-MM-DD`
- 默认行为：
  - 若无 `--out`：只返回 `count_emitted <= 10` 的摘要列表（标题+时间+链接）
  - 若有 `--out`：落盘完整 JSON 数组并返回 artifact

### 5) `stock news`（市场新闻，建议 `--out`，默认摘要）

- 命令：
  - `stock news --category general --out artifacts/stock/market-news.json`
- Finnhub：
  - `GET /news?category=general`
- 默认行为：
  - 若无 `--out`：只返回 `count_emitted <= 10` 摘要
  - 若有 `--out`：落盘完整 JSON 数组并返回 artifact

### 6) `stock rec`

- 命令：
  - `stock rec --symbol AAPL`
- Finnhub：
  - `GET /stock/recommendation?symbol=AAPL`
- result（最小字段）：
  - `ok`
  - `command: "stock rec"`
  - `symbol`
  - `trend`（原样 JSON 数组；若过大可只保留最近 N 条，并在 stdout 标注）

### 7) `stock metric`（建议 `--out`）

- 命令：
  - `stock metric --symbol AAPL --metric all --out artifacts/stock/aapl-metric.json`
- Finnhub：
  - `GET /stock/metric?symbol=AAPL&metric=all`
- 默认行为：
  - 若无 `--out`：result 只保留少量关键字段（例如 marketCap、52WeekHigh/Low 等），其余不输出
  - 若有 `--out`：落盘完整 JSON 并返回 artifact

### 8) `stock earnings`（建议 `--out`，默认摘要）

- 命令：
  - `stock earnings --from 2026-02-01 --to 2026-02-28 --out artifacts/stock/earnings-2026-02.json`
- Finnhub：
  - `GET /calendar/earnings?from=YYYY-MM-DD&to=YYYY-MM-DD`
- 默认行为：
  - 若无 `--out`：仅返回 `count_emitted <= 20` 摘要（symbol+date）
  - 若有 `--out`：落盘完整 JSON 并返回 artifact

## Error Codes（统一口径）

- `InvalidArgs`：参数缺失/格式错误
- `MissingCredentials`：找不到 `.env` 或缺 `FINNHUB_API_KEY`
- `RateLimited`：命中本地限速或 Finnhub 返回 429
- `NetworkError`：网络/超时/无法解析
- `FinnhubHttpError`：非 2xx HTTP（包含 status）
- `OutRequired`：该命令要求 `--out`（例如 symbols）
- `PathEscapesAgentsRoot`：`--out` 路径越界（含 `..` 或绝对路径）
- `NotSupported`：明确禁止的接口/能力（例如 candle）

## Acceptance（v22 交付口径）

> v22 为文档阶段：锁定协议与验收口径；不写实现代码。

1. `docs/prd/PRD-0022-finnhub-stock-cli.md` 存在，且包含 Req IDs、Non-Goals、Acceptance、Secrets、Rate Limit、Command Set。  
2. `docs/plan/v22-stock-cli.md` 存在，且包含可执行 Steps 与 Files 规划。  
3. 文档中的 DoD/Acceptance 全部可二元判定（pass/fail），无模糊词。  
4. 提交与推送：`git status --porcelain=v1` 为空。  

## Requirements（Req IDs）

### A. 命令面（REST）

- REQ-0022-001：新增 `stock quote`，返回 Finnhub `/quote` 数据的摘要与结构化 result。
- REQ-0022-002：新增 `stock profile`，返回 Finnhub `/stock/profile2` 数据摘要与结构化 result。
- REQ-0022-003：新增 `stock symbols`，强制 `--out` 落盘完整 symbols 列表并返回 artifact。
- REQ-0022-004：新增 `stock company-news`，支持日期范围，默认摘要，`--out` 落盘完整列表。
- REQ-0022-005：新增 `stock news`（general），默认摘要，`--out` 落盘完整列表。
- REQ-0022-006：新增 `stock rec`，返回 `/stock/recommendation`。
- REQ-0022-007：新增 `stock metric`，支持 `metric=all`，默认摘要，`--out` 落盘完整 JSON。
- REQ-0022-008：新增 `stock earnings`，支持日期范围，默认摘要，`--out` 落盘完整 JSON。

### B. 安全/工程

- REQ-0022-010：API Key 必须从 `.agents/skills/stock-cli/secrets/.env` 读取；不得写入代码/日志/result。
- REQ-0022-011：实现必须内建 rate limiter（目标 `<= 55 req/min`），429 输出 `RateLimited`。
- REQ-0022-012：大输出必须落盘（`--out` + artifacts），避免 stdout/result 截断误判。
- REQ-0022-013：路径必须受 `.agents` 根目录约束，拒绝绝对路径与 `..`。
- REQ-0022-014：禁止调用 `/stock/candle`，且命令层必须给出 `NotSupported` 口径。
- REQ-0022-020：命令实现必须放在独立目录 `commands/stock/*`；`TerminalCommands.kt` 只做注册表。

