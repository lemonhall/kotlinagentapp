1) 我们 SDK 的 resume 到底做了什么（能不能“重建整个会话”）

  - 语义是“读 events.jsonl 作为上下文 + 继续 append 同一个 session”，不是“重放/回放 UI”。见 external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/runtime/
    OpenAgenticSdk.kt:74。
  - 会从历史 Result.response_id 推导 previousResponseId，用于 Responses API 的 previous_response_id（若 provider 不支持会自动降级不用）。见 external/openagentic-sdk-kotlin/src/
    main/kotlin/me/lemonhall/openagentic/sdk/runtime/OpenAgenticSdk.kt:78。
  - “重建”是上下文层面的重建：把历史事件转成下一次模型调用的 input（含 tool call/output）。见 external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/runtime/
    OpenAgenticSdk.kt:463。
  - 但默认只读尾巴：resumeMaxEvents=1000、resumeMaxBytes=2MB，不会无限全量读；而且被 externalize/compaction prune 的 tool output 本来就不会完整注入模型。见 external/openagentic-
    sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/runtime/OpenAgenticSdk.kt:676。
    => 所以答案是：能“继续聊”（LLM 上下文层面），但不是 opencode 那种“把 UI/消息状态完整回放出来的重建”。

  2) opencode 的 resume/会话模型比我们多了什么

  - opencode 的 session 是数据库对象，原生就有 title / time / permission / parentID(父子会话)，子任务是 child session。见 E:
    \development\opencode\packages\opencode\src\session\index.ts:65。
  - opencode 的 Task（subagent）能带 task_id 续跑同一个子会话（子 agent 自己的上下文连续），并且 Task 创建 session 时就写 parentID 指回主会话。见 E:
    \development\opencode\packages\opencode\src\tool\task.ts:18、E:\development\opencode\packages\opencode\src\tool\task.ts:67。
  - opencode 的“resume”更多是把历史消息/parts回放给前端 + 继续 loop（甚至有 in-process 的 resume signal）。见 E:\development\opencode\packages\opencode\src\session\prompt.ts:249。
    => 总结：opencode 的 resume 是“产品层会话切换/回放 + 子会话树”，我们目前是“SDK 级上下文续接”。

  3) 你要的功能（点 Files 的 session → resume 成当前主会话）在我们这边的阻塞点

  - 现在 Chat 只用一个 SharedPreferences 里的 KEY_SESSION_ID 做续聊；能续，但 UI 不会加载历史消息。见 app/src/main/java/com/lsl/kotlin_agent_app/agent/
    OpenAgenticSdkChatAgent.kt:111、app/src/main/java/com/lsl/kotlin_agent_app/agent/OpenAgenticSdkChatAgent.kt:201。

  4) 我建议的“正统落地顺序”（最小闭环）

  - A. 先补“会话身份”：新建 session 时写 meta.json.metadata，至少有 kind=primary|task、parent_session_id、agent（task 时）。SDK/Runner 两侧都能做（我们 store 已支持 metadata）。
  - B. Files 页签只允许/优先对 kind=primary 做“续聊”，子会话可折叠显示（或默认隐藏）。
  - C. 点击 primary session 后：写入 KEY_SESSION_ID，并让 ChatViewModel 读取该 session 的 events.jsonl 回放成气泡（否则用户体感是“续了但看不到历史”）。