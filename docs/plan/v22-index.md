# v22 Index：Stock CLI（Finnhub 股市数据 / REST）

日期：2026-02-18

## Vision（引用）

- PRD：`docs/prd/PRD-0022-finnhub-stock-cli.md`
- 本轮目标（v22）：先把 Finnhub 股市数据能力的 `stock` 命令协议、密钥存储约定（`.env`）、频控与 `--out` artifacts 规则写硬（PRD + Plan），为后续按 `paw-cli-add-workflow` 做 TDD 落地提供可追溯锚点。

> 说明：v22 先不实现代码，只把协议与验收写死，避免“想到哪写到哪”。

## Milestones

### M0：文档闭环（PRD + Plan）

- PRD Trace：
  - PRD-0022：REQ-0022-001 / REQ-0022-002 / REQ-0022-003 / REQ-0022-004
  - PRD-0022：REQ-0022-005 / REQ-0022-006 / REQ-0022-007 / REQ-0022-008
  - PRD-0022：REQ-0022-010 / REQ-0022-011 / REQ-0022-012 / REQ-0022-013 / REQ-0022-014 / REQ-0022-020
- DoD（硬口径）：
  - `docs/prd/PRD-0022-finnhub-stock-cli.md` 存在且包含 Req IDs、Non-Goals、Acceptance；
  - `docs/plan/v22-stock-cli.md` 存在且包含可执行 Steps 与 Files 规划；
  - 计划中的 DoD/Acceptance 均为可二元判定（pass/fail），无模糊词；
  - 提交与推送：`git status --porcelain=v1` 为空。
- Verify：
  - 纯文档里程碑：无需跑测试；但必须 `git commit` + `git push`。
- Plan：
  - `docs/plan/v22-stock-cli.md`

## Plan Index

- `docs/plan/v22-stock-cli.md`

## ECN Index

- （本轮无）

