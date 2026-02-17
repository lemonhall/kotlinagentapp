# v10 Plan：Files 选会话续聊 + Chat 历史回放（最小闭环）

PRD：`docs/prd/PRD-0007-session-resume-from-files.md`

## Goal

把“续聊”从 SDK 级能力提升为 App 级可用闭环：

- 新 session 具备身份（primary/task）与父子指针（task → parent_session_id）。
- Files 可选 primary session 并一键切换为当前 Chat 会话。
- Chat 能从 `events.jsonl` 回放历史为气泡，解决“续了但看不到历史”的断点。

## PRD Trace

- REQ-0007-001 / REQ-0007-010 / REQ-0007-020

## Scope

做：
- SDK：新增 createSessionMetadata（落 meta.json.metadata）。
- App：为主会话与子会话分别写入 session metadata（primary/task + parent + agent）。
- Files：primary session 点击续聊；task session 禁止续聊（仅浏览）。
- Chat：在显示时从当前 sessionId 回放历史消息（只回放 user/assistant）。

不做：
- 不做子会话树 UI 管理与一键续跑。
- 不做远端持久化/云同步。

## Acceptance（硬口径）

### SDK（REQ-0007-001）

- 单测：`createSessionMetadata={"kind":"primary"}` 时，`meta.json.metadata.kind == "primary"`。

验证命令（SDK 仓库内）：

```powershell
cd external/openagentic-sdk-kotlin ; .\gradlew.bat test --tests "me.lemonhall.openagentic.sdk.sessions.*Metadata*"
```

### App（REQ-0007-010 / REQ-0007-020）

1. 单测：回放时不重复显示 `assistant.message` 与 `Result(final_text)`。
2. 人工 E2E（最小验证）：
   1) Chat 发送一句话产生 primary session；  
   2) Files → `.agents/sessions` 点击该 session（primary）→ 自动切换到 Chat；  
   3) Chat 显示历史气泡；再发送一句话，确认继续 append 同一个 session。  

验证命令（App 仓库根目录）：

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

## Files（预期改动范围）

- SDK：
  - `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/runtime/RuntimeModels.kt`
  - `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/runtime/OpenAgenticSdk.kt`
  - `external/openagentic-sdk-kotlin/src/test/kotlin/me/lemonhall/openagentic/sdk/sessions/*Metadata*.kt`
- App：
  - `app/src/main/java/com/lsl/kotlin_agent_app/config/AppPrefsKeys.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/OpenAgenticSdkChatAgent.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/DashboardFragment.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/FilesViewModel.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/chat/ChatFragment.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/chat/ChatViewModel.kt`
  - `app/src/test/java/com/lsl/kotlin_agent_app/ui/chat/ChatViewModelTest.kt`（回放逻辑单测）

## Steps（塔山循环：Red → Green → Refactor）

1. **TDD Red（SDK）**：新增单测覆盖 `createSessionMetadata` 写入 `meta.json.metadata`。
2. **TDD Green（SDK）**：实现 options 字段 + createSession(metadata) 传递；跑到绿。
3. **TDD Red（App）**：新增 Chat 回放单测（避免重复 Result）。
4. **TDD Green（App）**：实现 Chat 回放 + Files 续聊 + 会话 metadata 写入；跑到绿。
5. **Refactor**：整理会话 meta 解析与回放映射，保持逻辑可测。
6. **Review（证据）**：在 `docs/plan/v10-index.md` 填入验证命令与 exit code（作为 DoD 证据）。

## Risks

- 历史 events 过大导致 UI 卡顿：回放只取最后 N 条 user/assistant，且读取大小上限对齐 SDK resume 默认值。
- 老 session metadata 缺失：按 primary 兼容，不阻塞续聊。

