# v3 Plan：Files（`.agents/*` 文件管理器）MVP

## Goal

提供一个简单、安全的“`.agents/*` 文件管理器”（参考 `WebMCP_Sidecar`）：

- 默认根目录为 `.agents/*`（位于 `context.filesDir/.agents`）
- 支持浏览目录、预览小文本文件
- 支持新建/删除（删除必须二次确认；目录递归删除需明确）

## PRD Trace

- REQ-0001-020
- REQ-0001-021
- REQ-0001-022

## Scope

做：
- Files Tab：列表展示（目录优先 + 文件），可进入/返回；默认进入 `.agents/skills`
- 文本预览：仅支持小文件（例如 ≤256KB），超限提示
- 新建：文件/目录（MVP 可用简单 dialog 输入名称）
- 删除：文件/目录（必须二次确认；目录递归删除需明确）
- 仅访问 `.agents/*` 子树（防止误操作越界），不申请外部存储权限

不做：
- 不做外部存储/SAF
- 不做二进制文件预览
- 不做搜索/排序高级功能

## Acceptance（硬口径）

1. 能浏览 `.agents/*` 及子目录；返回上级不崩溃。
2. 预览文本文件：小文件可显示内容；超限提示且不加载全文。
3. 删除动作有明确的二次确认；取消不会删除。
4. `.\gradlew.bat :app:testDebugUnitTest` ✅
5. `.\gradlew.bat :app:assembleDebug` ✅

## Design Notes

- 建议抽象 `AgentsWorkspace`（仅暴露 `listDir/readText/write/mkdir/delete`），UI 只依赖接口。
- 安全边界：只允许 `.agents/*`，并对路径做“必须在 `.agents` 内”的校验（防止 `..` 穿越）。

## Steps（Strict）

1) 先写单测（红）：路径越界拒绝、超限拒绝、create/delete 的基本行为（用 Robolectric + 临时目录）
2) 实现 Repository（绿）
3) 做 Files UI（XML/Compose 任选；建议先沿用 Fragment+XML 以匹配模板）
4) build gate
