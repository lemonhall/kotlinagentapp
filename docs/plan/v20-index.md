# v20 Index：Ledger CLI（个人简单记账）

日期：2026-02-18

## Vision（引用）

- PRD：`docs/prd/PRD-0020-ledger-cli.md`
- 本轮目标（v20）：先把“个人简单记账”的 `ledger` 命令协议、数据落盘约定、验收口径写硬（PRD + Plan），为后续按 `paw-cli-add-workflow` 做 TDD 落地提供可追溯锚点。

> 说明：v20 先不实现代码，只把协议与验收写死，避免“想到哪写到哪”。

## Milestones

### M0：文档闭环（PRD + Plan）

- PRD Trace：
  - PRD-0020：REQ-0020-001 / REQ-0020-002 / REQ-0020-003 / REQ-0020-004
  - PRD-0020：REQ-0020-010 / REQ-0020-011 / REQ-0020-012 / REQ-0020-020
- DoD（硬口径）：
  - `docs/prd/PRD-0020-ledger-cli.md` 存在且包含 Req IDs、Non-Goals、Acceptance；
  - `docs/plan/v20-ledger-cli.md` 存在且包含可执行 Steps 与 Files 规划；
  - 计划中的 DoD/Acceptance 均为可二元判定（pass/fail），无模糊词；
  - 提交与推送：`git status --porcelain=v1` 为空。
- Verify：
  - 纯文档里程碑：无需跑测试；但必须 `git commit` + `git push`。
- Plan：
  - `docs/plan/v20-ledger-cli.md`

## Plan Index

- `docs/plan/v20-ledger-cli.md`

## ECN Index

- （本轮无）

