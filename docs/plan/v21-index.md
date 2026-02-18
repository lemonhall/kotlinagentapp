# v21 Index：Ledger CLI（实现 + 测试 + 内置 Skill）

日期：2026-02-18

## Vision（引用）

- PRD：`docs/prd/PRD-0020-ledger-cli.md`
- v20：`docs/plan/v20-ledger-cli.md`（协议与验收口径已写死）
- 本轮目标（v21）：按 v20 文档口径，完成 `terminal_exec` 白名单命令 `ledger` 的 TDD 落地（init/add/list/summary），并新增内置 Skill `ledger-cli`（assets → `.agents/skills` 安装链路），保证可审计、可验证、可回归。

## Milestones

### M1：Ledger CLI 最小闭环（实现 + 单测）

- PRD Trace：
  - PRD-0020：REQ-0020-001 / REQ-0020-002 / REQ-0020-003 / REQ-0020-004
  - PRD-0020：REQ-0020-010 / REQ-0020-011 / REQ-0020-012 / REQ-0020-020
- DoD（硬口径）：
  - `terminal_exec` 注册顶层命令 `ledger`，并实现 `init/add/list/summary`；
  - 默认工作区：`.agents/workspace/ledger/`，且包含 `meta.json/categories.json/accounts.json/transactions.jsonl`；
  - `ledger init` 在“目录已存在且非空”时缺 `--confirm` 必须拒绝（`ConfirmRequired`）；
  - `ledger add` 未 init 必须拒绝（`NotInitialized`），并真实追加写入 `transactions.jsonl`（单测断言至少 1 行 JSON）；
  - `ledger list/summary` 默认仅输出摘要；完整明细必须 `--out` 落盘，并在 `artifacts[]` 返回 `.agents/<out_path>`；
  - `--out` 必须受 `.agents` 根目录约束：拒绝绝对路径与 `..`（`PathEscapesAgentsRoot` / `InvalidArgs`）；
  - Tests：`.\gradlew.bat :app:testDebugUnitTest` exit code=0。
- Plan：`docs/plan/v21-ledger-cli-impl.md`

### M2：内置 Skill（assets → 安装 → 可用）

- PRD Trace：
  - PRD-0020：REQ-0020-020（工程规矩：命令与 skill 的可追溯闭环）
- DoD（硬口径）：
  - `app/src/main/assets/builtin_skills/ledger-cli/SKILL.md` 存在，且明确要求调用 `terminal_exec`；
  - `AgentsWorkspace.ensureInitialized()` 会安装 `ledger-cli` 到 `.agents/skills/ledger-cli/`；
  - Tests：`.\gradlew.bat :app:testDebugUnitTest` exit code=0。

## Plan Index

- `docs/plan/v21-ledger-cli-impl.md`

## ECN Index

- （本轮无）

