# v34 Index：ExchangeRate-API 汇率查询 CLI（terminal_exec exchange-rate）

日期：2026-02-19

## Vision（引用）

- PRD：`docs/prd/PRD-0031-exchange-rate-cli.md`
- 方法论引用：`tashan-development-loop`（PRD ↔ plan ↔ tests ↔ code 可追溯）、`paw-cli-add-workflow`（白名单 pseudo CLI：无 shell / 可审计落盘 / help 必须可用 / TDD）
- 本轮目标（v34）：在 `terminal_exec` 下新增顶层命令 `exchange-rate`，实现最新汇率查询（latest）与金额换算（convert），并内建本地缓存以降低公共接口请求频率；同时提供内置 skill `exchange-rate-cli` 作为“可复用说明书”。

## Milestones

### M1：`terminal_exec exchange-rate` 最小闭环（help/latest/convert + cache + --out artifacts）

- PRD Trace：
  - PRD-0031：REQ-0031-001 ~ REQ-0031-010
- DoD（硬口径）：
  - `TerminalCommands.defaultRegistry(...)` 注册 `exchange-rate` 顶层命令；
  - `exchange-rate --help` / `exchange-rate help` / `exchange-rate <sub> --help` / `exchange-rate help <sub>` 全部可用，且 `exit_code=0`；
  - `exchange-rate latest --base CNY` 可用（默认子集输出；可 `--symbols` 过滤；可 `--out` 落盘并返回 artifacts）；
  - `exchange-rate convert --from CNY --to USD --amount 100` 可用，数值计算稳定（BigDecimal）；
  - 默认启用缓存：同一 base 在未过期窗口内重复调用命中缓存（`cached=true`）；`--no-cache` 强制走网络；
  - 网络失败时的缓存回退行为满足 PRD-0031：REQ-0031-010；
  - Verify：`.\gradlew.bat :app:testDebugUnitTest` exit code=0（覆盖：help + 成功/失败路径 + 缓存命中/no-cache + out 落盘 + UnknownCurrency + RemoteError 映射）。

## Plan Index

- `docs/plan/v34-exchange-rate-cli.md`

## ECN Index

- （本轮无）

## Review（Evidence）

- Unit tests：`.\gradlew.bat :app:testDebugUnitTest` ✅（2026-02-19）
- Install debug（smoke）：`.\gradlew.bat :app:installDebug`（本轮不强制；CLI 为核心验收）
