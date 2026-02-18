# v21 Plan：Ledger CLI（实现 + 单测 + 内置 Skill）

## Goal

按 `docs/prd/PRD-0020-ledger-cli.md` 与 `docs/plan/v20-ledger-cli.md` 的硬口径，实现 `terminal_exec` 的 `ledger` 白名单命令，并提供内置 Skill `ledger-cli`，确保“命令 → 落盘 → artifacts → 单测”闭环可复现。

## PRD Trace

- PRD-0020：REQ-0020-001 / REQ-0020-002 / REQ-0020-003 / REQ-0020-004
- PRD-0020：REQ-0020-010 / REQ-0020-011 / REQ-0020-012
- PRD-0020：REQ-0020-020

## Scope

做（v21）：
- 实现 `ledger init/add/list/summary`（最小闭环）
- 落盘到 `.agents/workspace/ledger/`（4 文件 + JSONL 交易）
- 支持 `--out` 落盘大输出并返回 `artifacts[]` 引用
- 单测覆盖：NotInitialized / ConfirmRequired / --out / 路径越界 / 真实写入
- 新增内置 Skill：`ledger-cli`

不做（v21）：
- 分类/账户的增删改（只写预置文件）
- 导入（微信/支付宝）、预算、多币种、云同步
- 删除/回滚交易（append-only）

## Acceptance（硬口径）

1. `ledger init`：首次初始化成功；若 ledger 目录存在且非空，缺 `--confirm` 必拒绝（`ConfirmRequired`）。  
2. `ledger add`：支持 `expense/income/transfer`，并真实追加写入 `.agents/workspace/ledger/transactions.jsonl`；未 init 必拒绝（`NotInitialized`）。  
3. `ledger list`：默认仅输出摘要（stdout/result 不含完整明细）；提供 `--out` 时落盘完整 JSON 并在 `artifacts[]` 返回 `.agents/<out_path>`。  
4. `ledger summary`：按 `--month YYYY-MM` 输出汇总摘要；提供 `--out` 时落盘完整 JSON 并返回 artifact。  
5. 路径约束：所有写入必须在 `.agents/` 内，拒绝绝对路径与 `..`（`PathEscapesAgentsRoot` / `InvalidArgs`）。  
6. Tests：`.\gradlew.bat :app:testDebugUnitTest` exit code=0。  

反作弊条款（必须）：
- 单测必须断言：`transactions.jsonl` 至少包含 1 行可解析 JSON（不是空实现/假输出）。

## Files

- 命令注册表：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalCommands.kt`
- 命令实现（独立目录）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/ledger/LedgerCommand.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/ledger/LedgerStore.kt`
- 单测（Robolectric）：
  - `app/src/test/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecToolTest.kt`
- 内置 skill（assets + 安装）：
  - `app/src/main/assets/builtin_skills/ledger-cli/SKILL.md`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`

## Steps（Strict / TDD 优先）

1) TDD Red：在 `TerminalExecToolTest.kt` 增加用例（init/add/list/summary + --out + ConfirmRequired + NotInitialized + 路径越界）并跑到红  
2) TDD Green：实现 `LedgerStore` + `LedgerCommand`，注册到 `TerminalCommands.defaultRegistry(...)`，跑到绿  
3) Refactor：收敛错误码/输出契约一致性（仍绿）  
4) 内置 Skill：新增 `builtin_skills/ledger-cli/SKILL.md` 并在 `AgentsWorkspace.ensureInitialized()` 安装（仍绿）  
5) Verify：`.\gradlew.bat :app:testDebugUnitTest` exit code=0  
6) Ship：`git add -A ; git commit -m "v21: feat: ledger cli" ; git push`  

## Risks

- 参数与数据模型漂移：必须以 PRD-0020 / v20 文档为准；新增需求走新 PRD/vN。  
- 输出过大被截断：完整明细必须走 `--out` 落盘。  
- 路径安全：任何写入统一通过 `.agents` 根目录约束，避免越界写入。  

