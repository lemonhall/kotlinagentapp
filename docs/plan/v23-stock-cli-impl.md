# v23 Plan：Stock CLI（实现 + 单测 + 内置 Skill）

## Goal

按 `docs/prd/PRD-0022-finnhub-stock-cli.md` 与 `docs/plan/v22-stock-cli.md` 的硬口径，实现 `terminal_exec` 的 `stock` 白名单命令，并提供内置 Skill `stock-cli`，确保“命令 → HTTP 请求 → 落盘 → artifacts → 单测”闭环可复现。

## PRD Trace

- PRD-0022：REQ-0022-001 / REQ-0022-002 / REQ-0022-003 / REQ-0022-004
- PRD-0022：REQ-0022-005 / REQ-0022-006 / REQ-0022-007 / REQ-0022-008
- PRD-0022：REQ-0022-010 / REQ-0022-011 / REQ-0022-012 / REQ-0022-013 / REQ-0022-014
- PRD-0022：REQ-0022-020

## Scope

做（v23）：
- 实现 `stock quote/profile/symbols/company-news/news/rec/metric/earnings`
- Secrets：只从 `.agents/skills/stock-cli/secrets/.env` 读 `FINNHUB_API_KEY`（可选 `FINNHUB_BASE_URL`）
- 频控：内建全局 rate limiter（目标 `<= 55 req/min`）
- 输出：默认 stdout/result 只给摘要；大输出用 `--out` 落盘并返回 artifacts 引用
- Guardrails：
  - `stock symbols` 强制 `--out`（`OutRequired`）
  - `--out` 路径必须在 `.agents/` 内（拒绝绝对路径与 `..`）
  - 禁止 candle（`NotSupported`），实现不得出现 `/stock/candle` 调用
- 单测覆盖：
  - MissingCredentials / OutRequired / PathEscapesAgentsRoot / NotSupported / RateLimited
  - 反作弊：用 fake HTTP 断言请求 endpoint/path/query/headers 确实被构造；并断言 artifacts 文件真实落盘存在且包含关键字段
- 内置 Skill：`stock-cli`（assets + 安装 + secrets seed）

不做（v23）：
- WebSocket 实时推送
- 历史 K 线（candles）
- 自动重试/指数退避（429 直接报错，避免雪崩）
- 缓存/批量并发优化（后续另开 vN）

## Acceptance（硬口径）

1. `stock quote --symbol AAPL`：请求 `GET /quote?symbol=AAPL`，`result.command="stock quote"` 且 `result.symbol="AAPL"`。  
2. `stock profile --symbol AAPL`：请求 `GET /stock/profile2?symbol=AAPL`，stdout 只输出摘要字段。  
3. `stock symbols` 缺 `--out` 必拒绝（`OutRequired`）。  
4. 所有 `--out` 路径必须在 `.agents/` 内，拒绝绝对路径与 `..`（`PathEscapesAgentsRoot` / `InvalidArgs`）。  
5. Secrets：只读取 `.agents/skills/stock-cli/secrets/.env`；缺 `FINNHUB_API_KEY` 返回 `MissingCredentials`；stdout/result/audit 不包含 key 子串。  
6. 禁止 candle：`stock candle ...` 必须失败（`NotSupported`），且实现中不得出现 `/stock/candle` 调用。  
7. 频控：命中本地限速或 Finnhub 429 时必须输出 `error_code="RateLimited"`（尽可能携带 `retry_after_ms`）。  
8. 内置 Skill：`app/src/main/assets/builtin_skills/stock-cli/SKILL.md` 存在；`AgentsWorkspace.ensureInitialized()` 会安装并 seed `.env`。  
9. Tests：`.\gradlew.bat :app:testDebugUnitTest` exit code=0。  

反作弊条款（必须）：
- 单测必须断言 fake HTTP 收到了“正确 path + query + `X-Finnhub-Token` header”，并断言 artifacts 文件真实落盘存在且 JSON 包含关键字段（禁止空实现/假输出）。

## Files

- 依赖：
  - `gradle/libs.versions.toml`
  - `app/build.gradle.kts`
- 命令注册表：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalCommands.kt`
- 命令实现（独立目录）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/stock/StockCommand.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/stock/FinnhubClient.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/stock/StockRateLimiter.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/stock/StockSecrets.kt`
- 单测（Robolectric）：
  - `app/src/test/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecToolTest.kt`
- 内置 skill（assets + 安装）：
  - `app/src/main/assets/builtin_skills/stock-cli/SKILL.md`
  - `app/src/main/assets/builtin_skills/stock-cli/secrets/env.example`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`

## Steps（Strict / TDD 优先）

1) TDD Red：在 `TerminalExecToolTest.kt` 增加用例（OutRequired / MissingCredentials / PathEscapesAgentsRoot / NotSupported / happy path with fake HTTP / RateLimited）并跑到红  
2) TDD Green：实现 `StockSecrets` + `StockRateLimiter` + `FinnhubClient` + `StockCommand`，注册到 `TerminalCommands.defaultRegistry(...)`，跑到绿  
3) Refactor：收敛错误码/输出契约一致性（仍绿）  
4) 内置 Skill：新增 `builtin_skills/stock-cli/*` 并在 `AgentsWorkspace.ensureInitialized()` 安装 + seed secrets（仍绿）  
5) Verify：`.\gradlew.bat :app:testDebugUnitTest` exit code=0  
6) Ship：`git add -A ; git commit -m "v23: feat: stock cli" ; git push`  

## Risks

- 频控与可测性：rate limiter 若基于 sleep 会导致单测不稳定 → 必须可注入 clock/limiter。  
- 输出过大截断：symbols/news/earnings 可能非常大 → 必须 `--out` 落盘并返回 artifacts。  
- 免费 tier 覆盖变化：非 US 或 premium 数据不稳定 → v23 只保证 US 股票查询场景（仍以 PRD-0022 为准）。  

