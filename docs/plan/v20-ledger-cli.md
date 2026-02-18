# v20 Plan：Ledger CLI（个人简单记账 / 本地流水）

## Goal

把“个人简单记账”能力用 `terminal_exec` 的白名单命令 `ledger` 表达出来，并把落盘路径、输出契约、确认门禁与 `--out` artifacts 写死在验收口径里；v20 **只落文档，不写实现**。

## PRD Trace

- PRD-0020：REQ-0020-001 / REQ-0020-002 / REQ-0020-003 / REQ-0020-004
- PRD-0020：REQ-0020-010 / REQ-0020-011 / REQ-0020-012
- PRD-0020：REQ-0020-020

## Scope

做（v20：文档阶段）：
- 锁定命令集合与 argv 约定（`ledger init/add/list/summary`）
- 锁定工作区落盘结构（`.agents/workspace/ledger/`）
- 锁定输出契约（stdout/result/artifacts/exit_code/error_code）
- 锁定 guardrails：
  - `init` 覆盖/重置必须 `--confirm`
  - 大输出必须 `--out` 落盘 + artifacts 引用

不做（v20）：
- 任何代码实现（命令、单测、builtin skill 安装）
- 微信/支付宝导入、预算、复式记账、多币种、云同步

## Acceptance（硬口径，面向后续实现）

1. `ledger init` 存在：当 ledger 目录已存在且非空时缺 `--confirm` 必拒绝（`ConfirmRequired`）。  
2. `ledger add` 支持三类：`expense/income/transfer`，并追加写入 `.agents/workspace/ledger/transactions.jsonl`。  
3. `ledger list/summary` 默认只输出摘要；完整明细必须 `--out` 落盘，并在 tool output 的 `artifacts[]` 返回 `.agents/<out_path>`。  
4. 所有落盘都必须在 `.agents/` 内，拒绝绝对路径与 `..`（`PathEscapesAgentsRoot` / `InvalidArgs`）。  
5. `.\gradlew.bat :app:testDebugUnitTest` exit code=0（后续实现时必须补齐覆盖用例）。  

反作弊条款（必须）：
- 禁止“只把命令注册进白名单但返回空实现/假输出”就宣称完成：单测必须验证真实文件落盘存在且内容包含关键字段（例如 `transactions.jsonl` 至少有 1 行 JSON）。  

## Files（规划：遵守 paw-cli-add-workflow；v20 不落地代码）

> 下列为后续实现阶段预计会改动/新增的路径清单（v20 只写计划，不实际创建）。

- 注册表（只注册，不写实现）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalCommands.kt`
- 命令实现（拆分目录）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/ledger/LedgerCommand.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/ledger/*`（可选：落盘/查询/汇总核心逻辑）
- 单测（Robolectric）：
  - `app/src/test/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecToolTest.kt`
- 内置 skill（让 Agent 能按说明书调用）：
  - `app/src/main/assets/builtin_skills/ledger-cli/SKILL.md`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`

## Steps（Strict）

> v20 只做文档闭环，因此步骤只覆盖 Analysis/Design/Plan + Gate，不进入实现。

1) Analysis：确认中国日常习惯下的最小要素（账户/分类/转账）与最小文件集合（4 个文件）  
2) Design：锁定命令协议与输出契约（哪些字段必须进 result，哪些只能进 artifacts）  
3) Plan：写清 Files / 验收口径 / 未来测试点（NotInitialized、ConfirmRequired、--out、路径越界）  
4) DoD Gate：检查 Acceptance 全部可二元判定且有明确验证命令/断言口径  
5) Doc QA Gate：术语一致（ledger/workspace/out/artifacts），Req IDs 追溯不断链  
6) Ship（文档）：`git add -A ; git commit -m "v20: doc: ledger cli spec" ; git push`  

## Risks

- 需求膨胀：一旦加入导入/预算/多币种，会把简单流水变成复杂账本 → v20 明确不做。  
- 可用性：命令参数过多会降低“快速记一笔”的体验 → 后续实现可考虑提供 `ledger quick ...`，但需走新 PRD/计划。  
- 数据一致性：append-only JSONL 简单但要考虑转账的双腿一致性 → v20 先定义最小字段，后续实现再补校验与修复命令。  

