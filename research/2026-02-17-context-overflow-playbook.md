# 上下文总是“炸”？从 Codex 取经，给 OpenAgentic SDK 的一份“治爆”备忘录（v1）

日期：2026-02-17  
最后更新：2026-02-17  
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

## 这份报告怎么读（不迷路版）

- 你如果现在就烦到想摔键盘：直接跳到「可以立刻搬回家的改造清单」那一节，照着 A → B → D 的顺序改，先止血。
- 你如果要把“治爆”做成可长期维护的工程能力：从「我们现在为什么容易炸」→「Codex 怎么稳住不炸」→「改造清单」按顺序读。
- 你如果关心 WebView（`web_*`）这条链路：重点看「我们现在为什么容易炸」里关于 `WebSnapshot` 的预算与“过程材料隔离”的建议，以及「Codex 的 sub-agent 思路」那一段。

## 目录（按“工程落地”顺序排）

1) 我们现在为什么容易炸（结合本仓库代码）  
2) Codex 是怎么稳住不炸的（代码入口 + 伪代码）  
3) 可以立刻搬回家的改造清单（按性价比排序）  
4) 下一步动刀顺序（建议）  
5) 验收清单（每改一刀就对照）  
6) 附：我们已经有的基础（别重复造轮子）

## 我们现在为什么容易炸（以现有 SDK 代码为参照）

先说一句公道话：我们不是“啥都没做”。相反，我们已经做了不少关键防护——只是这些防护还不够“从源头兜底”，所以一旦碰上 WebFetch/WebView 这类高噪音链路，还是会炸。

### 我们已经做对的事（别丢）

1) **events.jsonl 不落 streaming delta（避免 trace 膨胀）**  
   - `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/sessions/FileSessionStore.kt:65`（`AssistantDelta` 直接跳过，不写盘）

2) **Preflight compaction：在 provider call 之前先估算 tokens，提前 compact**  
   - `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/runtime/OpenAgenticSdk.kt:176`（`Preflight compaction`）  
   - `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/runtime/OpenAgenticSdk.kt:1123`（`estimateModelInputTokens`）

3) **Tool output pruning：旧 tool result 可以被占位清空（减少回放输入体积）**  
   - 占位符常量：`external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/compaction/Compaction.kt:40`（`TOOL_OUTPUT_PLACEHOLDER`）  
   - Responses input 里用占位符替换：`external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/runtime/OpenAgenticSdk.kt:490`  

4) **WebView 工具本身已经有“预算意识”**（这点非常好，只是还需要“历史侧兜底”）  
   - `web_snapshot` 默认可见文本预算：`app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/web/WebTools.kt:338`（`renderMaxCharsTotal = 12_000`）  
   - `web_query` 默认 `max_length=4000`：`app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/web/WebTools.kt:591`  
   - 还已经能落地截图 artifact：`app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/web/WebTools.kt:139`（`.agents/artifacts/web_screenshots`）

### 我们仍然会炸的根因（结合“真实代码路径”）

根因不是一句“max_chars 太大”这么简单，而是三件事叠在一起：

1) **工具输出是“内联进 provider input 的字符串”**（会二次膨胀）  
   在 `buildResponsesInput` 里，`ToolResult.output` 会先被 JSON 序列化，然后再塞进 `"output": "<json-string>"`。这会导致转义/引号额外开销，越长越夸张：  
   - `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/runtime/OpenAgenticSdk.kt:496`（`function_call_output` + `encodeToString(...)`）

2) **WebFetch 虽然截断，但依旧是“把正文塞回历史”**（缺少“落文件 + 指针”这一层）  
   - `max_chars`：`external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/tools/WebFetchTool.kt:47`（默认 24_000；上限 80_000）  
   - 返回里仍然是 `text`（截断后的正文）：`external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/tools/WebFetchTool.kt:95`  
   - prompt 里也强调“size-bounded”，但仍是“返回文本”：`external/openagentic-sdk-kotlin/src/main/resources/me/lemonhall/openagentic/sdk/toolprompts/webfetch.txt:3`

3) **我们把 compaction 的门槛设得很高，但还缺“工具输出入口的硬预算”**  
   App 侧当前把 `contextLimit` 配到 200k（为了大上下文模型），这本身没错，但它意味着：如果工具输出持续往历史里堆，等 compaction 出手时往往已经很难救得漂亮。  
   - `app/src/main/java/com/lsl/kotlin_agent_app/agent/OpenAgenticSdkChatAgent.kt:156`（`contextLimit = 200_000`）

于是，最常出问题的场景就变成了：

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

#### 3.3 Codex 的 “System Prompts / Base Instructions” 里有没有写工具调用技巧？有，但偏“流程/习惯”，不偏“web_search 教程”

你问得很关键：如果 Codex 只给了一个很薄的 `web_search` tool schema，那“怎么用得好”靠什么？答案是两层：

1) **全局 Base Instructions（你说的 System Prompts）**：教模型“怎么当个靠谱的 CLI coding agent”（包括工具调用的节奏、怎么汇报、怎么组织多次 tool calls）。
2) **工具 schema**：把工具“长什么样/有哪些参数/什么时候可用”暴露给模型。对 `web_search` 这种内建 tool，语义主要由模型+API 内建实现决定；Codex 这里不会再写一份“怎么搜网页”的教程。

下面把这层 Base Instructions 的关键片段在报告里“放钉子”（文件 + 行号），避免后续实现时只能凭印象：

**(a) “调用工具前先打一段前导说明（preamble）”**

- 文件：`E:\development\codex\codex-rs\core\prompt.md`  
- 关键行：`codex-rs/core/prompt.md:33`（`### Preamble messages`）

它要求模型在 tool call 前先说一句“我接下来要干嘛”，并强调：合并相关操作、简短、承接上下文、避免对每个小读取都啰嗦。
这不是强制机制（代码不会替你做），但在交互体验上非常关键：**减少用户焦虑，减少无意义追问，也减少“重复解释导致的上下文膨胀”。**

**(b) “用工具要讲效率：搜文件优先 rg；修改文件优先 apply_patch”**

- 文件：`E:\development\codex\codex-rs\core\gpt-5.2-codex_prompt.md`  
- 关键行：`codex-rs/core/gpt-5.2-codex_prompt.md:5`（prefer `rg`），`:11`（prefer `apply_patch`）

它的本质不是“语法偏好”，而是控制交互成本：
- `rg` 比 `cat`/`grep -R` 更快更准，通常能用更少输出拿到定位
- `apply_patch` 鼓励“最小 diff”，避免把大段文件全文反复搬进上下文

**(c) “用 sub-agent 节省上下文/日志噪音（明确写在 prompt 里）”**

- 文件：`E:\development\codex\codex-rs\core\templates\collab\experimental_prompt.md`  
- 关键行：`codex-rs/core/templates/collab/experimental_prompt.md:12`

这里写得非常直白：跑测试/某些命令会输出大量日志，为了优化主上下文，可以 spawn 一个 agent 去做；并且要求你告诉子 agent 不要再 spawn 子 agent，避免递归。

这点和我们“上下文总炸”的痛点是直接对应的：**Codex 把“节省上下文”当作一条操作准则写进了 prompt，而不是事后靠 compaction 亡羊补牢。**

为了避免后续我们实现时“只记得大意”，这里把最关键那句原样摘出来（来自 `codex-rs/core/templates/collab/experimental_prompt.md`）：

```text
* Running tests or some config commands can output a large amount of logs. In order to optimize your own context, you can spawn an agent and ask it to do it for you. In such cases, you must tell this agent that it can't spawn another agent himself (to prevent infinite recursion)
```

并且，这不是“口号”。Codex 里确实有一套可运行的 multi-agent 控制平面：

- `E:\development\codex\codex-rs\core\src\agent\control.rs:40`（`AgentControl::spawn_agent`：创建 thread + 发送初始 prompt）
- `E:\development\codex\codex-rs\core\src\codex_delegate.rs:26`（`run_codex_thread_interactive/one_shot`：起 sub-agent、转发事件、把 approvals 路由回父 session）

**把这条“sub-agent 省上下文”的思路，直接借鉴到我们 WebView 工具链上（强烈建议）**

你提到的“驱动 WebView 去搞事情”其实和“跑测试输出巨量日志”是同一类灾难现场：  
DOM 片段、Console logs、Network logs、截图、重试/等待的状态机……如果我们把这些一股脑当成普通对话消息/事件写进历史，**炸上下文只是时间问题**。

Codex 这条 prompt 规则，翻译成我们 SDK 的工程化落地，可以非常具体：

1) **把 WebView 执行从“主对话的一部分”变成“可委托的一次性子任务”**  
   主 agent 只拿一个结构化结果（几百 token 以内），所有原始过程材料进 artifacts（文件/数据库），想看再按需取。

2) **约束子任务输出：默认不回灌原始日志，只回“高信号摘要 + 指针”**  
   这点最好不是“靠模型自觉”，而是靠工具返回 schema 去卡死：`webview.run` 返回固定字段（summary、artifacts、next_steps），不允许把 trace 全文塞回 `summary`。

3) **可选：把“WebView 子任务”实现成真正的 sub-agent（有独立上下文预算）**  
   参考 Codex 的做法，父 session 负责“交互与决策”，子 session 负责“脏活累活”，并明确告诉它：禁止再 spawn，避免递归失控。

下面是“照着写就能做出来”的伪代码级接口/流程（刻意对齐 Codex 的概念）：

```text
// 主 agent：负责用户对话 + 最终结论
handleUserIntent(intent):
  if intent.requiresWebViewAutomation():
    return runWebViewAsSubTask(intent)
  else:
    return runNormally(intent)

runWebViewAsSubTask(intent):
  sub = spawn_sub_agent(
          purpose="webview-worker",
          instructions=[
            "默认不要把 trace/log 原文写回对话",
            "把截图/DOM/网络日志落到 artifacts",
            "只返回：结论 + 关键证据点 + artifacts 指针",
            "禁止再 spawn 子 agent"
          ])

  // 子 agent：只做执行，不做长篇叙述
  web = sub.call_tool("webview.run", {
          goal: intent.goal,
          url: intent.url,
          time_budget_ms: 120000,
          record: ["screenshot","console","network","dom_snapshots"],
          artifact_dir: ".agents/artifacts/webview/<run_id>/"
        })

  // 子 agent 把“脏材料”写盘（trace.jsonl + screenshots + net.har 等）
  // 主 agent 只拿一个小结果（<= N tokens）
  return {
    ok: web.ok,
    what_happened: web.summary,          // 10~30 行以内
    evidence: web.key_evidence[],        // 3~7 条要点（含 selector/url/截图编号）
    artifacts: web.artifacts[],          // 可点击/可检索的指针
    next_steps: web.next_steps[]
  }
```

**为什么这招对我们特别值钱？**  
因为 WebView 自动化天然是“高噪音 I/O 密集型任务”。你不可能指望 compaction 每次都救得回来；最划算的办法是：一开始就把“噪音”隔离到系统外（artifacts / 子任务上下文），主对话只保留“可复用、可推理、可继续行动”的小信息。

**(d) 这份 Base Instructions 在代码里是怎么进请求的？**

Codex 会在启动 session 时确定 base instructions 的优先级（config override / session meta / model default），代码在：

- `E:\development\codex\codex-rs\core\src\codex.rs:341`（Resolve base instructions priority order）

伪代码（对应 `codex-rs/core/src/codex.rs:341` 的逻辑）：

```text
modelInfo = modelsManager.get_model_info(model)
baseInstructions =
  config.base_instructions
  or conversation_history.session_meta.base_instructions
  or modelInfo.get_model_instructions(personality)

ResponsesAPI.request.instructions = baseInstructions
ResponsesAPI.request.tools = [...tool schemas...]
```

补一句：你在 `codex-rs/core/models.json` 里看到的 `base_instructions` 字段，本质就是“这一层 System Prompt 的落地载体”。例如 `gpt-5.2-codex` 的 `base_instructions` 就是 `gpt-5.2-codex_prompt.md` 的内容内联进去的（可在 `codex-rs/core/models.json:86` 附近看到）。

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

## 把 Codex 的“作业”翻译成我们仓库里的落点（对照表）

这一段的目的很朴素：**别让“抄作业”停留在口号**。我们要能一眼看出：改动应该落在哪个文件、哪个函数、为什么那里最划算。

| Codex 的做法 | 对应到我们现在的代码现实 | 我们应该怎么抄 |
|---|---|---|
| 统一入口截断 tool outputs（写历史/回喂模型前就预算化） | 我们目前主要在构建 provider input 时把 `ToolResult` 直接内联成 JSON 字符串：`external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/runtime/OpenAgenticSdk.kt:496` | 在 `buildResponsesInput/buildLegacyMessages` 里对 `ToolResult` 做统一预算（头+尾 + marker），并优先输出“预览 + 指针”结构 |
| 大输出落文件，history 只留指针 | 我们的 `BashTool` 已经这么干：`external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/tools/BashTool.kt:168`（`full_output_file_path`） | 让 `WebFetch`、`web_snapshot` 也学这一套：正文/快照写 artifact，tool.result 只回小 JSON |
| WebSearch 历史项只记动作，不记结果全文 | 我们的 tool schema 侧已经有 prompts 注入：`external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/tools/OpenAiToolSchemas.kt:64`（`ToolPrompts.render("webfetch", ...)`） | 强化 prompts：把“原始材料别塞回对话”写进工具说明，并把“结果摘要字段”显式化（summary/evidence/artifact_path） |
| prompt 里写明：高日志任务用 sub-agent（省主上下文） | 我们 app 侧已经对 WebView 写了“预算铁律”：`app/src/main/java/com/lsl/kotlin_agent_app/agent/OpenAgenticSdkChatAgent.kt:208`（`web_*` 20 次上限等） | 在 SDK/工具层再加一层：把 WebView 自动化过程材料隔离到 artifacts（必要时做 sub-session/sub-agent），主对话只拿结构化结果 |

## 可以立刻“搬回家”的改造清单（按性价比排序）

下面我按“最快能缓解痛苦”的顺序排。我们后续可以一条条过，愿意的话就落到 PRD/Plan。

### A. 在 SDK 的“写入事件/构建模型输入”处做统一预算（强烈推荐，优先级 #1）

目标：不管哪个 Tool 一时兴起吐了个大包，都别让 session history 变成炸药桶。

建议落点（按我们当前代码现实，能直接开工）：

- **优先落在“构建 provider input”这一层**（更安全：events.jsonl 仍保留完整可追溯；回喂模型时再预算化）：  
  - `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/runtime/OpenAgenticSdk.kt:448`（`buildResponsesInput`）  
  - `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/runtime/OpenAgenticSdk.kt:509`（`buildLegacyMessages`）  
  这里会把 `ToolResult.output` JSON 序列化后塞进字符串字段（`"output"` / `"content"`），所以预算化放这层最划算：**不然一条 tool.result 可能把下一轮输入直接撑爆**。
- **备选落点：写 events.jsonl 前就做预算化**（更激进，适合移动端存储压力极大时）：  
  - `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/sessions/FileSessionStore.kt:61`（`appendEvent`）  
  但这会牺牲“完整审计/回放”，除非你同时把完整内容写到 artifacts 并在 event 里留指针。

策略建议：
  - “头+尾”截断 + marker
  - JSON 结构尽量保持（比如 `text_preview`、`artifact_path`、`truncated=true`），不要把 JSON 截成半截字符串
  - 预算别算得太死：预留一点序列化/转义开销（Codex 那个 `* 1.2` 的小细节非常值得学）

验收口径（很工程化，但舒服）：
1) 连续 10 次 WebFetch，`events.jsonl` 仍保持可控体积  
2) 下一轮请求前的 `estimateModelInputTokens` 不会突然跳到离谱

### B. WebFetchTool 学 BashTool：大内容落文件，返回“指针 + 预览”

现在的 `WebFetchTool` 会返回 `text`，最多 80k 字符：  
- `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/tools/WebFetchTool.kt:47`（`max_chars` 默认 24k，上限 80k）  
- `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/tools/WebFetchTool.kt:95`（返回 JSON 里仍然放 `text`）  
同时 tool prompt 也已经在提醒“size-bounded 防爆”：`external/openagentic-sdk-kotlin/src/main/resources/me/lemonhall/openagentic/sdk/toolprompts/webfetch.txt:3`。  

但“只截断并返回文本”还不够——我们真正想要的是“两层输出”（preview 进上下文，full 落盘）：

- **永远返回小 JSON**（适合进上下文）：
  - `final_url / title / content_type / mode / truncated`
  - `text_preview`（比如 4k~8k，头+尾）
  - `artifact_path`（完整内容写到 artifact 文件）
- **完整内容写到文件**（对齐 BashTool 的体验；它已经能输出 `full_output_file_path`）：  
  - `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/tools/BashTool.kt:49`（`.openagentic-sdk/tool-output/...`）  
  - `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/tools/BashTool.kt:168`（`full_output_file_path`）  
  模型如果要继续看：用 `Read/Grep` 去“读文件的一段/搜关键字”，而不是再 WebFetch 叠 buff。

这样一来，WebFetch 不再是“上下文杀手”，而是一个“产出 artifact 的抓取器”。

### C. “搜索/抓取”结果分层：历史里只留摘要，原始材料进 artifacts（优先级 #2）

一旦我们把外部材料当作 artifacts，就能自然做两件事：

1) UI 体验更好：Files 页可以直接浏览这些抓取结果文件（配合 JSON/JSONL 默认“查看模式”会很舒服）。  
2) 模型更稳定：上下文里不再重复塞同一份大材料。

这一步可以先从 WebFetch 做，后面再扩到“网页截图、PDF、长日志、HTML”等。

顺便说一句：**WebView 的 `web_snapshot` 也非常适合走这条路**。现在它会把 `snapshot_text`（默认最多 12k 字符）直接作为 tool.result 写回历史：  
- `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/web/WebTools.kt:338`（`renderMaxCharsTotal = 12_000`）  
建议后续改成“预览 + 指针”：把完整快照（或 raw snapshot JSON）写到 `.agents/artifacts/...`，tool.result 只保留 `snapshot_sha256 + preview + artifact_path`，需要精读再 `Read/Grep`。

### D. compaction 的触发更“兜底”：usage 不可靠时，用估算触发（优先级 #3）

我们现在有 `wouldOverflow(usage)`，但真实世界里 provider 的 usage 字段有时缺失/不准/延迟。建议：

- 如果 usage 不可用：用 `estimateModelInputTokens(modelInput)` 兜底判断是否接近阈值
- compaction 触发不要等“>=100%”：可以在 80%~90% 就先做一次，避免一脚踩爆
  - 对应代码路径（便于开工定位）：`external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/runtime/OpenAgenticSdk.kt:312`（`wouldOverflow(options.compaction, modelOut.usage)`）

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

- tool 输出占位清空：  
  - `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/compaction/Compaction.kt:40`（`TOOL_OUTPUT_PLACEHOLDER`）  
  - 输入构建时替换占位：`external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/runtime/OpenAgenticSdk.kt:490`
- Bash 大输出落文件（我们后面给 WebFetch/WebSnapshot 抄的“样板间”）：  
  - `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/tools/BashTool.kt:49`（落盘目录 `.openagentic-sdk/tool-output`）  
  - `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/tools/BashTool.kt:168`（`full_output_file_path`）  
  - 以及 prompt 已经明确“超限会写文件，别自己 head/tail”：`external/openagentic-sdk-kotlin/src/main/resources/me/lemonhall/openagentic/sdk/toolprompts/bash.txt:27`
- Read/Grep/List 这套“围绕文件精读/检索”的能力已经具备：  
  - `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/tools/ReadTool.kt`  
  - `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/tools/GrepTool.kt`  
  - `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/tools/ListTool.kt`

下一步不是从 0 到 1，而是把这些能力串起来，形成一个统一的“预算化 + artifact 化”的闭环。

---

## 附：在本仓库里快速定位（复制就能用）

PowerShell 下建议这样搜（避免翻文件翻到眼花）：

```powershell
rg -n "buildResponsesInput|buildLegacyMessages|function_call_output" external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/runtime/OpenAgenticSdk.kt
rg -n "max_chars|truncated\\\"|\\\"text\\\"" external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/tools/WebFetchTool.kt
rg -n "full_output_file_path|tool-output" external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/tools/BashTool.kt
rg -n "renderMaxCharsTotal|web_snapshot|snapshot_text" app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/web/WebTools.kt
```
