# v24 Index：RSS CLI（协议锁口径 / 文档闭环）

日期：2026-02-18

## Vision（引用）

- PRD：`docs/prd/PRD-0024-rss-cli.md`
- 本轮目标（v24）：先把 RSS/Atom 订阅与抓取能力的 `rss` 命令协议、落盘结构、输出契约与安全护栏写硬（PRD + Plan），为后续按 `paw-cli-add-workflow` 做 TDD 落地提供可追溯锚点。

> 说明：v24 先不实现代码，只把协议与验收写死，避免“想到哪写到哪”。

## Milestones

### M0：文档闭环（PRD + Plan）

- PRD Trace：
  - PRD-0024：REQ-0024-001 / REQ-0024-002 / REQ-0024-003 / REQ-0024-004 / REQ-0024-005
  - PRD-0024：REQ-0024-010 / REQ-0024-011 / REQ-0024-012 / REQ-0024-013
- DoD（硬口径）：
  - `docs/prd/PRD-0024-rss-cli.md` 存在且包含 Req IDs、Non-Goals、Acceptance；
  - `docs/plan/v24-rss-cli.md` 存在且包含可执行 Steps 与 Files 规划；
  - 计划中的 DoD/Acceptance 均为可二元判定（pass/fail），无模糊词；
  - 提交与推送：`git status --porcelain=v1` 为空。
- Verify：
  - 纯文档里程碑：无需跑测试；但必须 `git commit` + `git push`。
- Plan：
  - `docs/plan/v24-rss-cli.md`

## Plan Index

- `docs/plan/v24-rss-cli.md`

## ECN Index

- （本轮无）

