# v6 Index：Deep-Research 子会话交付（只回传报告地址）

## Vision（引用）

- PRD：`docs/prd/PRD-0003-context-overflow-control.md`
- 本轮聚焦：把 `deep-research` 这种“高噪音、多工具调用”的工作流下沉到子会话；主会话只拿到一个 `report_path`，按需再读报告文件。

## Milestones

### M1：`Task(agent="deep-research")` 输出报告地址

- PRD Trace：REQ-0003-020
- DoD（硬口径）：
  - App 支持 `Task(agent="deep-research", prompt=...)`
  - tool.result 返回包含 `report_path`（相对 `.agents` 根目录）
  - 子会话生成的报告文件存在且非空：`<agents_root>/<report_path>`
  - `.\gradlew.bat :app:testDebugUnitTest` exit code=0
- Plan：`docs/plan/v6-deep-research-subagent.md`

## Plan Index

- `docs/plan/v6-deep-research-subagent.md`

## ECN Index

- `docs/ecn/ECN-0002-web-snapshot-artifacts-deferred.md`

