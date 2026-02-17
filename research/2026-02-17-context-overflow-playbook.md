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

这一节我写得更“能照着抄”的版本：**每个点都给 Codex 的代码入口 + 伪代码**。以后我们改 SDK 的时候，不用靠记忆，更不用靠猜。

> 说明：下面的行号来自我本机 `E:\development\codex` 当前代码；未来 Codex 上游变动后行号可能漂移。为了稳妥，我在每个小节都附了一个 `rg` 的定位关键词。

### 1) 全局的 `tool_output_token_limit`：先预算、后入库（而不是“相信每个 Tool 都自律”）

Codex 真正狠的地方在于：它**不信任任何 tool 的“自觉”**。哪怕 tool 输出 1MB，它也会在“写入历史/回喂模型”的入口统一按预算砍一刀。

**代码入口（Codex repo）**
- 配置字段：`codex-rs/core/src/config/mod.rs:295`（`tool_output_token_limit`）
  - 快速定位：`rg -n "tool_output_token_limit" codex-rs/core/src/config/mod.rs`
- 把 `tool_output_token_limit` 映射到模型的 `truncation_policy`：`codex-rs/core/src/models_manager/model_info.rs:22`（`with_config_overrides`）
  - 快速定位：`rg -n "config.tool_output_token_limit" codex-rs/core/src/models_manager/model_info.rs`

**它在做什么（伪代码）**

```text
# 目标：把“工具输出预算”变成 TurnContext 的统一截断策略

modelInfo = loadModelInfo(slug)

if config.tool_output_token_limit != null:
  if modelInfo.truncation_policy.mode == BYTES:
    modelInfo.truncation_policy = bytes(approxBytesForTokens(config.tool_output_token_limit))
  else if mode == TOKENS:
    modelInfo.truncation_policy = tokens(config.tool_output_token_limit)

turnContext.truncation_policy = modelInfo.truncation_policy
```

**为什么这招能救命**  
因为它把“爆炸风险”从“每个 tool 的实现质量”转移到了“一个统一入口的强约束”。这就是工程上最靠谱的做法：**你可以写烂 tool，但别把系统炸了**。

### 2) 截断不是简单 `take(n)`：而是“头+尾”保命 + marker 交代省略（并且对多段 item 友好）

Codex 的截断策略很像一个老练的运维同事：你给他一坨超长日志，他不会只留开头让你抓瞎，而是**留住开头和结尾**，中间用 marker 告诉你省略了多少。

**代码入口（Codex repo）**
- 历史入库时统一处理 tool outputs：`codex-rs/core/src/context_manager/history.rs:327`（`process_item`）
  - 快速定位：`rg -n "fn process_item" codex-rs/core/src/context_manager/history.rs`
- 截断实现：`codex-rs/core/src/truncate.rs:79`（`formatted_truncate_text` / `truncate_text`）
  - 快速定位：`rg -n "pub\\(crate\\) fn truncate_text\\(" codex-rs/core/src/truncate.rs`
- 多段 content items 的预算截断 + 省略摘要：`codex-rs/core/src/truncate.rs:100`（`truncate_function_output_items_with_policy`）
  - 快速定位：`rg -n "truncate_function_output_items_with_policy" codex-rs/core/src/truncate.rs`

**它在做什么（伪代码）**

```text
# 入口：ContextManager 记录历史时，对 tool outputs 统一截断

fn process_item(item, policy):
  policy2 = policy * 1.2            # 预留序列化开销

  match item.type:
    FunctionCallOutput:
      if output.body is Text:
        output.body.text = truncate_text(output.body.text, policy2)
      else if output.body is ContentItems[]:
        output.body.items = truncate_items(output.body.items, policy2)
      return item

    CustomToolCallOutput:
      item.output = truncate_text(item.output, policy2)
      return item

    else:
      return item
```

```text
# 核心：truncate_text 走“头+尾”

fn truncate_text(s, policy):
  if s fits in budget(policy): return s

  maxBytes = policy.byte_budget()   # tokens 模式会先估算成 bytes
  if maxBytes == 0:
    return "…N tokens/chars truncated…"

  (leftBudget, rightBudget) = split_budget(maxBytes)
  (left, right, removedUnits) = split_string_on_utf8_boundary(s, leftBudget, rightBudget)
  marker = "…{removedUnits} tokens/chars truncated…"
  return left + marker + right
```

```text
# 多段 items：尽量保留结构；预算不够时“截断一个 + 省略若干个”

fn truncate_items(items, policy):
  remaining = policy.budget()
  omitted = 0
  out = []

  for item in items:
    if item is image:
      out.push(item)                # image 不吃文本预算（或按固定成本）
      continue

    cost = estimate_cost(item.text, policy)  # bytes 或 tokens 的近似
    if cost <= remaining:
      out.push(item)
      remaining -= cost
    else:
      snippet = truncate_text(item.text, policy.with_budget(remaining))
      if snippet empty: omitted += 1 else out.push(text(snippet))
      remaining = 0

  if omitted > 0:
    out.push(text("[omitted {omitted} text items ...]"))

  return out
```

**这里有个很“工程味”的小细节**：`policy * 1.2`  
这意味着 Codex 不是在理想世界里做预算，而是在真实世界里考虑“JSON 序列化也要花钱”。我们 SDK 里也该学这一点，别预算得太死，死了就会在最尴尬的时候溢出。

### 3) WebSearch 只记“动作”，不把“结果全文”写进历史（把“原始材料”留在系统外）

先把话说清楚（我前一版写得太像“Codex 自带摘要结果”了，这是误导）：

- **Codex 并没有把“搜索结果/网页正文”做成一个可持久化的“找到什么摘要”写进历史。**  
  它记录的是 `web_search_call` 发生时的 **动作参数**（例如搜了什么 query、打开了哪个 url、在页面里找了哪个 pattern），而不是把 results/body 这种大块内容塞进 history。
- 你在 UI 里看到的“找到了什么/做了什么”的**人类可读短句**，本质上是把这些动作参数拼出来的一行 detail（比如 `"'pattern' in https://..."`），不是“总结搜索结果”。

这个点特别适合拿来做 WebFetch 的改造方向：**历史里只记“我去搜了/打开了/在页面里找了什么”，至于原始结果全文，不要硬塞进上下文。**

#### 3.1 “提示词”到底在哪：Codex 没有单独的 web_search 提示词文件，它用的是「tool schema 本身」

你说得对：工具要好用，“提示词/指令”确实是技巧的一部分。问题在于——**Codex 对 `web_search` 这个内建工具，并没有像我们 SDK 那样配一份 `toolprompts/web_search.txt` 之类的专用提示词**。

它更像是“把方向盘交给 Responses API + 模型内置能力”，Codex 这边提供的“提示词形态”主要是：

1) **把 `web_search` tool 放进 request 的 `tools` 列表里**（这是模型看到的“工具说明”）  
2) **配置 `external_web_access`** 控制 cached/live（这也是 tool spec 的一部分）  
3) UI/日志里展示“发生了什么”时，只把 action 参数拼成一行 detail（不展示结果全文）

换句话说，Codex 的“web_search 提示词”不是 Markdown，而是 **发送给模型的 `tools` JSON**。

下面是 Codex 里这份 “tool schema 提示词” 的原样代码（直接决定了最终序列化出来的 JSON 长什么样）：

```text
Codex: codex-rs/core/src/client_common.rs (tools::ToolSpec::WebSearch)
  166:     /// When serialized as JSON, this produces a valid "Tool" in the OpenAI
  167:     /// Responses API.
  168:     #[derive(Debug, Clone, Serialize, PartialEq)]
  169:     #[serde(tag = "type")]
  170:     pub(crate) enum ToolSpec {
  171:         #[serde(rename = "function")]
  172:         Function(ResponsesApiTool),
  173:         #[serde(rename = "local_shell")]
  174:         LocalShell {},
  175:         // TODO: Understand why we get an error on web_search although the API docs say it's supported.
  176:         // https://platform.openai.com/docs/guides/tools-web-search?api-mode=responses#:~:text=%7B%20type%3A%20%22web_search%22%20%7D%2C
  177:         // The `external_web_access` field determines whether the web search is over cached or live content.
  178:         // https://platform.openai.com/docs/guides/tools-web-search#live-internet-access
  179:         #[serde(rename = "web_search")]
  180:         WebSearch {
  181:             #[serde(skip_serializing_if = "Option::is_none")]
  182:             external_web_access: Option<bool>,
  183:         },
  184:         #[serde(rename = "custom")]
  185:         Freeform(FreeformTool),
  186:     }
```

你会注意到一个很关键的事实：这里 **没有 description、没有 usage guidelines** ——这就是 Codex 为 web_search 提供给模型的全部“提示词形态”。（真正的语义/用法，靠模型本身 + OpenAI 内建 tool 行为。）

**代码入口（Codex repo）**
- `web_search_call` 的历史 item 结构：`codex-rs/protocol/src/models.rs:167`（`ResponseItem::WebSearchCall`）
  - 你会发现它只有 `status` 和 `action`，没有“results/body/content”这种大字段。
  - 快速定位：`rg -n "WebSearchCall" codex-rs/protocol/src/models.rs`
- “动作参数 → 一行短句”的拼装：`codex-rs/core/src/web_search.rs:18`（`web_search_action_detail` / `web_search_detail`）
  - 它把 `Search(query/queries)`、`OpenPage(url)`、`FindInPage(url, pattern)` 变成可展示的 detail。
  - 快速定位：`rg -n "web_search_action_detail" codex-rs/core/src/web_search.rs`
- 什么时候把 web_search 工具暴露给模型：`codex-rs/core/src/tools/spec.rs:1563`（`match config.web_search_mode`）
  - 快速定位：`rg -n "match config.web_search_mode" codex-rs/core/src/tools/spec.rs`

**它在做什么（伪代码）**

```text
# tools/spec.rs：根据配置决定要不要给模型 web_search 工具
if config.web_search_mode == CACHED:
  tools += web_search(external_web_access=false)
else if mode == LIVE:
  tools += web_search(external_web_access=true)
else:
  tools 不包含 web_search
```

```text
# protocol/models.rs：历史里只记录一个轻量 item
WebSearchCall {
  status: "completed" | ...,
  action: { type: "search"|"open_page"|"find_in_page", ... }
}

# 重点：没有 results[] / page_text / html 之类的大字段
```

上面这段“按配置塞 tool spec”的代码，在 Codex 里对应的原样实现是：

```text
Codex: codex-rs/core/src/tools/spec.rs (根据 web_search_mode push ToolSpec::WebSearch)
 1563:     match config.web_search_mode {
 1564:         Some(WebSearchMode::Cached) => {
 1565:             builder.push_spec(ToolSpec::WebSearch {
 1566:                 external_web_access: Some(false),
 1567:             });
 1568:         }
 1569:         Some(WebSearchMode::Live) => {
 1570:             builder.push_spec(ToolSpec::WebSearch {
 1571:                 external_web_access: Some(true),
 1572:             });
 1573:         }
 1574:         Some(WebSearchMode::Disabled) | None => {}
 1575:     }
```

**所以，“找到了什么摘要”到底是谁写的？（关键澄清）**

- 在 Codex 的持久化模型里：**没有一个系统自动生成的“结果摘要字段”**。
- 如果你希望“找到了什么”在历史里可追溯，靠的是两种方式之一：
  1) **模型自己用一条 `assistant` 消息说清楚**（例如“我在页面里找到了 X，相关段落是 …”），这条文本会进入 history；
  2) 你在产品侧/SDK 侧实现一个**显式的‘结果摘要步骤’**：把原始 results/body（不进历史）→ 生成一个小 JSON 摘要（进历史），并附带 artifacts 指针（文件路径/哈希/来源 URL 等）。

换句话说：Codex 的工程选择是“**历史先轻量化**（动作可追溯），需要结果再让 assistant/产品补一条小摘要”，而不是把 web 搜索的原始材料当成历史正文。

#### 3.2 Codex UI 里“找到了什么”的那行短句是怎么来的：只是把 action 参数拼出来

你如果看到类似“`'foo' in https://bar`”这种“像摘要但又很短”的文案，它来自这个函数：`codex-rs/core/src/web_search.rs:18`。

它做的事非常直白：
- `Search`：展示 `query`（或取 `queries[0]`，多 query 时用 `"...“` 结尾）
- `OpenPage`：展示 url
- `FindInPage`：展示 `"'pattern' in url"`

这就是“找到了什么”的来源——**它描述的是“我搜了什么/我在什么页面里找了什么 pattern”，不是“我找到了哪些结果”。**

原样代码如下（这段就是你要的“技巧”：让 UI 可读，但不把正文塞进历史）：

```text
Codex: codex-rs/core/src/web_search.rs
  18: pub fn web_search_action_detail(action: &WebSearchAction) -> String {
  19:     match action {
  20:         WebSearchAction::Search { query, queries } => search_action_detail(query, queries),
  21:         WebSearchAction::OpenPage { url } => url.clone().unwrap_or_default(),
  22:         WebSearchAction::FindInPage { url, pattern } => match (pattern, url) {
  23:             (Some(pattern), Some(url)) => format!("'{pattern}' in {url}"),
  24:             (Some(pattern), None) => format!("'{pattern}'"),
  25:             (None, Some(url)) => url.clone(),
  26:             (None, None) => String::new(),
  27:         },
  28:         WebSearchAction::Other => String::new(),
  29:     }
  30: }
```

**借鉴到我们 SDK 的落地翻译**  
WebFetch/WebSearch 这种“外部世界材料”最好走同一条路：
1) 历史里留下“小 JSON（动作+摘要+指针）”  
2) 原始内容落到 session artifacts（文件）  
3) 需要继续看 → 用 Read/Grep 去“精读/检索”文件，而不是反复把全文塞回上下文

### 4) 源头硬上限：即使你忘了截断，也别让系统被拖死（例如 exec 输出缓冲 1 MiB）

这属于“安全带”：你可以不系（不推荐），但它能在你犯错时保命。

**代码入口（Codex repo）**
- unified exec 输出缓冲上限：`codex-rs/core/src/unified_exec/mod.rs:58`（`UNIFIED_EXEC_OUTPUT_MAX_BYTES = 1 MiB`）
  - 快速定位：`rg -n "UNIFIED_EXEC_OUTPUT_MAX_BYTES" codex-rs/core/src/unified_exec/mod.rs`

**它在做什么（伪代码）**

```text
MAX_BYTES = 1MiB
buffer.append(chunk)
if buffer.size > MAX_BYTES:
  drop_middle_or_oldest_until_fit()   # 保留可用信息，避免内存爆炸
```

### 5)（加餐）Codex 的 subagent 机制：它确实有，但不是“所有 web 都丢给 subagent”

你之前问“是不是把搜索请求都交给 subagent 省 context？”——Codex 的答案更务实：**subagent 主要用于特定任务（review/compact 等），不是默认拿来替 web_search 收拾烂摊子**。

**代码入口（Codex repo）**
- 启动 subagent（示例：review 子线程）：`codex-rs/core/src/codex_delegate.rs:51`（`SessionSource::SubAgent(SubAgentSource::Review)`）
  - 快速定位：`rg -n "SubAgentSource::Review" codex-rs/core/src/codex_delegate.rs`
- 把 subagent 标记写进请求头：`codex-rs/codex-api/src/endpoint/responses.rs:79`（`x-openai-subagent`）
  - 快速定位：`rg -n "x-openai-subagent" codex-rs/codex-api/src/endpoint/responses.rs`
- 甚至 review subagent 会显式禁用 web search：`codex-rs/core/src/tasks/review.rs:91`
  - 快速定位：`rg -n "web_search_mode" codex-rs/core/src/tasks/review.rs`

**对我们意味着什么**  
如果我们要“研究子会话/子 agent”，那是一个额外能力（很强但工程量大）；而“先把上下文稳定住”，最该学 Codex 的仍然是：**统一预算化 + artifacts 化**。

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
