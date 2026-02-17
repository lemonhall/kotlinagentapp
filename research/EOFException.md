 其一：EOFException + 空报告，怎么杜绝/为什么会这样？

  - 发生了什么（证据）
      - ec380... 子会话在模型流式阶段直接异常中断：adb_dumps\session-ec380da5f70e452488af8b6a39e9954d-events.jsonl:103
      - 随后 SDK 只拿到一个 stop_reason=error 且 final_text="" 的结果：adb_dumps\session-ec380da5f70e452488af8b6a39e9954d-events.jsonl:104
      - 但父会话收到的 Task tool.result 仍然是 ok=true，report_summary 也是 (empty)：adb_dumps\session-85036e4b8d624d6f9e8dfbfb91b8eafa-events.jsonl:5
      - 设备端报告文件确实只有 8 字节：files/.agents/artifacts/reports/deep-research/20260217_200807_Deep-Research.md（基本就是 (empty)）
  - 为什么“证据够了却写不出报告”
      - 证据在 WebSearch/WebFetch/... 的工具输出里不等于“报告已落盘”。报告需要模型在最后阶段生成内容并触发 Write/Edit；而这次是在“下一次模型调用/流式返回”阶段断了
        （EOFException），所以根本没走到写报告那一步。
  - EOFException 常见根因（移动端更容易）
      - 网络/代理链路中断、SSE 流被中间设备或服务端提前关闭、App 切后台/省电策略导致连接被系统回收、长时间无数据被某层认为“空闲”而断开等。它本质是 IO 层异常，不是“研究逻辑推理不
        足”。
  - “杜绝空报告”这件事对不对？对。
    你现在的行为属于“失败也写一个空文件 + 父会话还当成功”，这是最糟糕的组合：既丢信息，又误导 UI/主 Agent。

  推荐措施（按收益优先级）

  1. 失败要显式失败（不再 ok=true）
      - Task(deep-research) 的返回里只要出现 stop_reason=error / error_type，就应该 ok=false，并把 error_type/error_message/sub_session_id/events_path/report_path 一并返回，让主会
        话与 UI 明确展示“失败，可重试/续跑”。
  2. 报告文件“事务化/检查点”（避免空、避免丢）
      - 子会话启动时先由宿主写入报告骨架（标题/执行摘要占位/关键发现占位/来源占位 + “生成中…”）。
      - 每次阶段性进展（例如 sources 收集到 N 条、或完成某章节草稿）就追加/更新一次。
      - 如果最终失败，报告里至少要落一段“失败原因 + 已收集到的来源/搜索词列表 + 续跑指引”，而不是 (empty)。
  3. 自动恢复：一次“重试” + “续跑”能力
      - 重试：遇到 EOFException/timeout 这类瞬断，自动重试 1 次（最好切到非 streaming 的 complete 模式，或缩短单次输出、降低工具噪音）。
      - 续跑：失败后提供“继续生成报告”的路径（复用同一个 sub session，或读 events.jsonl/tool-output 进行二次汇总生成），避免新开一堆 session。
  4. 避免“无限研究不收敛”
      - 给 deep-research 子会话加和 webview 子会话类似的硬约束：例如“来源够 8–12 条就必须进入写作”，“工具调用预算”，“连续两轮没有新增信息就停止并写报告”。

  其二：为什么自我恢复这么弱？

  - 目前从日志看，它是“没重试、不续跑、失败也 ok=true、UI 不看 stop_reason/error_type、不做 checkpoint 写文件”的组合，所以一旦 IO 掉线就直接空交付、主会话还当成功展示。

  你希望恢复策略是哪种？（选 1 个）

  - A. 自动：失败自动重试 1 次；仍失败就给“继续/重试”按钮
  - B. 半自动（推荐）：失败立刻提示 + 一键“续跑生成报告”（复用同一子会话或基于已抓取证据汇总）
  - C. 手动：只提示失败，不做任何自动动作