# PRD-0031：ExchangeRate-API 汇率查询 Pseudo Terminal Commands（exchange-rate-cli）

日期：2026-02-19  
定位：在 `terminal_exec` 的“白名单 pseudo CLI（无 shell / 无外部进程）”框架下，新增一个**无需 API Key** 的汇率查询命令集，基于 ExchangeRate-API 的 Open Access 接口（`open.er-api.com`），提供「查询最新汇率 / 金额换算」的可审计、可缓存、可测试闭环能力。

## Vision

让用户与 Agent 都可以在 App 内仅通过 `terminal_exec`：

- 查询以某基准货币（base）计价的**最新（每日更新）**汇率，并按需筛选目标货币列表
- 进行金额换算（convert）：`amount * rate`
- 在网络异常、返回错误、参数非法时，得到**可解释、可程序化处理**的错误码与错误信息

并且：

- **无需密钥**：不引入 `.env` / secrets（此接口公开免费）
- **可缓存**：默认做本地缓存，避免高频请求公共接口
- **可审计**：每次调用都由 `terminal_exec` 自动审计落盘（工具已自带）
- **输出可控**：stdout/result 默认摘要；全量数据（160+ currencies）通过 `--out` 落盘并用 artifacts 引用

## Background（ExchangeRate-API Open Access）

- 接口地址：`GET https://open.er-api.com/v6/latest/{BASE_CURRENCY}`
- `{BASE_CURRENCY}`：ISO 4217 三位货币代码（如 `USD` / `CNY` / `JPY` / `EUR`）
- 响应关键字段：
  - `result`：成功为 `"success"`；失败为 `"error"`（并带 `error-type`）
  - `base_code`：基准货币
  - `rates`：以 `base_code=1` 时的各货币汇率映射
  - `time_last_update_utc` / `time_next_update_utc`：数据更新时间（每日一次）
- 特性与限制：
  - 无需注册、无需 API Key
  - 非实时，约 24 小时更新一次
  - 公共免费接口：必须做本地缓存与最小化请求

## Non-Goals（v31）

- 不做：实时/分钟级汇率（该接口非实时）
- 不做：历史汇率/时间序列
- 不做：高频轮询/订阅推送
- 不做：自动识别货币符号（￥/$/€）到 ISO 代码
- 不做：交易/投资建议/自动下单
- 不做：强依赖系统代理或在命令内配置代理（保持简单；必要时后续另立 PRD）

## Dependencies

- 网络权限与 HTTP 客户端：优先使用 OkHttp（跟随工程现状）；不得引入外部进程
- JSON 解析：跟随工程当前选择（Jackson/Gson 其一）
- 文件读写：仅限 App 内工作区 `.agents/`（含缓存与 artifacts）

## Project Rules（实现必须遵守：来自 paw-cli-add-workflow）

- `TerminalCommands.kt` 只做命令注册；命令实现独立文件/目录。
- 顶层命令建议使用独立目录（预计为中等规模）：
  - `exchange-rate`：`app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/exchange_rate/*`
- `TerminalExecTool` 的命令解析限制必须遵守（单行、禁用 `;`/`&&`/`|`/`>`/`<` 等）。
- **Help 必须可用**：`exchange-rate --help` / `exchange-rate help` / 子命令 help 均 `exit_code=0`。

## Command Set（v31 目标命令协议）

统一输出契约（所有 `exchange-rate *` 命令一致）：

- `exit_code`：0 成功；非 0 失败
- `stdout`：人类可读摘要（短，避免 16k 截断）
- `result`：结构化 JSON（稳定字段，给 Agent 消费）
- `artifacts[]`：需要全量输出/落盘时返回引用（`path/mime/description`）
- 失败必须提供：`error_code` + `error_message`

统一 guardrails：

- **默认不输出全量 rates**：未显式要求时，stdout/result 只返回摘要与指定 targets 的子集
- 所有落盘路径必须在 `.agents/` 内；拒绝绝对路径与 `..`（`PathEscapesAgentsRoot` / `InvalidArgs`）
- 网络请求需设置超时（connect/read），并将超时归一为 `NetworkError`

### 0) `exchange-rate help`（强制）

- 命令入口（必须全部支持）：
  - `exchange-rate --help`
  - `exchange-rate help`
  - `exchange-rate latest --help`
  - `exchange-rate help latest`
  - `exchange-rate convert --help`
  - `exchange-rate help convert`
- stdout：简洁 usage + examples（不超过 ~200 行；超长用 artifact）
- result（建议最小字段）：
  - `ok=true`
  - `command`
  - `usage`
  - `subcommands=["latest","convert"]`
  - `flags=[...]`
  - `examples=[...]`

### 1) `exchange-rate latest`

用途：查询某基准货币的最新汇率（每日更新）。

- 命令：
  - `exchange-rate latest --base CNY`
  - `exchange-rate latest --base CNY --symbols USD,EUR,JPY`
  - `exchange-rate latest --base CNY --out artifacts/exchange-rate/latest-CNY.json`
- 远端调用：
  - `GET https://open.er-api.com/v6/latest/CNY`
- 参数：
  - `--base <ISO4217>`（必填，3 位大写字母；实现应对大小写做容错：输入 `cny` 视作 `CNY`）
  - `--symbols <CSV>`（可选；逗号分隔；仅输出这些目标货币）
  - `--out <relpath>`（可选；若提供则落盘**全量**响应或全量 `rates`，并返回 artifact）
  - `--no-cache`（可选；跳过缓存，强制网络请求）
- 默认行为（无 `--out`）：
  - 若未提供 `--symbols`：仅返回**常用子集**（默认 targets：`USD,EUR,JPY,GBP,HKD`），并返回 `rates_total`
  - 若提供 `--symbols`：只返回 symbols 子集
- result（最小字段，成功）：
  - `ok=true`
  - `command="exchange-rate latest"`
  - `base_code`
  - `time_last_update_utc`
  - `time_next_update_utc`
  - `cached`（boolean）
  - `rates_total`（Int；远端 rates 的总币种数）
  - `rates`（Map：仅子集或 symbols）
  - `out`（若提供 `--out`）
  - `source_url="https://open.er-api.com/v6/latest/<BASE>"`

### 2) `exchange-rate convert`

用途：金额换算：`amount` 从 `from` 转到 `to`。

- 命令：
  - `exchange-rate convert --from CNY --to USD --amount 100`
  - `exchange-rate convert --from USD --to CNY --amount 12.34 --precision 4`
- 远端调用：
  - `GET https://open.er-api.com/v6/latest/<from>`
- 参数：
  - `--from <ISO4217>`（必填）
  - `--to <ISO4217>`（必填）
  - `--amount <decimal>`（必填；支持整数与小数；负数是否允许见 REQ-0031-007）
  - `--precision <0..10>`（可选；默认 6；用于 `converted_amount` 的四舍五入展示，不改变内部计算精度）
  - `--no-cache`（可选；同 `exchange-rate latest`）
- result（最小字段，成功）：
  - `ok=true`
  - `command="exchange-rate convert"`
  - `from`
  - `to`
  - `amount`
  - `rate`（`to` 相对 `from` 的汇率）
  - `converted_amount`
  - `time_last_update_utc`
  - `time_next_update_utc`
  - `cached`

## Cache（强制：公共接口降频）

缓存目标：将同一 `base` 的重复查询合并到“每日一次”量级；并允许显式 `--no-cache` 绕过。

建议策略（实现可调整，但必须可测试、可解释）：

- 缓存键：`base_code`（例如 `CNY`）
- 缓存内容：远端完整 JSON（含 `time_next_update_utc` 与 `rates`）
- 缓存目录（建议）：`.agents/cache/exchange-rate/`
- 命中条件：
  - 若缓存存在且当前时间 `< time_next_update_utc`：命中缓存
  - 否则：发起网络请求并覆盖缓存
- 失败回退：
  - 若网络请求失败且缓存存在且未过期：允许返回缓存并在 stdout/result 标注 `stale=true`（见 REQ-0031-010）

## Error Codes（统一口径）

- `InvalidArgs`：参数缺失/格式错误（含 ISO 代码不合法、CSV 解析失败、precision 越界）
- `UnknownCurrency`：`rates` 中找不到目标货币（`--symbols` 或 `--to`）
- `NetworkError`：网络/超时/无法解析/连接失败
- `RemoteError`：HTTP 2xx 但 `result!="success"`（包含 `error-type`）
- `RemoteHttpError`：非 2xx HTTP（包含 status）
- `CacheReadError` / `CacheWriteError`：缓存读写失败（不应崩溃）
- `OutRequired`：未来若新增会产生大输出的子命令，可复用此码（v31 暂不强制）
- `PathEscapesAgentsRoot`：`--out` 路径越界（含 `..` 或绝对路径）

## Requirements（Req IDs + 可二元验收）

### REQ-0031-001（命令注册与 help）

- 必须新增顶层命令 `exchange-rate`，并注册到 `TerminalCommands.defaultRegistry(...)`。
- `exchange-rate --help` / `exchange-rate help` 必须 `exit_code=0` 且返回包含 `latest`/`convert` 的 usage。

验收（自动化）：
- `TerminalExecToolTest` 里执行 `tool.exec("exchange-rate --help")`：`exit_code==0` 且 stdout 含 `exchange-rate latest` 与 `exchange-rate convert`。

### REQ-0031-002（latest 基础功能）

- `exchange-rate latest --base <BASE>` 成功时必须返回 `base_code==<BASE>`，并包含 `time_last_update_utc/time_next_update_utc/rates_total/rates`。

验收（自动化）：
- 在可控的 fake HTTP 响应（MockWebServer）下断言 result 字段齐全，`rates_total>=1`。

### REQ-0031-003（symbols 过滤）

- `exchange-rate latest --base CNY --symbols USD,EUR` 仅返回请求的两个币种（且顺序不做强约束）。

验收（自动化）：
- `result.rates` 仅包含 `USD` 与 `EUR` 两个 key（无其他）。

### REQ-0031-004（out 落盘与 artifacts）

- `exchange-rate latest --base CNY --out artifacts/exchange-rate/latest-CNY.json` 必须在 `.agents/` 下落盘，并在 `artifacts[]` 返回该文件引用。

验收（自动化）：
- 断言 artifact `path` 存在且可读；且 `path` 以 `.agents/` 开头。

### REQ-0031-005（convert 换算）

- `exchange-rate convert --from CNY --to USD --amount 100` 必须返回 `rate` 与 `converted_amount`，并满足：`converted_amount == round(amount * rate, precision)`。

验收（自动化）：
- 在固定 rates 输入下断言数值正确（避免浮点误差：使用 BigDecimal 或等效策略）。

### REQ-0031-006（缓存命中与 no-cache）

- 默认情况下，同一 `base` 在未过期窗口内重复调用必须命中缓存并标注 `cached=true`。
- 显式 `--no-cache` 必须强制走网络并标注 `cached=false`（即便缓存存在）。

验收（自动化）：
- 用计数器断言网络调用次数符合预期（1 次 vs 2 次）。

### REQ-0031-007（amount 合法性边界）

- `--amount` 必须拒绝非数字输入（例如 `abc`）。
- `--amount` 是否允许负数：
  - **默认建议：拒绝负数**（`InvalidArgs`），避免语义歧义；如需支持，必须在 help 中明确写出并补充测试。

验收（自动化）：
- `exchange-rate convert ... --amount abc` 返回 `exit_code!=0` 且 `error_code="InvalidArgs"`。

### REQ-0031-008（远端 error 映射）

- 当远端返回 `{"result":"error","error-type":"..."}`
  - 必须映射为 `error_code="RemoteError"`，并在 `error_message` 中携带 `error-type`。

验收（自动化）：
- fake 响应断言 `error_code/error_message`。

### REQ-0031-009（UnknownCurrency）

- 当 `--symbols` 或 `--to` 指定的货币在 `rates` 中不存在，必须返回 `error_code="UnknownCurrency"`。

验收（自动化）：
- fixed rates 下请求不存在的 code，断言错误码与消息包含 code。

### REQ-0031-010（网络失败的缓存回退）

- 若网络请求失败：
  - 若存在**未过期**缓存：允许回退返回缓存，并标注 `cached=true`（或 `stale=false`）
  - 若仅存在**过期**缓存：允许可选回退（实现可选），但必须标注 `stale=true`
  - 若无缓存：返回 `NetworkError`

验收（自动化）：
- 分三种场景断言行为稳定（不崩溃、错误码可解释、stale 标注正确）。

## Open Questions（写入 v1 计划前必须定稿）

1) `exchange-rate latest` 默认 targets 子集是否按：`USD,EUR,JPY,GBP,HKD`？是否需要加入 `AUD,CAD,SGD,KRW`？  
3) `--amount` 是否允许负数？（见 REQ-0031-007：默认建议拒绝）  
