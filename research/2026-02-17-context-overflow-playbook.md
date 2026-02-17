# 上下文总是“炸”？从 Codex 取经，给 OpenAgentic SDK 的一份“治爆”备忘录（v0）

日期：2026-02-17  
背景：kotlin-agent-app / `external/openagentic-sdk-kotlin` 集成过程中，线程经常触发 `context_length_exceeded`（或等价的 token 过载），体验非常割裂：**明明我们知道怎么做，但模型“装不下了”**。

这份备忘录不是“学术论文腔”，而是想把 Codex 里那些**真正能落地**、能让你立刻舒一口气的手段，翻译成我们 SDK 能用的工程动作。后面我们可以照着它，一条条拉清单改造。

---

## TL;DR：三条“治爆原则”（先把火灭了再谈理想）

1) **大输出永远不要直接进上下文**  
能写文件就写文件；上下文里只放“目录 + 摘要 + 指针 + 少量片段”。

2) **截断要“头+尾”，不要只砍尾巴或只留开头**  
只留开头最容易把结论/错误堆栈/最终结果砍没；保留尾巴才能让模型知道“最后发生了啥”。

3) **不要指望 compaction 永远来得及**  
compaction 是“救急车”，不是“消防栓”。真正稳定的是：**在写入 session history/发回模型之前就预算化**。

---

## 我们现在为什么容易炸（以现有 SDK 代码为参照）

我们其实已经做了不少防护：

- 工具层面有“最大输出”概念：例如 `WebFetchTool` 会把 `text` 限制在 `max_chars`（默认 24k，最大可到 80k）。  
  但这依然可能很大：80k 字符粗算 20k tokens 左右，来两三次就把上下文掏空了。
- 我们也有 compaction & tool output pruning：  
  `Compaction.kt` 里有 `TOOL_OUTPUT_PLACEHOLDER`，并会在特定条件下把旧 tool result 清空。

问题常出在两类场景：

1) **“大结果”在 compaction 触发之前就已经把上下文塞爆**  
尤其是连续 WebFetch / Bash / Read / Grep 之类组合拳，一轮下来历史里堆了一堆大块 JSON。

2) **即使工具自己截断了，但截断后的那份“截断文本”依旧偏大**  
比如 WebFetch：它做的是“截断 text 字段”，但并没有像 BashTool 那样把完整内容落文件并返回可再读的指针。  
于是模型想“继续看”，只能再 WebFetch 一次 → 又加一坨 → 更炸。

---

## Codex 是怎么“稳住不炸”的（我们能直接抄作业的部分）

### 1) 全局的 `tool_output_token_limit`：先预算、后入库

Codex 里有一个很关键的配置：`tool_output_token_limit`。它的意义不是“工具自己别输出太大”，而是：

> **无论工具实际返回多大，写入对话历史/再次发回模型时，统一按预算截断。**

实现上，Codex 会在记录历史时对 `FunctionCallOutput` / `CustomToolCallOutput` 做截断（还预留序列化开销），保证历史不会因为某一次工具输出失控而雪崩。

这对我们 SDK 的启发是：  
**“工具输出限制”不是分散在每个 Tool 里，而应该在“会话历史入口”再兜一次底。**

### 2) 截断策略是“头+尾” + marker，而不是简单 `take(n)`

Codex 的截断不是硬切前 N 个字符，而是保留前后片段，中间插一个 marker（例如“省略了多少 tokens/chars”），并且对多段 content items 也会尽量保住结构、补“omitted N items”的摘要。

这个细节看起来“讲究”，但实际非常救命：  
日志/报错/最终输出往往在尾部，**保尾巴=保真相**。

### 3) WebSearch 只记“动作”，不把“结果全文”写进历史

Codex 对 `web_search` 的持久化非常克制：历史里只保留 `status + action（search/open_page/find_in_page）` 这种“发生了什么”，而不是把一整页网页/一堆搜索结果塞进去。

这背后的思路很值得抄：  
**“把外部世界的原始材料”当成 artifacts/附件，而不是当成对话历史正文。**

### 4) 源头硬上限：例如 exec 输出缓冲上限（1 MiB）

即便上层做了预算，Codex 还会在某些高风险工具（类似 exec）上加“源头缓冲上限”，防止内存/IO/序列化被拖死。

---

## 可以立刻“搬回家”的改造清单（按性价比排序）

下面我按“最快能缓解痛苦”的顺序排。我们后续可以一条条过，愿意的话就落到 PRD/Plan。

### A. 在 SDK 的“写入事件/构建模型输入”处做统一预算（强烈推荐，优先级 #1）

目标：不管哪个 Tool 一时兴起吐了个大包，都别让 session history 变成炸药桶。

建议落点（方向，不限定具体文件/函数）：

- 在 `OpenAgenticSdk` 构建 `modelInput` 之前，或者在 `sessionStore.appendEvent` 前，增加一层“输出预算化”：
  - 对 `ToolResult.output` 做 token/char 预算截断
  - 对 `AssistantMessage`（尤其是长 Markdown）也可选做预算截断（保留尾巴）
- 策略建议：
  - “头+尾”截断 + marker
  - JSON 结构尽量保持（比如 `text_preview`、`artifact_path`、`truncated=true`），不要把 JSON 截成半截字符串

验收口径（很工程化，但舒服）：
1) 连续 10 次 WebFetch，`events.jsonl` 仍保持可控体积  
2) 下一轮请求前的 `estimateModelInputTokens` 不会突然跳到离谱

### B. WebFetchTool 学 BashTool：大内容落文件，返回“指针 + 预览”

现在的 `WebFetchTool` 会返回 `text`，最多 80k 字符。建议改成两层输出：

- **永远返回小 JSON**（适合进上下文）：
  - `final_url / title / content_type / mode / truncated`
  - `text_preview`（比如 4k~8k，头+尾）
  - `artifact_path`（完整内容写到 session artifact 文件）
- 完整内容写到文件（和 BashTool 的“full output file”一致的体验）：
  - 模型如果要继续看，用 `Read/Grep` 工具去“读文件的一段/搜关键字”

这样一来，WebFetch 不再是“上下文杀手”，而是一个“产出 artifact 的抓取器”。

### C. “搜索/抓取”结果分层：历史里只留摘要，原始材料进 artifacts（优先级 #2）

一旦我们把外部材料当作 artifacts，就能自然做两件事：

1) UI 体验更好：Files 页可以直接浏览这些抓取结果文件（我们刚好也做了 JSON/JSONL 的默认查看模式）。  
2) 模型更稳定：上下文里不再重复塞同一份大材料。

这一步可以先从 WebFetch 做，后面再扩到“网页截图、PDF、长日志、HTML”等。

### D. compaction 的触发更“兜底”：usage 不可靠时，用估算触发（优先级 #3）

我们现在有 `wouldOverflow(usage)`，但真实世界里 provider 的 usage 字段有时缺失/不准/延迟。建议：

- 如果 usage 不可用：用 `estimateModelInputTokens(modelInput)` 兜底判断是否接近阈值
- compaction 触发不要等“>=100%”：可以在 80%~90% 就先做一次，避免一脚踩爆

### E. 可选：把“研究/搜索”做成子会话（sub-session）而不是主会话堆料（优先级：看你想不想做大）

你提到的“用 subagent 节省 context”的思路，在 Codex 里主要用在 review/compact 等任务，不是默认用于 web search。  
但我们 SDK 其实可以设计一个“研究模式”：

- 主会话：只保留问题、决策、下一步（轻、稳定）
- 研究子会话：负责 WebFetch/多轮搜索/长文阅读
- 子会话结束：只把“结构化摘要 + 引用片段 + artifact 指针”写回主会话

这条一旦做成，对“长文档/长网页/多来源调研”非常强；缺点是工程量更大、状态管理更复杂。

---

## 我建议我们下一步怎么“按顺序动刀”

如果你的目标是“先不炸再说”，我建议顺序是：

1) **A：统一预算化**（会话入口兜底）  
2) **B：WebFetch 落文件 + 预览**（立刻干掉一个大头）  
3) **D：compaction 触发兜底**（让“救急车”更可靠）  
4) **C：成果分层**（把体验打磨舒服）  
5) **E：研究子会话**（想把能力做成“真·研究员”再上）

---

## 给“上下文不炸”的验收清单（我们后面每改一刀都能对照）

- 连续运行 20+ 次工具调用（含 WebFetch/Bash/Read/Grep 组合），不会出现明显的上下文溢出错误
- `events.jsonl` 不会出现单条事件动辄几十万字符（除非它是 artifact 文件路径的记录）
- 模型想继续看大内容时，**优先会学会用 Read/Grep 读 artifact**，而不是反复 WebFetch 叠 buff
- compaction 发生时，保留“关键决策/关键路径/关键文件”，而不是把重要上下文压成一句废话

---

## 附：我们已经有的基础（别重复造轮子）

这点也值得写下来，免得我们“以为缺”，其实早有：

- `Compaction.kt` 已经支持对旧 tool result 做占位清空（`TOOL_OUTPUT_PLACEHOLDER`）  
- `BashTool` 已经有“大输出写文件 + 截断返回”的模式  
- `Read/Grep/List` 等工具已经具备“围绕文件做精确读取/搜索”的能力

下一步不是从 0 到 1，而是把这些能力串起来，形成一个统一的“预算化 + artifact 化”的闭环。

