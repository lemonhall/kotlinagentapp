# v27 Plan：Files 导入 + 移动（inbox 导入 / 剪切粘贴）

## Goal

交付 Files 页签的两个能力：

1) 外部文件导入到 `.agents/workspace/inbox/`（按钮导入 + 外部打开/分享导入）  
2) Files 内“剪切 → 粘贴”移动文件/目录（仅限 `.agents` 内）  

## PRD Trace

- PRD-0027：REQ-0027-001 / REQ-0027-002 / REQ-0027-003
- PRD-0027：REQ-0027-010 / REQ-0027-011

## Scope

做：

- Files 顶部新增按钮：
  - 导入（OpenDocument → copy 到 `.agents/workspace/inbox/`）
  - 粘贴（当剪切板非空时显示）
- Files 条目长按新增动作：剪切（文件/目录）
- 目录空白处长按：触发粘贴
- MainActivity 接入 `ACTION_VIEW` / `ACTION_SEND`：
  - 提示用户确认导入
  - 导入到 `.agents/workspace/inbox/` 并 toast 提示
- 工作区新增 API：`AgentsWorkspace.movePath(...)`（含安全约束）
- 单测：覆盖 move 文件与目录

不做：

- 选择导入目录（本期固定 inbox）
- 复制（copy/paste）
- 多选批量、重命名、拖拽

## Acceptance（硬口径）

见 `docs/prd/PRD-0027-files-import-move.md` 的 Acceptance。

## Files

- UI：
  - `app/src/main/res/layout/fragment_dashboard.xml`
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/DashboardFragment.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/FilesViewModel.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/FilesUiState.kt`
- Intent 接入：
  - `app/src/main/AndroidManifest.xml`
  - `app/src/main/java/com/lsl/kotlin_agent_app/MainActivity.kt`
- Workspace：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`
- Tests：
  - `app/src/test/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspaceTest.kt`

## Steps（Strict / TDD）

1) TDD Red：新增 `AgentsWorkspaceTest`，期望 move 文件/目录（先失败）  
2) TDD Green：实现 `AgentsWorkspace.movePath`（含路径约束与“目录不能移入自身”）  
3) UI：导入按钮（OpenDocument）与 inbox 落盘；Files 长按剪切 + 粘贴  
4) Intent：接入外部打开/分享的导入确认  
5) Verify：`.\gradlew.bat :app:testDebugUnitTest` 全绿  
6) Ship：`git add -A ; git commit -m "v27: feat: files import and move" ; git push`  

## Risks

- Android intent-filter 太宽（`*/*`）会让 App 出现在更多“打开方式”列表；后续可收敛 mimeType。

