# PRD-0020：个人记账 Ledger CLI（本地流水 / 分类 / 账户）Pseudo Terminal Commands

日期：2026-02-18  
定位：在 `terminal_exec` 的“白名单 pseudo CLI（无 shell / 无外部进程）”框架下，为 App 内工作区提供一个**简单**、符合中国日常习惯的“个人记账”能力：流水录入、查询、月度汇总（本地存储、可审计、可测试）。

## Vision

让用户与 Agent 都可以在 App 内仅通过 `terminal_exec`：

- 快速记录一笔支出/收入/转账（默认币种 CNY）
- 查看最近流水（可过滤：月份/账户/分类）
- 生成月度汇总（按分类、按账户），必要时用 `--out` 落盘完整 JSON

并且：

- **本地优先**：数据只存 `.agents/workspace/ledger/`，不依赖网络
- **输出可控**：stdout/result 默认输出摘要；大输出必须 `--out` 落盘并通过 artifacts 引用
- **写操作可追溯**：命令行为可通过审计 runs 追溯（`terminal_exec` 自带）

## Non-Goals（v20）

- 不做：微信/支付宝/银行卡账单导入（后续版本再做）
- 不做：预算、分期、资产负债表、复式记账
- 不做：OCR 小票、语音记账
- 不做：云同步/多设备合并
- 不做：多币种（默认 CNY）

## Dependencies

- 无网络依赖
- 时间与时区：使用 Android 本机时间/时区（系统提示词已注入 time/zone；命令也可显式 `--at`）

## Project Rules（实现必须遵守：来自 paw-cli-add-workflow）

- `TerminalCommands.kt` 只能做“命令注册表/默认 registry 构建”，禁止堆实现逻辑。
- 顶层命令必须独立目录：
  - `ledger`：`app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/ledger/*`
- `TerminalExecTool` 的 registry 初始化逻辑不改；只在 `TerminalCommands.defaultRegistry(...)` 注册新增命令。

## Data Model（最小约定，面向 v20）

工作区目录：

- `.agents/workspace/ledger/`
  - `meta.json`（版本、创建时间、本地时区）
  - `categories.json`（预置分类树：支出/收入）
  - `accounts.json`（预置账户：现金/银行卡/微信/支付宝）
  - `transactions.jsonl`（一行一笔交易；append-only）

交易类型：

- `expense`：支出（金额为正，语义为“流出”）
- `income`：收入（金额为正，语义为“流入”）
- `transfer`：转账（`from_account` → `to_account`，金额为正）

金额：

- 统一以 CNY 为默认语义；实现可内部用“分（fen）整数”存储，避免浮点误差。

## Command Set（v20）

统一约束：

- 所有命令输出同时提供：`stdout`（摘要）+ `result`（结构化）+（必要时）`artifacts[]`（落盘大输出）。
- stdout/result 默认脱敏与可读：不输出超长 note；必要时仅输出前 80 字并标记 truncated。
- 所有落盘路径必须在 `.agents/` 内；`--out` 返回 `.agents/<out_path>` artifact 引用。

### 1) `ledger init`

- 命令：
  - `ledger init [--confirm]`
- 行为：
  - 创建/补齐 `.agents/workspace/ledger/` 及 4 个文件
  - 若目录已存在且非空：缺 `--confirm` 必拒绝（防止误覆盖/误重置）
- result（最小字段）：
  - `ok`
  - `command: "ledger init"`
  - `workspace_dir`
  - `created: boolean`

### 2) `ledger add`

- 命令（支出/收入）：
  - `ledger add --type expense|income --amount <yuan> --category <name> --account <name> [--note <text>] [--at <iso8601>]`
- 命令（转账）：
  - `ledger add --type transfer --amount <yuan> --from <account> --to <account> [--note <text>] [--at <iso8601>]`
- 行为：
  - 追加写入一条交易到 `transactions.jsonl`
  - 如未 init：返回 `NotInitialized`
- result（最小字段）：
  - `ok`
  - `command: "ledger add"`
  - `tx_id`（稳定 ID，例如 UUID）
  - `type`
  - `amount_yuan` / `amount_fen`
  - `saved_path`（固定为 `.agents/workspace/ledger/transactions.jsonl`）

### 3) `ledger list`（可选 `--out`）

- 命令：
  - `ledger list [--month YYYY-MM] [--account <name>] [--category <name>] [--max <n>] [--out <path>]`
- 行为：
  - 默认仅输出最近 `--max`（默认 50）条的摘要
  - 若提供 `--out`：落盘完整 JSON 列表并返回 artifact
- result（最小字段）：
  - `ok`
  - `command: "ledger list"`
  - `count_total`
  - `count_emitted`
  - `filters: {month?, account?, category?}`
  - `out?`

### 4) `ledger summary`（可选 `--out`）

- 命令：
  - `ledger summary --month YYYY-MM [--by category|account] [--out <path>]`
- 行为：
  - 输出该月支出/收入汇总（按分类或按账户）
  - `--out`：落盘完整 JSON 明细并返回 artifact
- result（最小字段）：
  - `ok`
  - `command: "ledger summary"`
  - `month`
  - `by`
  - `expense_total_fen` / `income_total_fen`
  - `out?`

### 5) 预留（后续版本）

- `ledger categories ...`（自定义分类）
- `ledger accounts ...`（自定义账户）
- `ledger delete ... --confirm`（删除/回滚）
- `ledger import wechat|alipay ...`（账单导入）

## Safety（必须）

1) 路径与落盘  
- 任何写文件只能写入 `.agents/` 内部路径；`--out` 必须受 `resolveWithinAgents` 约束。

2) 大输出落盘  
- list/summary 的“完整明细”不得直接塞 stdout/result；必须 `--out` 落盘 + artifacts 返回。

3) 破坏性操作确认  
- `ledger init` 若检测到已有非空 ledger 目录：缺 `--confirm` 必须拒绝（`ConfirmRequired`）。

## Acceptance（v20 文档口径）

1. `terminal_exec` 新增顶层命令 `ledger`，并实现 `init/add/list/summary`（最小闭环）。
2. 默认存储目录固定：`.agents/workspace/ledger/`，且包含 `meta.json/categories.json/accounts.json/transactions.jsonl`。
3. `ledger list/summary` 支持 `--out`，并通过 `artifacts[]` 返回 `.agents/<out_path>` 引用。
4. `ledger init` 在“目录已存在且非空”时缺 `--confirm` 必须拒绝（`ConfirmRequired`）。
5. `.\gradlew.bat :app:testDebugUnitTest` exit code=0（覆盖：NotInitialized、ConfirmRequired、--out artifacts、基本写入与可重复性）。

## Requirements（Req IDs）

### A. 命令面

- REQ-0020-001：新增 `ledger init` 并初始化最小工作区文件集合。
- REQ-0020-002：新增 `ledger add` 支持 expense/income/transfer，并追加写入 `transactions.jsonl`。
- REQ-0020-003：新增 `ledger list` 支持过滤/限制输出，并可 `--out` 落盘完整 JSON。
- REQ-0020-004：新增 `ledger summary` 生成月度汇总，并可 `--out` 落盘完整 JSON。

### B. 安全/可用性

- REQ-0020-010：默认 stdout/result 仅输出摘要；完整明细必须 `--out` 落盘 + artifacts 引用。
- REQ-0020-011：路径必须受 `.agents` 根目录约束，拒绝绝对路径与 `..`。
- REQ-0020-012：破坏性操作（v20：init 覆盖/重置）必须 `--confirm`。

### C. 工程规矩

- REQ-0020-020：命令实现必须放在独立目录 `commands/ledger/*`；`TerminalCommands.kt` 只做注册表。

