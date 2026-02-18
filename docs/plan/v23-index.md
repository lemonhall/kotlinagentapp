# v23 Index：Stock CLI（实现 + 测试 + 内置 Skill）

日期：2026-02-18

## Vision（引用）

- PRD：`docs/prd/PRD-0022-finnhub-stock-cli.md`
- v22（协议与验收口径已锁）：`docs/plan/v22-stock-cli.md`
- 本轮目标（v23）：按 v22 硬口径，完成 `terminal_exec` 白名单命令 `stock` 的 TDD 落地（quote/profile/symbols/company-news/news/rec/metric/earnings），并新增内置 Skill `stock-cli`（assets → `.agents/skills` 安装链路），保证可审计、可验证、可回归。

## Milestones

### M1：Stock CLI 最小闭环（实现 + 单测）

- PRD Trace：
  - PRD-0022：REQ-0022-001 / REQ-0022-002 / REQ-0022-003 / REQ-0022-004
  - PRD-0022：REQ-0022-005 / REQ-0022-006 / REQ-0022-007 / REQ-0022-008
  - PRD-0022：REQ-0022-010 / REQ-0022-011 / REQ-0022-012 / REQ-0022-013 / REQ-0022-014 / REQ-0022-020
- DoD（硬口径）：
  - `terminal_exec` 注册顶层命令 `stock`，并实现 `quote/profile/symbols/company-news/news/rec/metric/earnings`；
  - 密钥读取固定为 `.agents/skills/stock-cli/secrets/.env`，缺 `FINNHUB_API_KEY` 必须失败（`MissingCredentials`），且 stdout/result 不包含 key；
  - `stock symbols` 缺 `--out` 必须失败（`OutRequired`）；
  - 所有 `--out` 路径必须受 `.agents` 根目录约束：拒绝绝对路径与 `..`（`PathEscapesAgentsRoot` / `InvalidArgs`）；
  - 禁止 candle：任何 candle 相关子命令必须失败（`NotSupported`），且实现不得出现 `/stock/candle` 调用；
  - 频控：实现必须内建 rate limiter（目标 `<= 55 req/min`），429 口径为 `RateLimited`；
  - Tests：`.\gradlew.bat :app:testDebugUnitTest` exit code=0。
- Plan：`docs/plan/v23-stock-cli-impl.md`

### M2：内置 Skill（assets → 安装 → secrets seed）

- PRD Trace：
  - PRD-0022：REQ-0022-010 / REQ-0022-020
- DoD（硬口径）：
  - `app/src/main/assets/builtin_skills/stock-cli/SKILL.md` 存在，且明确要求调用 `terminal_exec`；
  - `AgentsWorkspace.ensureInitialized()` 会安装 `stock-cli` 到 `.agents/skills/stock-cli/`；
  - 若 `.agents/skills/stock-cli/secrets/.env` 不存在：会从 `builtin_skills/stock-cli/secrets/env.example` seed 一份（仅首次；不覆盖用户 secrets）；
  - Tests：`.\gradlew.bat :app:testDebugUnitTest` exit code=0。

## Plan Index

- `docs/plan/v23-stock-cli-impl.md`

## ECN Index

- （本轮无）

