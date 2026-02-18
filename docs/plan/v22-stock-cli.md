# v22 Plan：Stock CLI（Finnhub 股市数据 / 文档锁口径）

## Goal

把“股市数据（Finnhub）”能力用 `terminal_exec` 的白名单命令 `stock` 表达出来，并把密钥存储（skill-local `.env`）、频控（rate limiter）、`--out` artifacts、禁止 candle 等 guardrails 写死在验收口径里；v22 **只落文档，不写实现**。

## PRD Trace

- PRD-0022：REQ-0022-001 / REQ-0022-002 / REQ-0022-003 / REQ-0022-004
- PRD-0022：REQ-0022-005 / REQ-0022-006 / REQ-0022-007 / REQ-0022-008
- PRD-0022：REQ-0022-010 / REQ-0022-011 / REQ-0022-012 / REQ-0022-013 / REQ-0022-014
- PRD-0022：REQ-0022-020

## Scope

做（v22：文档阶段）：
- 锁定命令集合与 argv 约定（`stock quote/profile/symbols/company-news/news/rec/metric/earnings`）
- 锁定密钥存储（`.agents/skills/stock-cli/secrets/.env`）与脱敏规则
- 锁定 Finnhub 调用约束（免费 tier、禁用 candle、429 口径）
- 锁定输出契约（stdout/result/artifacts/exit_code/error_code）
- 锁定 guardrails：
  - 大输出必须 `--out` 落盘 + artifacts 引用（至少 symbols 强制）
  - 全局请求 rate limiter（目标 `<= 55 req/min`）

不做（v22）：
- 任何代码实现（命令、单测、builtin skill 安装）
- WebSocket 实时推送
- 历史 K 线（candles）

## Acceptance（硬口径，面向后续实现）

1. `stock symbols` 缺 `--out` 必拒绝（`OutRequired`）。  
2. 所有 `--out` 路径必须在 `.agents/` 内，拒绝绝对路径与 `..`（`PathEscapesAgentsRoot` / `InvalidArgs`）。  
3. 密钥读取固定为 `.agents/skills/stock-cli/secrets/.env`，缺 `FINNHUB_API_KEY` 返回 `MissingCredentials`，且 stdout/result 不包含 key。  
4. 禁止 candle：任何 candle 相关能力必须返回 `NotSupported`，并且实现中不得出现 `/stock/candle` 调用。  
5. 频控：实现必须内建 rate limiter（目标 `<= 55 req/min`），遇到 429 输出 `RateLimited`。  
6. `.\gradlew.bat :app:testDebugUnitTest` exit code=0（后续实现时必须补齐覆盖用例）。  

反作弊条款（必须）：
- 禁止“只注册命令但返回假数据”就宣称完成：后续单测必须用 fake HTTP/fixture 断言请求确实被构造（含 endpoint/path/headers），并断言 artifacts 文件真实落盘存在且包含关键字段。  

## Files（规划：遵守 paw-cli-add-workflow；v22 不落地代码）

> 下列为后续实现阶段预计会改动/新增的路径清单（v22 只写计划，不实际创建）。

- 注册表（只注册，不写实现）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalCommands.kt`
- 命令实现（拆分目录）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/stock/StockCommand.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/stock/FinnhubClient.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/stock/StockRateLimiter.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/stock/StockSecrets.kt`
- 单测（Robolectric）：
  - `app/src/test/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecToolTest.kt`
- 内置 skill（让 Agent 能按说明书调用）：
  - `app/src/main/assets/builtin_skills/stock-cli/SKILL.md`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`

## Steps（Strict）

> v22 只做文档闭环，因此步骤只覆盖 Analysis/Design/Plan + Gate，不进入实现。

1) Analysis：确认免费 tier 约束与核心接口集合（quote/profile/symbols/news/recommendation/metric/earnings）  
2) Design：锁定命令协议、错误码与输出契约（何时必须 `--out`）  
3) Plan：写清 Files / 验收口径 / 未来测试点（MissingCredentials、RateLimited、OutRequired、PathEscapesAgentsRoot、NotSupported）  
4) DoD Gate：检查 Acceptance 全部可二元判定且有明确验证命令/断言口径  
5) Doc QA Gate：术语一致（stock/finnhub/token/out/artifacts/.env），Req IDs 追溯不断链  
6) Ship（文档）：`git add -A ; git commit -m "v22: doc: finnhub stock cli spec" ; git push`  

## Risks

- 频控与可测性：rate limiter 若只做“睡眠”会导致单测不稳定 → 后续实现需可注入 clock/limiter。  
- 输出过大截断：symbols/news/earnings 可能非常大 → 必须 `--out` 落盘并返回 artifacts。  
- 免费 tier 覆盖变化：非 US 或 premium 数据不稳定 → v22 明确只保证 US 股票查询场景。  

