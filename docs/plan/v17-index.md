# v17 Index：Archive CLI（zip / tar）

日期：2026-02-18

## Vision（引用）

- PRD：`docs/prd/PRD-0017-archive-zip-tar-cli.md`
- 本轮目标：把 zip/tar 归档处理能力以 `terminal_exec` 白名单命令形式暴露出来，并把安全护栏（confirm、路径越界、防炸弹、覆盖门禁）写死在验收口径与测试里。

## Milestones

### M1：zip/tar 最小闭环（list/extract/create）

- PRD Trace：
  - PRD-0011：REQ-0011-001 / REQ-0011-002 / REQ-0011-010
  - PRD-0017：REQ-0017-001 / REQ-0017-002 / REQ-0017-010 ~ REQ-0017-014 / REQ-0017-020
- DoD（硬口径）：
  - `zip` 与 `tar` 顶层命令可用，且 `list/extract/create` 子命令最小闭环可跑；
  - extract/create 缺失 `--confirm` 必拒绝（`ConfirmRequired`）；
  - ZipSlip/TarSlip 被阻止（path traversal 测试必有）；
  - 默认不覆盖，`--overwrite` 必须与 `--confirm` 同时存在才生效；
  - list 超长输出支持 `--out` 落盘并通过 `artifacts[]` 返回引用；
  - Tests：`.\gradlew.bat :app:testDebugUnitTest` exit code=0
- Plan：`docs/plan/v17-archive-zip-tar-cli.md`

## Plan Index

- `docs/plan/v17-archive-zip-tar-cli.md`

## ECN Index

- （本轮无）

## Evidence

- Unit tests：`.\gradlew.bat :app:testDebugUnitTest`（通过）
- Builtin skills：`archive-zip` / `archive-tar`（已随 `AgentsWorkspace.ensureInitialized()` 安装到 `.agents/skills/*`）

## Diff（Vision vs. Reality）

- （本轮无）
