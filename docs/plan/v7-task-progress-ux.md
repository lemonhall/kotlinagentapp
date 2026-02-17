# v7 Plan：Task/工具调用进度播报（气泡内单行状态 + 计时）

## Goal

在“长时间工具调用/子会话执行”期间，聊天界面能持续显示单行状态与已用时，降低用户焦虑；并让 `Task(agent=...)` 子会话的关键动作能最小成本向上冒泡。

## PRD Trace

- REQ-0004-001 / REQ-0004-002 / REQ-0004-003 / REQ-0004-004

## Scope

### In

- Chat UI：Assistant 气泡内新增单行 status（不换行、替换更新）
- Chat VM：维护计时器与“最近动作”状态
- 事件映射：把常见工具调用映射为短文案
- Task 冒泡：子会话工具事件以轻量“进度事件”回到主 Flow（不回放全文日志）

### Out

- 任何“剩余时间”预测
- 对 deep-research/webview 的策略/准确性优化（仅 UX）
- Files 页对大文件阅读能力增强（本轮不做）

## Acceptance

- 发送后 1s 内可见状态行；单行 + 省略；每秒更新用时
- 主会话工具调用能反映为友好文案（至少覆盖 `WebSearch/WebFetch/web_open/Read/Write/Edit`）
- `Task(agent=...)` 执行期间至少出现一次“子任务：...”状态
- 单测 + debug 构建可通过；debug 安装到设备可运行

## Files

- 修改：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/chat/ChatModels.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/chat/ChatViewModel.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/chat/ChatScreen.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/OpenAgenticSdkChatAgent.kt`
  - `app/src/test/java/com/lsl/kotlin_agent_app/ui/chat/ChatViewModelTest.kt`

## Steps（Strict）

1) **TDD Red**：为 `ToolUse(WebSearch)` 等事件写测试，断言 Assistant message 的 statusLine 会更新且包含关键字（如“搜索”）。
2) **Green**：实现 ChatViewModel 的 statusLine + 计时器（每秒更新）。
3) **Green**：实现文案映射（工具名 + 关键参数提取）。
4) **Green**：实现 Task 冒泡：
   - 主 `OpenAgenticSdkChatAgent` 在 `taskRunner` 内创建轻量进度事件（HookEvent）
   - 子会话执行时收集其工具事件并映射为“子任务：...”状态
5) **Refactor**：去重状态更新、节流（只保留最近动作），确保不会刷屏/卡 UI。
6) **Verify**：
   - `.\gradlew.bat :app:testDebugUnitTest`
   - `.\gradlew.bat :app:assembleDebug`
   - `.\gradlew.bat :app:installDebug`
7) **Ship**：`git commit` + `git push`

## Risks

- 事件太密导致 UI 更新频繁：需要仅更新“最近一条”，并限制更新频率（例如 200ms 内合并）。
- 子会话进度冒泡的实现要确保不影响主会话工具执行顺序与最终结果。

