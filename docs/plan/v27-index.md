# v27 Index：Files 导入 + 移动（inbox 导入 / 剪切粘贴）

日期：2026-02-18

## Vision（引用）

- PRD：`docs/prd/PRD-0027-files-import-move.md`
- 本轮目标（v27）：把“外部文件导入到 `.agents` + Files 内移动”做成可回归的最小闭环，服务于后续例如 SSH 私钥配置等任务。

## Milestones

### M1：导入 + 移动闭环

- PRD Trace：
  - PRD-0027：REQ-0027-001 / REQ-0027-002 / REQ-0027-003
  - PRD-0027：REQ-0027-010 / REQ-0027-011
- DoD（硬口径）：
  - Files 页签新增“导入”按钮，导入到 `.agents/workspace/inbox/`；
  - App 支持 `ACTION_VIEW` / `ACTION_SEND` 导入确认并落盘；
  - Files 支持“剪切 → 粘贴”移动，且仅在 `.agents` 内；
  - 单测覆盖 `AgentsWorkspace.movePath` 文件与目录移动；
  - Verify：`.\gradlew.bat :app:testDebugUnitTest` 通过；
  - 提交与推送：`git status --porcelain=v1` 为空。

## Plan Index

- `docs/plan/v27-files-import-move.md`

## ECN Index

- （本轮无）

