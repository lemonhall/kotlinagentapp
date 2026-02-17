# v7 Index：Task/工具调用进度播报（气泡内单行状态 + 计时）

## Vision（引用）

- PRD：`docs/prd/PRD-0004-task-progress-ux.md`
- 本轮聚焦：把“我还在工作”以一行状态 + 已用时的形式呈现在聊天气泡内，并让 `Task(agent=...)` 的子会话关键动作能向上冒泡。

## Milestones

### M1：聊天气泡内状态行 + 计时器

- PRD Trace：REQ-0004-001，REQ-0004-002
- DoD（硬口径）：
  - 发送消息后，Assistant 气泡内 1s 内出现单行状态；状态为替换更新（不累计多行）
  - 已用时按 `52s` / `1m 53s` / `1h 02m 03s` 格式更新
  - `.\gradlew.bat :app:testDebugUnitTest` exit code=0
  - `.\gradlew.bat :app:assembleDebug` exit code=0
- Plan：`docs/plan/v7-task-progress-ux.md`

### M2：主会话工具事件映射为友好文案

- PRD Trace：REQ-0004-003
- DoD（硬口径）：
  - `WebSearch/WebFetch/web_open/Read/Write/Edit` 至少覆盖并显示为友好文案
  - 超长参数会截断，状态行不换行
- Plan：`docs/plan/v7-task-progress-ux.md`

### M3：Task 子会话进度冒泡（最小可用）

- PRD Trace：REQ-0004-004
- DoD（硬口径）：
  - `Task(agent="deep-research")` 或 `Task(agent="webview")` 执行期间，状态行能出现至少一次“子任务：...”文案
  - 子会话结束/失败后，状态行停止更新，进入最终回复或报错提示
- Plan：`docs/plan/v7-task-progress-ux.md`

## Plan Index

- `docs/plan/v7-task-progress-ux.md`

## ECN Index

- （本轮暂无）

