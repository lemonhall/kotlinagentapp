# v34：ExchangeRate-API 汇率查询 CLI（terminal_exec exchange-rate）

日期：2026-02-19

## Goal

实现 PRD-0031 的 `exchange-rate` 顶层命令，提供：

- `exchange-rate latest`：按 base 查询最新（每日更新）汇率，支持 `--symbols` 子集输出与 `--out` 落盘
- `exchange-rate convert`：按 from/to/amount 进行金额换算
- 内建缓存与网络失败回退，避免高频调用公共接口
- 内置 skill：`exchange-rate-cli`

## PRD Trace

- PRD：`docs/prd/PRD-0031-exchange-rate-cli.md`
- Req IDs：REQ-0031-001 ~ REQ-0031-010

## Scope

做：

- 新增 `terminal_exec exchange-rate ...` 命令集（help/latest/convert）
- 新增 ExchangeRate HTTP client（可注入 transport test hooks）
- 新增缓存落盘到 `.agents/cache/exchange-rate/`
- 新增 builtin skill 文档并在 `AgentsWorkspace.ensureInitialized()` 安装

不做：

- 历史汇率/时间序列
- 实时/分钟级刷新
- 代理硬编码到仓库/命令（仅依赖系统/环境层）

## Acceptance（可验证）

以单测为主（Robolectric）：

- `exchange-rate --help` 等 help 入口全部 `exit_code=0` 且 `result.ok=true`
- `exchange-rate latest --base CNY --symbols USD,EUR` 仅返回 USD/EUR
- `exchange-rate latest --base CNY --out artifacts/exchange-rate/latest-CNY.json` 返回 artifacts 且文件存在
- `exchange-rate convert --from CNY --to USD --amount 100` 返回 `rate` 与 `converted_amount`（按 precision 四舍五入）
- 缓存命中：同一 base 重复调用网络仅 1 次
- `--no-cache`：重复调用网络变为 2 次
- UnknownCurrency/RemoteError/NetworkError 映射稳定

## Files（预计变更）

- `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/exchange_rate/ExchangeRateClient.kt`（新增）
- `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/exchange_rate/ExchangeRateCommand.kt`（新增）
- `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalCommands.kt`（注册）
- `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`（安装 builtin skill）
- `app/src/main/assets/builtin_skills/exchange-rate-cli/SKILL.md`（新增）
- `app/src/test/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecToolTest.kt`（新增测试）

## Steps（Strict）

1) TDD Red：为 help/latest/convert/out/cache/no-cache/错误映射写失败测试
2) 跑到红：`.\gradlew.bat :app:testDebugUnitTest`
3) TDD Green：实现 ExchangeRateClient（transport hooks + JSON 解析 + 错误映射）
4) 实现 ExchangeRateCommand（参数解析 + 缓存 + `--out` + BigDecimal 计算）
5) 注册命令 + builtin skill + AgentsWorkspace 安装
6) 跑到绿：`.\gradlew.bat :app:testDebugUnitTest`

## Risks

- ExchangeRate 时间字符串格式（RFC1123 + `+0000`）解析差异：用 `SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)` 并在测试覆盖。
- 公共接口不稳定或字段变化：忽略未知字段；错误映射以 `result` 字段为准；测试使用 fake transport 固化响应。

