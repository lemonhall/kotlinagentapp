# v3 Index：Files（`.agents/*` 文件管理器）+ Skills（SKILL.md 规范）MVP

## Vision（引用）

- PRD：`docs/prd/PRD-0001-kotlin-agent-app.md`
- 本轮聚焦：参考 `WebMCP_Sidecar` 的设定，补齐「能做事」的基本载体——在 App 内部存储提供一个 **`.agents/*` 文件管理器**，并落盘内置 skills（`.agents/skills/<name>/SKILL.md`）。

## Milestones

### M1：Files Tab（`.agents/*` 文件管理器 + 基本操作）

- PRD Trace：REQ-0001-020 / REQ-0001-021 / REQ-0001-022
- DoD（硬口径）：
  - 能浏览 `.agents/*`（目录/文件列表，支持进入子目录与返回；默认进入 `.agents/skills`）
  - 文本文件可预览（有大小上限，超限提示）
  - 新建文件/目录；删除文件/目录**必须二次确认**（目录递归删除需明确确认）
  - `.\gradlew.bat :app:assembleDebug` exit code=0
  - `.\gradlew.bat :app:testDebugUnitTest` exit code=0（核心逻辑单测覆盖）
- Plan：`docs/plan/v3-files-internal-storage.md`

### M2：Skills（`.agents/skills/<name>/SKILL.md` + YAML frontmatter）

- PRD Trace：REQ-0001-030 / REQ-0001-031（本轮以 Files 管理器可浏览/编辑 skills 为主，不单独做 Skills Tab）
- DoD（硬口径）：
  - skills 文件规范对齐 Sidecar：`.agents/skills/<name>/SKILL.md`，使用 YAML frontmatter 提供 `name/description/...`
  - 内置 3-4 个通用 skills（best-effort 落盘；缺失不阻塞）
  - 通过 Files 管理器可打开/编辑/保存 SKILL.md
  - `.\gradlew.bat :app:assembleDebug` exit code=0
  - `.\gradlew.bat :app:testDebugUnitTest` exit code=0（解析/读写逻辑单测覆盖）
- Plan：`docs/plan/v3-skills-local-directory.md`

## Plan Index

- `docs/plan/v3-files-internal-storage.md`
- `docs/plan/v3-skills-local-directory.md`

## Traceability Matrix（v3）

| Req ID | v3 Plan | Tests / Commands | Evidence |
|---|---|---|---|
| REQ-0001-020 | v3-files-internal-storage | `:app:testDebugUnitTest` | local |
| REQ-0001-021 | v3-files-internal-storage | `:app:testDebugUnitTest` | local |
| REQ-0001-022 | v3-files-internal-storage | `:app:testDebugUnitTest` | local |
| REQ-0001-030 | v3-skills-local-directory | `:app:testDebugUnitTest` | local |
| REQ-0001-031 | v3-skills-local-directory | `:app:testDebugUnitTest` | local |

## ECN Index

- none

## Evidence（本地）

- 2026-02-15：
  - `.\gradlew.bat :app:testDebugUnitTest` ✅
  - `.\gradlew.bat :app:assembleDebug` ✅
