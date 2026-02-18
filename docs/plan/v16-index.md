# v16 Index：Terminal Tab（命令历史 + 人类输入执行）

日期：2026-02-18

## Vision（引用）

- PRD：`docs/prd/PRD-0016-terminal-tab.md`
- 本轮目标：新增底部导航 `Terminal` 页签，展示 `terminal_exec` 历史，并允许人类输入命令执行（同样写入审计，形成闭环）。

## Milestones

### M1：Terminal 页签最小闭环

- PRD Trace：
  - PRD-0011：REQ-0011-001 / REQ-0011-010
  - PRD-0016：REQ-0016-001 ~ REQ-0016-004 / REQ-0016-010
- DoD（硬口径）：
  - 新增 `Terminal` 页签，能列出历史 run；
  - 手动执行 `hello`，UI 立即显示输出并出现在历史中；
  - 审计文件包含 `stdout/stderr`（截断），不包含 stdin；
  - Tests：`.\gradlew.bat :app:testDebugUnitTest` exit code=0
- Plan：`docs/plan/v16-terminal-tab.md`

## Plan Index

- `docs/plan/v16-terminal-tab.md`

## ECN Index

- （本轮无）

