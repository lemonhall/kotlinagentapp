# v24 Index：RSS CLI（协议锁口径 / 文档闭环）

日期：2026-02-18

## Vision（引用）

- PRD：`docs/prd/PRD-0024-rss-cli.md`
- 本轮目标（v24）：在锁定协议/护栏（PRD + Plan）的前提下，按 `paw-cli-add-workflow` 以 TDD 落地 `rss` CLI（add/list/remove/fetch）最小闭环：可落盘、可审计、可回归、可通过 builtin skill 驱动。

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

### M1：RSS CLI 最小闭环（实现 + 单测 + builtin skill）

- PRD Trace：
  - PRD-0024：REQ-0024-001 / REQ-0024-002 / REQ-0024-003 / REQ-0024-004 / REQ-0024-005
  - PRD-0024：REQ-0024-010 / REQ-0024-011 / REQ-0024-012 / REQ-0024-013
- DoD（硬口径）：
  - `terminal_exec` 新增白名单命令 `rss`（add/list/remove/fetch），且不引入外部进程；
  - 订阅落盘：`.agents/workspace/rss/subscriptions.json`；
  - 抓取状态落盘：`.agents/workspace/rss/fetch_state.json`；
  - `--out` 产物在 `.agents/` 内，且 tool output 的 `artifacts[]` 返回 `.agents/<out_path>`；
  - 429：`error_code="RateLimited"`，并尽可能返回 `retry_after_ms`；
  - 单测：`.\gradlew.bat :app:testDebugUnitTest` exit code=0；
  - 冒烟装机：`.\gradlew.bat :app:installDebug` 成功安装到已连接设备。

## Plan Index

- `docs/plan/v24-rss-cli.md`

## ECN Index

- （本轮无）
