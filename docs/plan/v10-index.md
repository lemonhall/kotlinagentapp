# v10 Index：Files 选会话续聊 + Chat 历史回放（最小闭环）

## Vision（引用）

- PRD：`docs/prd/PRD-0007-session-resume-from-files.md`
- 本轮聚焦：
  - 会话身份元数据落盘（meta.json.metadata）
  - Files primary session 一键“续聊”
  - Chat 读取 events.jsonl 回放历史气泡

## Milestones

### M1：SDK 会话 metadata 落盘

- PRD Trace：REQ-0007-001
- DoD（硬口径）：
  - 新增 `OpenAgenticOptions.createSessionMetadata`（或等价字段），在新建 session 时写入 `meta.json.metadata`；
  - `cd external/openagentic-sdk-kotlin ; .\\gradlew.bat test --tests "me.lemonhall.openagentic.sdk.sessions.*Metadata*"` exit code=0
- Plan：`docs/plan/v10-session-resume-from-files.md`

### M2：Files primary session “续聊”

- PRD Trace：REQ-0007-010
- DoD（硬口径）：
  - `.agents/sessions` 列表对 primary/task 做可见区分（subtitle 或标识）；
  - 点击 primary session 后写入 `AppPrefsKeys.CHAT_SESSION_ID=<sid>` 并切换到 Chat 页签；
  - 点击 task session 不写入 session id；
  - `.\gradlew.bat :app:testDebugUnitTest` exit code=0
- Plan：`docs/plan/v10-session-resume-from-files.md`

### M3：Chat 历史回放

- PRD Trace：REQ-0007-020
- DoD（硬口径）：
  - Chat 显示时会根据 `AppPrefsKeys.CHAT_SESSION_ID` 回放 `events.jsonl` 的 user/assistant 气泡；
  - 回放不会把 `assistant.message` 与 `Result(final_text)` 重复显示为两条气泡；
  - 回放条数受限（避免无限增长）；
  - `.\gradlew.bat :app:testDebugUnitTest` exit code=0
  - `.\gradlew.bat :app:installDebug` exit code=0（有连接设备/模拟器时）
- Plan：`docs/plan/v10-session-resume-from-files.md`

## Plan Index

- `docs/plan/v10-session-resume-from-files.md`

## ECN Index

- （本轮无）
