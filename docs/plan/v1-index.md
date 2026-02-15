# v1 Index：MVP-1（Chat Compose + 基本设置持久化占位）

## Vision（引用）

- PRD：`docs/prd/PRD-0001-kotlin-agent-app.md`
- 本轮聚焦：先把“Chat（Compose）最短闭环”做硬，并让设置页具备可持久化的配置入口，为后续接入 SDK 打底。

## Milestones

### M1：Chat Tab（Compose in Fragment）最短闭环

- Status：done（2026-02-15）
- Scope：
  - Chat 页替换为 Compose UI（消息列表 + 输入框 + 发送）
  - ViewModel 驱动消息状态；MVP 先用 echo/mock assistant
  - Tool 轨迹占位（mock 事件列表）
- DoD（硬口径）：
  - `.\gradlew.bat :app:assembleDebug` exit code=0
  - `.\gradlew.bat :app:testDebugUnitTest` exit code=0
  - 存在 1 个“端到端替代”本地测试（Robolectric）覆盖：启动 MainActivity → 进入 Chat tab → ChatFragment 视图创建成功
- Plans：
  - `docs/plan/v1-chat-compose.md`

### M2：Settings Tab（XML）配置持久化（占位）

- Status：todo
- Scope：
  - Settings 页可编辑并持久化：`base_url` / `api_key` / `model`
  - 安全：不打印完整密钥
- DoD（硬口径）：
  - `.\gradlew.bat :app:testDebugUnitTest` exit code=0（含配置读写的单测）
- Plans：
  - `docs/plan/v1-settings-config.md`

> 注：本轮只承诺 M1；M2 写入计划但可在 M1 完成后再进入。

## Plan Index

- `docs/plan/v1-chat-compose.md`
- `docs/plan/v1-settings-config.md`

## Traceability Matrix（v1）

| Req ID | v1 Plan | Tests / Commands | Evidence |
|---|---|---|---|
| REQ-0001-001 | v1-chat-compose | `:app:testDebugUnitTest` | local |
| REQ-0001-002 | v1-chat-compose | `:app:testDebugUnitTest` | local |
| REQ-0001-010 | v1-settings-config | `:app:testDebugUnitTest` | pending |

## ECN Index

- none

## Differences（愿景 vs 现实）

- SDK 集成与真实对话/工具调用未实现（留到 v2/v3）。
- Files/Skills/WebView 相关需求未进入 v1。

## Evidence（本地）

- 2026-02-15：
  - `.\gradlew.bat :app:testDebugUnitTest` ✅
  - `.\gradlew.bat :app:assembleDebug` ✅
