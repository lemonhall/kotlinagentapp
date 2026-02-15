# v3 Plan：Skills（`.agents/skills/<name>/SKILL.md`）MVP

## Goal

对齐 `WebMCP_Sidecar` 的 skills 规范，并确保最短闭环可用：

- 约定 skills 根目录：`context.filesDir/.agents/skills/`
- 每个 skill 目录至少包含 `SKILL.md`（带 YAML frontmatter 的 `name/description/...`）
- 内置 3-4 个通用 skills（best-effort 落盘）
- 通过 Files 管理器可浏览/打开/编辑/保存

## PRD Trace

- REQ-0001-030
- REQ-0001-031

## Scope

做：
- skills 文件落盘：`.agents/skills/<name>/SKILL.md`（best-effort）
- 技能元信息读取：从 `SKILL.md` 的 YAML frontmatter 提取 `name/description`
- 详情查看/编辑：通过 Files 管理器打开/保存

不做：
- 不做对话式安装/卸载（留到后续 File Agent / Skill Agent）
- 不做远程 registry

## Acceptance（硬口径）

1. 没有 skills 目录时自动创建（空列表提示）。
2. 内置 skills 首次进入时可在 `.agents/skills/*` 看到并可打开 SKILL.md。
3. 编辑保存后重新打开仍能读回。
4. `.\gradlew.bat :app:testDebugUnitTest` ✅
5. `.\gradlew.bat :app:assembleDebug` ✅

## Design Notes

- 建议复用 `AgentsWorkspace` 的 FS 能力；后续再引入 `ListSkills` tool（等 Agent Runtime ready）。
- skill 的“唯一标识”用目录名（与 Sidecar 对齐）。

## Steps（Strict）

1) 先写单测（红）：manifest 解析、enabled 标记读写、缺文件容错
2) 实现 Repository（绿）
3) 做 Skills UI（XML/Compose 任选；建议先沿用 Fragment+XML 以匹配模板）
4) build gate
