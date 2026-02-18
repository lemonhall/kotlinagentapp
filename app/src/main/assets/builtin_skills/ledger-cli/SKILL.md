---
name: ledger-cli
description: 个人简单记账（本地流水）skill：通过 `terminal_exec` 执行白名单命令 `ledger`（init/add/list/summary），并用 `--out` 落盘大输出供 artifacts 引用。
---

# Ledger CLI（个人简单记账 / 本地流水）

## Goal

在 App 内工作区 `.agents/workspace/ledger/` 提供最小闭环记账能力：初始化、追加流水、查询与月度汇总。

## Commands

使用工具 `terminal_exec`，严格按单行命令调用（禁止 `;`、`|`、`>` 等 shell token）。

### 1) 初始化工作区

- `ledger init`

如果已存在且非空，必须：

- `ledger init --confirm`

### 2) 记一笔（支出/收入/转账）

- 支出：
  - `ledger add --type expense --amount 12.34 --category 餐饮 --account 微信 --note 午饭`
- 收入：
  - `ledger add --type income --amount 3000 --category 工资 --account 银行卡`
- 转账：
  - `ledger add --type transfer --amount 200 --from 微信 --to 银行卡 --note 提现`

### 3) 列表（默认摘要；完整明细用 --out）

- 摘要：
  - `ledger list --max 50`
- 完整明细（落盘 + artifacts）：
  - `ledger list --month 2026-02 --out artifacts/ledger/ledger-2026-02.json`

### 4) 汇总（默认摘要；完整明细用 --out）

- `ledger summary --month 2026-02 --by category --out artifacts/ledger/summary-2026-02.json`

## Verification

- 成功：`exit_code == 0`，且 `result.ok == true`
- 工作区文件存在：
  - `.agents/workspace/ledger/meta.json`
  - `.agents/workspace/ledger/categories.json`
  - `.agents/workspace/ledger/accounts.json`
  - `.agents/workspace/ledger/transactions.jsonl`
- 当使用 `--out`：`artifacts[]` 必须包含对应的 `.agents/<out_path>` 引用

## Rules

- 必须实际调用 `terminal_exec`，不要臆造 stdout/result/artifacts。
- 若工具返回 `exit_code != 0` 或包含 `error_code`，直接说明错误并停止。
- 任何写入路径必须在 `.agents/` 内；拒绝绝对路径与 `..`。

