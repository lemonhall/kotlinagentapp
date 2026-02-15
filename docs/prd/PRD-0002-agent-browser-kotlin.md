# PRD-0002：agent-browser-kotlin（Kotlin 纯库，解决“HTML/DOM 太长吃 token”）

## 1. 背景与问题

我们在做一个 Android 聊天型 Agent App（供应商为 **OpenAI Responses API 风格**，且**必须流式**）。Agent 具备 Web 能力（WebView/WebFetch/WebSearch 等），会把网页内容作为工具结果返回给模型。

当前严重问题：工具把 `document.body.outerHTML`（或整页 DOM/HTML）直接返回给模型，导致一次 `tool.result` 就可能几十万字符，下一轮模型请求把该内容带上后触发 `context_length_exceeded`（上下文窗口超限），即使“对话轮次不多”也会爆。

证据（真机 dump 的 session）：曾出现单条 `tool.result` 约 590KB 的返回内容。

我们希望借鉴 `vercel-labs/agent-browser` 的核心理念：**snapshot + refs**，即用“低 token 的页面表征 + 引用(ref)交互”替代“整页 HTML 回传”：

- `snapshot` 返回可控长度的页面结构摘要，并给可交互/可读元素分配 `@e1/@e2/...` 引用；
- 后续按 ref 精准读取/操作，而不是反复喂整页 DOM。

参考项目：`https://github.com/vercel-labs/agent-browser`（重点看 snapshot/refs 的输出形态与压缩策略）。

## 2. 目标（Goals）

1) 输入：一段很大的 HTML（`String`），输出：**可控上限**的 `Snapshot`（结构化、短）+ `Ref` 映射。  
2) Snapshot 重点保留“可读/可操作”信息：文本、链接、按钮、输入框、表单元素、标题层级、列表项等；可配置 `interactiveOnly`。  
3) 支持按 ref 做后续查询：取某节点 `text/attrs/outerHTML`（但必须有长度上限与截断标记）。  
4) 预算约束：任何输出都必须在预算内（字符数、节点数、深度、每节点文本长度等），并显式标记 `truncated`。  
5) 纯 Kotlin 库：**不依赖 Android/WebView**；仅需要塞入 HTML 文本即可启动。  
6) 对上层 Agent 工具提供稳定协议：模型看到的是 snapshot；工具调用携带 ref 做精准查询。

## 3. 非目标（Non-goals）

- 不做真实浏览器自动化、不执行 JS、不处理动态 DOM（这些由适配层负责：例如 WebView 注入 JS 后把 HTML/片段交给本库）。  
- 不追求 100% HTML 规范解析覆盖；优先保证鲁棒、预算可控、对常见页面有效。  
- 不做网络抓取（fetch）。

## 4. 设计概览（API 设想）

### 4.1 数据结构（建议）

```kotlin
data class SnapshotOptions(
  val maxCharsTotal: Int = 12_000,
  val maxNodes: Int = 200,
  val maxDepth: Int = 12,
  val maxTextPerNode: Int = 200,
  val interactiveOnly: Boolean = true,
  val includeAttrs: Set<String> = setOf("href","name","type","value","placeholder","aria-label","role"),
  val scopeSelector: String? = null, // 可选：只 snapshot 某区域；初期可先不实现复杂 selector
  val outputFormat: OutputFormat = OutputFormat.PLAIN_TEXT_TREE,
  val dedupeText: Boolean = true,
)

data class SnapshotStats(
  val inputChars: Int,
  val nodesVisited: Int,
  val nodesEmitted: Int,
  val truncated: Boolean,
  val reasons: List<String> = emptyList(),
)

data class NodeRef(
  val ref: String,           // e1/e2/...
  val tag: String,           // a/button/input/...
  val role: String? = null,  // aria/语义推断
  val name: String? = null,  // aria-label/文本摘要
  val attrs: Map<String,String> = emptyMap(), // 受 includeAttrs 与截断控制
  val path: String? = null,  // 简短路径，可选
  val textSnippet: String? = null,
)

data class SnapshotResult(
  val snapshotText: String,
  val refs: Map<String, NodeRef>,
  val stats: SnapshotStats,
)

enum class RefQueryKind { TEXT, ATTR, OUTER_HTML }

data class RefQueryResult(
  val ref: String,
  val kind: RefQueryKind,
  val value: String,
  val truncated: Boolean,
)
```

### 4.2 核心函数（建议）

- 一次性便捷 API（简单但重复解析）：
  - `fun snapshot(html: String, options: SnapshotOptions = SnapshotOptions()): SnapshotResult`
  - `fun queryRef(html: String, ref: String, kind: RefQueryKind, limitChars: Int = 4_000): RefQueryResult`

- 可复用解析结果（推荐，减少重复解析）：
  - `fun buildDocument(html: String, options: SnapshotOptions = SnapshotOptions()): SnapshotDocument`
  - `fun snapshot(doc: SnapshotDocument): SnapshotResult`
  - `fun query(doc: SnapshotDocument, ref: String, kind: RefQueryKind, limitChars: Int = 4_000): RefQueryResult`

### 4.3 Snapshot 输出格式（给模型看的文本）

建议输出（`PLAIN_TEXT_TREE`）类似：

```
[snapshot] nodes=120 emitted=80 truncated=false
@e1  [button] "登录"
@e2  [link href="https://..."] "价格"
@e3  [input type="text" name="q" placeholder="搜索"]
@e4  [h1] "今日金价"
...
```

约束：

- `snapshotText.length <= options.maxCharsTotal`
- 单行文本/属性必须截断（避免某行爆炸）
- 若达到预算：停止遍历并将 `stats.truncated=true` + `reasons` 说明（如 `maxCharsTotal` / `maxNodes`）

## 5. 解析与抽取策略

### 5.1 HTML 解析（实现建议）

优先使用成熟解析器（纯 Kotlin/JVM）：

- `org.jsoup:jsoup`（JVM/Android 可用，容错好，生态成熟）

### 5.2 Ref 生成与候选节点规则

- 采用 DFS 遍历 DOM
- 遇到“候选节点”分配 `e1,e2,...`，并输出一行 snapshot
- 候选规则（可配置）：
  - interactiveOnly=true：`a, button, input, select, textarea, option, form, [role=button], [onclick]` 等
  - interactiveOnly=false：额外包括 `h1-h6, p, li, article, section` 等“可读结构”
- 文本提取：whitespace normalize；限制 `maxTextPerNode`
- attrs：仅白名单，且每个 value 限长

### 5.3 预算控制（硬约束）

- 全局：`maxCharsTotal`（最重要）
- 结构：`maxNodes`、`maxDepth`
- 字段：`maxTextPerNode`、attrs value 的上限
- 必须返回 `stats`，让上层工具/Agent 可以在 Tool Trace 里可视化预算命中原因

## 6. 与上层工具衔接（重要）

上层 Web 工具（WebView/WebFetch）应改为：

1) 抓到 HTML（或 DOM 片段）后 **不再直接返回整页 outerHTML**；
2) 调用本库 `snapshot(...)`，把 `snapshotText + stats` 作为主要 tool.result 返回给模型；
3) 模型需要更多细节时，再调用工具 `query_ref(ref, kind, limit)` 或 `snapshot(scope=...)`。

必须保证：工具返回永远不会把整页 HTML 原样塞回模型上下文。

## 7. 交付物（Deliverables）

- Kotlin Library：`agent-browser-kotlin`（Gradle 工程，可发布为 maven artifact）
- 核心 API + 实现：
  - `SnapshotOptions / SnapshotResult / SnapshotStats / NodeRef`
  - `buildDocument / snapshot / query`
- 单元测试：
  - 大 HTML（>500KB）能 snapshot 且输出受控
  - interactiveOnly 行为正确
  - 截断标记与 stats 正确
  - ref 稳定性：同一输入同一 options 下 refs 顺序一致
- README：
  - 背景、快速示例、最佳实践（禁止 outerHTML 直喂模型）
  - 推荐默认 options（例如 12k chars）

## 8. 验收标准（Acceptance Criteria）

- 输入 600KB HTML，输出 `snapshotText.length <= maxCharsTotal`，不会 OOM/卡死（合理时间内完成）。  
- 输出包含可交互元素 refs（link/button/input）且包含必要 attrs（href/type/name/placeholder 等）。  
- `query` 在 `limitChars` 下返回稳定且可控的截断结果，且不会泄露整页超长内容。  
- 无 Android 依赖，可在 JVM/Android 工程中引用。

## 9. 推荐默认配置（起点）

- `maxCharsTotal=12000`
- `maxNodes=200`
- `maxDepth=12`
- `maxTextPerNode=200`
- `interactiveOnly=true`
- `includeAttrs={href, name, type, value, placeholder, aria-label, role}`
- 输出：`PLAIN_TEXT_TREE`



====================

最后被改成了另一种实现：

柠檬叔，这个 PRD 写得很扎实，v2 和 v3 的演进逻辑也很清晰。我先仔细读完两稿，然后帮你合并成一份完整的 PRD v3（final）。

让我先去看看 agent-browser 的源码，确认一些关键细节，然后再动手合并。

好的，我已经看完了 agent-browser 的 README 和完整命令集。现在让我去看看它的核心 snapshot 源码，特别是 ref 映射和 ARIA tree 的实现。

CLI 是 Rust 写的，核心逻辑应该在 Node.js daemon 那边。让我看看 daemon 的源码结构。

让我搜索 snapshot 相关的核心文件。

找到了核心文件 `src/snapshot.ts`。让我去看看它的源码。

我已经完整读完了 agent-browser 的 `snapshot.ts` 源码。现在我对它的实现有了深入理解。让我关闭浏览器，然后给你输出合并后的 PRD。

柠檬叔，我把 agent-browser 的 `snapshot.ts` 源码完整读了一遍。几个关键发现需要在合并 PRD 时体现：

1. agent-browser 的 snapshot 核心依赖 Playwright 的 `locator.ariaSnapshot()` —— 它拿到的是已经格式化好的 ARIA tree 文本，然后在文本层面做正则解析、加 ref、过滤。它并不自己遍历 DOM。
2. RefMap 存的是 `{ selector, role, name, nth }`，其中 selector 是 `getByRole('button', { name: "Submit", exact: true })` 这种 Playwright 语义定位器字符串。
3. 它有一个 `cursor` 模式（`-C` flag），会额外用 `page.evaluate()` 在浏览器里跑 JS 找 `cursor:pointer` / `onclick` / `tabindex` 的非标准交互元素。
4. compact 模式的实现：遍历行，保留有 `[ref=]` 的行、有文本内容的行、以及有 ref 子孙的结构行。

这些发现验证了你 PRD 的核心判断：我们在 WebView 里没有 `ariaSnapshot()` API，所以必须自己写 JS 遍历 DOM 来生成等价输出。

下面是合并后的完整 PRD v3 final：

---

# PRD-0002 v3 (Final)：agent-browser-kotlin

WebView JS 注入实现 Snapshot + Ref 交互，完整网页自动化闭环

## 1. 背景与问题

我们在做一个 Android 聊天型 Agent App（供应商为 OpenAI Responses API 风格，流式调用）。Agent 具备 Web 能力（WebView / WebFetch / WebSearch 等），会把网页内容作为工具结果返回给模型。

当前严重问题：工具把 `document.body.outerHTML`（或整页 DOM/HTML）直接返回给模型，导致一次 `tool.result` 就可能几十万字符，下一轮模型请求把该内容带上后触发 `context_length_exceeded`，即使对话轮次不多也会爆。

证据：真机 dump 的 session 中，曾出现单条 `tool.result` 约 590KB 的返回内容。

### 核心思路

借鉴 `vercel-labs/agent-browser` 的 snapshot + refs 理念，但适配 Android WebView 环境：

- agent-browser 依赖 Playwright 的 `locator.ariaSnapshot()` 获取浏览器运行时的 Accessibility Tree，然后在文本层面做正则解析、加 ref、过滤 —— 我们没有这个 API。
- agent-browser 的 RefMap 存的是 `{ selector: "getByRole('button', { name: \"Submit\", exact: true })", role, name, nth }` 这种 Playwright 语义定位器 —— 我们没有 Playwright 定位器引擎。
- 替代方案：在 WebView 中注入自研 JS 脚本，遍历真实 DOM，提取语义/ARIA/可见性信息，生成结构化 JSON（等价于 ariaSnapshot 的输出）。通过 `data-agent-ref` 属性标记元素，实现 O(1) 定位。
- Kotlin 侧接收 JSON，负责预算控制、格式化为模型可读的 snapshot 文本、管理 ref 映射。
- 同时实现 action 能力（click/fill/select 等），补全 `snapshot → 模型决策 → action → re-snapshot` 的完整闭环。

参考项目：`https://github.com/vercel-labs/agent-browser`（重点看 `src/snapshot.ts` 的 ref 标注风格、INTERACTIVE/CONTENT/STRUCTURAL 三类角色分类、compact 过滤策略、cursor-interactive 检测）。

### 与 agent-browser 的关键差异

| 维度 | agent-browser | 本库（agent-browser-kotlin） |
|---|---|---|
| ARIA Tree 来源 | Playwright `locator.ariaSnapshot()` | 自研 JS 遍历真实 DOM |
| Ref 定位方式 | `getByRole(role, { name, exact }).nth(n)` | `data-agent-ref` 属性 + `querySelector` |
| 可见性判断 | Playwright 内置（基于渲染引擎） | JS `getComputedStyle` + `offsetParent` |
| 动态内容 | Playwright 看到 JS 渲染后的 DOM | WebView 同样看到 JS 渲染后的 DOM |
| Action 执行 | Playwright locator API（click/fill 等） | JS 模拟事件序列（dispatchEvent） |
| 运行环境 | Node.js + Playwright | Android WebView + Kotlin |
| cursor 模式 | `page.evaluate()` 找 cursor:pointer 元素 | JS 侧统一处理，无需额外调用 |

## 2. 目标（Goals）

1. WebView 侧（JS）：遍历真实 DOM，输出结构化 JSON，包含语义角色、文本、属性、可见性、ref 标识；同时支持通过 ref 执行操作和查询。
2. Kotlin 侧：接收 JSON，执行预算控制（字符数/节点数/深度），格式化为模型可读的 snapshot 文本（对齐 agent-browser 的 ARIA snapshot 输出风格）。
3. Snapshot 重点保留"可读/可操作"信息：文本、链接、按钮、输入框、表单元素、标题层级、列表项等；可配置 `interactiveOnly`。
4. 支持 cursor-interactive 检测：找到 `cursor:pointer` / `onclick` / `tabindex` 的非标准交互元素（对齐 agent-browser 的 `-C` flag）。
5. 支持按 ref 做后续精准操作（click/fill/select/check 等）和查询（text/html/value/attrs）。
6. 预算约束：任何输出都必须在预算内，并显式标记 `truncated` 及原因。
7. 完整闭环：snapshot（看）→ 模型决策 → action（操作）→ re-snapshot → ...
8. 纯 Kotlin 库 + JS 脚本：不依赖 Playwright，不依赖 Android 特定 API。

## 3. 非目标（Non-goals）

- 不做完整浏览器自动化框架（不管理浏览器生命周期、Tab、Cookie 等）。
- 不追求 100% ARIA 规范覆盖；优先保证鲁棒、预算可控、对常见页面有效。
- 不做网络抓取（fetch）。
- 不处理跨 iframe 场景（初期）。
- 不做拖拽、长按、双击等高级交互（初期）。

## 4. 架构分层

```
┌─────────────────────────────────────────────────┐
│  Agent / Tool Layer (Kotlin)                    │
│  - 定义 tools: web_snapshot, web_click,         │
│    web_fill, web_select, web_scroll, web_get... │
│  - 把 snapshot 结果作为 tool.result 返回模型     │
│  - 模型返回 tool_call(ref=e5, action=click)     │
├─────────────────────────────────────────────────┤
│  agent-browser-kotlin (本库，Kotlin)             │
│  - 管理 JS 脚本生成与结果解析                    │
│  - snapshot: JSON → 预算控制 → 格式化文本        │
│  - action: 生成 JS 调用代码 → 解析执行结果       │
│  - ref 映射缓存与校验                            │
├─────────────────────────────────────────────────┤
│  agent-browser.js (本库提供，注入 WebView)       │
│  - snapshot(): 遍历 DOM → 结构化 JSON            │
│  - action(ref, action, params): 定位 → 操作      │
│  - query(ref, kind, limit): 定位 → 查询          │
│  - page.*: 页面级操作（scroll/pressKey/getUrl）  │
├─────────────────────────────────────────────────┤
│  Android WebView                                │
│  - evaluateJavascript() 注入/调用               │
└─────────────────────────────────────────────────┘
```

## 5. JS 侧设计（agent-browser.js）

### 5.1 全局入口

```javascript
window.__agentBrowser = {
  snapshot: function(options) { ... },
  action:   function(ref, action, params) { ... },
  query:    function(ref, kind, limit) { ... },
  page: {
    scrollBy:  function(x, y) { ... },
    scrollTo:  function(x, y) { ... },
    getUrl:    function() { ... },
    getTitle:  function() { ... },
    pressKey:  function(key) { ... },
  },
};
```

### 5.2 Snapshot

#### 5.2.1 节点信息提取

对每个 DOM 元素，提取：

| 字段 | 来源 | 说明 |
|---|---|---|
| `tag` | `element.tagName` | 小写 |
| `role` | 显式 `role` 属性 > tag→role 隐式映射 | 见 5.2.2 |
| `name` | `aria-label` > `aria-labelledby` 引用文本 > `alt` > `title` > 可见文本（截断） | 简化版可访问名称计算 |
| `text` | 直接文本子节点，normalize whitespace | 截断至 `maxTextPerNode` |
| `attrs` | 白名单：`href, name, type, value, placeholder, src, action, method` | 每个 value 限长 |
| `visible` | `offsetParent` + `getComputedStyle` | 不可见节点跳过 |
| `interactive` | 是否为可交互元素 | 布尔 |
| `cursorInteractive` | `cursor:pointer` / `onclick` / `tabindex` 的非标准交互元素 | 布尔，对齐 agent-browser `-C` flag |
| `ref` | `e1, e2, ...` 递增分配 | 仅对候选节点分配 |
| `level` | heading level（h1-h6） | 仅 heading 节点 |
| `children` | 递归子节点数组 | — |

#### 5.2.2 Tag → Role 隐式映射

```javascript
const IMPLICIT_ROLES = {
  'a[href]':    'link',
  'button':     'button',
  'input[type=text]':    'textbox',
  'input[type=search]':  'searchbox',
  'input[type=email]':   'textbox',
  'input[type=password]':'textbox',
  'input[type=number]':  'spinbutton',
  'input[type=checkbox]':'checkbox',
  'input[type=radio]':   'radio',
  'input[type=submit]':  'button',
  'input[type=reset]':   'button',
  'input[type=range]':   'slider',
  'select':     'combobox',
  'textarea':   'textbox',
  'h1':         'heading',  'h2': 'heading',  'h3': 'heading',
  'h4':         'heading',  'h5': 'heading',  'h6': 'heading',
  'ul':         'list',     'ol': 'list',
  'li':         'listitem',
  'nav':        'navigation',
  'main':       'main',
  'header':     'banner',
  'footer':     'contentinfo',
  'form':       'form',
  'table':      'table',
  'tr':         'row',
  'td':         'cell',
  'th':         'columnheader',
  'img':        'img',
  'article':    'article',
  'section':    'region',
  'aside':      'complementary',
  'dialog':     'dialog',
  'details':    'group',
  'summary':    'button',
  'option':     'option',
  'progress':   'progressbar',
  'meter':      'meter',
};
```

显式 `role` 属性优先。

#### 5.2.3 角色分类（对齐 agent-browser `snapshot.ts`）

```javascript
// 对齐 agent-browser 的 INTERACTIVE_ROLES
const INTERACTIVE_ROLES = new Set([
  'button', 'link', 'textbox', 'checkbox', 'radio',
  'combobox', 'listbox', 'menuitem', 'menuitemcheckbox',
  'menuitemradio', 'option', 'searchbox', 'slider',
  'spinbutton', 'switch', 'tab', 'treeitem',
]);

// 对齐 agent-browser 的 CONTENT_ROLES
const CONTENT_ROLES = new Set([
  'heading', 'cell', 'gridcell', 'columnheader', 'rowheader',
  'listitem', 'article', 'region', 'main', 'navigation',
  'img', 'progressbar', 'meter',
]);

// 对齐 agent-browser 的 STRUCTURAL_ROLES
const STRUCTURAL_ROLES = new Set([
  'generic', 'group', 'list', 'table', 'row', 'rowgroup',
  'grid', 'treegrid', 'menu', 'menubar', 'toolbar',
  'tablist', 'tree', 'directory', 'document', 'application',
  'presentation', 'none', 'form', 'banner', 'contentinfo',
  'complementary', 'dialog',
]);
```

Ref 分配规则：
- INTERACTIVE 节点：始终分配 ref
- CONTENT 节点：`interactiveOnly=false` 时分配 ref；`interactiveOnly=true` 时，有 name 的也分配（对齐 agent-browser 行为）
- STRUCTURAL 节点：自身不分配 ref，但作为树结构保留（如果有 ref 子孙）
- cursor-interactive 节点（`cursor:pointer` / `onclick` / `tabindex`）：`cursorInteractive=true` 时分配 ref，标记为 `clickable` 或 `focusable` 角色

#### 5.2.4 可见性判断

```javascript
function isVisible(el) {
  if (el.offsetParent === null && getComputedStyle(el).position !== 'fixed') return false;
  const style = getComputedStyle(el);
  if (style.display === 'none') return false;
  if (style.visibility === 'hidden') return false;
  if (parseFloat(style.opacity) === 0) return false;
  if (el.getAttribute('aria-hidden') === 'true') return false;
  return true;
}
```

不可见节点及其子树直接跳过。这是压缩率的重要来源。

#### 5.2.5 Cursor-Interactive 检测（对齐 agent-browser `-C` flag）

agent-browser 在 `findCursorInteractiveElements` 中用 `page.evaluate()` 找非标准交互元素。我们在 JS 侧统一处理：

```javascript
function isCursorInteractive(el) {
  // 跳过已有 ARIA 交互角色的元素
  if (INTERACTIVE_ROLES.has(getRole(el))) return false;
  // 跳过原生交互标签
  const interactiveTags = new Set(['a','button','input','select','textarea','details','summary']);
  if (interactiveTags.has(el.tagName.toLowerCase())) return false;

  const style = getComputedStyle(el);
  const hasCursorPointer = style.cursor === 'pointer';
  const hasOnClick = el.hasAttribute('onclick') || el.onclick !== null;
  const tabIndex = el.getAttribute('tabindex');
  const hasTabIndex = tabIndex !== null && tabIndex !== '-1';

  return hasCursorPointer || hasOnClick || hasTabIndex;
}
```

#### 5.2.6 JS 侧预算（粗粒度）

JS 侧做粗粒度的节点数上限（`maxNodes=500`），防止超大 DOM 导致 JS 执行时间过长。精细的字符预算由 Kotlin 侧控制。

#### 5.2.7 DOM 标记

snapshot 遍历时，对每个分配了 ref 的元素设置 `data-agent-ref` 属性：

```javascript
element.setAttribute('data-agent-ref', ref);
```

每次 snapshot 开始时，清除旧的标记：

```javascript
function clearOldRefs() {
  document.querySelectorAll('[data-agent-ref]').forEach(el => {
    el.removeAttribute('data-agent-ref');
  });
}
```

#### 5.2.8 输出 JSON 格式

```json
{
  "version": 1,
  "url": "https://example.com/page",
  "title": "页面标题",
  "timestamp": 1739628047000,
  "tree": {
    "tag": "body",
    "role": null,
    "children": [
      {
        "tag": "nav",
        "role": "navigation",
        "children": [
          {
            "ref": "e1",
            "tag": "a",
            "role": "link",
            "name": "首页",
            "attrs": { "href": "/" },
            "text": "首页"
          },
          {
            "ref": "e2",
            "tag": "a",
            "role": "link",
            "name": "价格",
            "attrs": { "href": "/pricing" },
            "text": "价格"
          }
        ]
      },
      {
        "tag": "main",
        "role": "main",
        "children": [
          {
            "ref": "e3",
            "tag": "h1",
            "role": "heading",
            "text": "今日金价",
            "level": 1
          },
          {
            "ref": "e4",
            "tag": "input",
            "role": "searchbox",
            "attrs": { "type": "search", "placeholder": "搜索..." }
          },
          {
            "ref": "e5",
            "tag": "button",
            "role": "button",
            "name": "搜索",
            "text": "搜索"
          }
        ]
      }
    ]
  },
  "stats": {
    "domNodes": 3200,
    "visitedNodes": 800,
    "emittedNodes": 45,
    "skippedHidden": 2400,
    "jsTimeMs": 12
  }
}
```

### 5.3 Action

```javascript
function actionByRef(ref, action, params) {
  const el = document.querySelector(`[data-agent-ref="${ref}"]`);
  if (!el) return { success: false, error: 'ref_not_found', ref };

  try {
    switch (action) {
      case 'click':       return doClick(el, params);
      case 'fill':        return doFill(el, params);
      case 'check':       return doCheck(el, params);
      case 'uncheck':     return doUncheck(el, params);
      case 'select':      return doSelect(el, params);
      case 'focus':       return doFocus(el);
      case 'hover':       return doHover(el);
      case 'scroll_into_view': return doScrollIntoView(el);
      case 'clear':       return doClear(el);
      default:            return { success: false, error: 'unknown_action', action };
    }
  } catch (e) {
    return { success: false, error: e.message, ref, action };
  }
}
```

#### 5.3.1 各操作实现

```javascript
function doClick(el, params) {
  el.scrollIntoView({ block: 'center', behavior: 'instant' });
  const rect = el.getBoundingClientRect();
  const x = rect.left + rect.width / 2;
  const y = rect.top + rect.height / 2;
  // 模拟真实点击事件序列
  for (const type of ['pointerdown','mousedown','pointerup','mouseup','click']) {
    const Cls = type.startsWith('pointer') ? PointerEvent : MouseEvent;
    el.dispatchEvent(new Cls(type, {
      bubbles: true, cancelable: true, view: window,
      clientX: x, clientY: y, button: 0,
    }));
  }
  return { success: true, action: 'click', ref: el.getAttribute('data-agent-ref') };
}

function doFill(el, params) {
  el.scrollIntoView({ block: 'center', behavior: 'instant' });
  el.focus();
  // 使用 native setter 绕过 React/Vue 的 value 拦截
  const setter = Object.getOwnPropertyDescriptor(
    window.HTMLInputElement.prototype, 'value'
  )?.set || Object.getOwnPropertyDescriptor(
    window.HTMLTextAreaElement.prototype, 'value'
  )?.set;
  if (setter) setter.call(el, params.value);
  else el.value = params.value;
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
  return { success: true, action: 'fill', value: params.value };
}

function doSelect(el, params) {
  if (el.tagName !== 'SELECT') return { success: false, error: 'not_a_select_element' };
  const values = Array.isArray(params.values) ? params.values : [params.values];
  for (const opt of el.options) {
    opt.selected = values.includes(opt.value) || values.includes(opt.textContent.trim());
  }
  el.dispatchEvent(new Event('change', { bubbles: true }));
  return { success: true, action: 'select', values };
}

function doCheck(el) {
  if (el.type === 'checkbox' || el.type === 'radio') {
    if (!el.checked) el.click();
    return { success: true, action: 'check', checked: el.checked };
  }
  return { success: false, error: 'not_checkable' };
}

function doUncheck(el) {
  if (el.type === 'checkbox') {
    if (el.checked) el.click();
    return { success: true, action: 'uncheck', checked: el.checked };
  }
  return { success: false, error: 'not_uncheckable' };
}

function doFocus(el) {
  el.focus();
  return { success: true, action: 'focus' };
}

function doHover(el) {
  el.scrollIntoView({ block: 'center', behavior: 'instant' });
  el.dispatchEvent(new MouseEvent('mouseenter', { bubbles: true }));
  el.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }));
  return { success: true, action: 'hover' };
}

function doScrollIntoView(el) {
  el.scrollIntoView({ block: 'center', behavior: 'smooth' });
  return { success: true, action: 'scroll_into_view' };
}

function doClear(el) {
  el.focus();
  el.value = '';
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
  return { success: true, action: 'clear' };
}
```

#### 5.3.2 为什么用 `data-agent-ref` 而不是 role+name 定位

agent-browser 用 `page.getByRole(role, { name, exact: true }).nth(nth)` 定位，这依赖 Playwright 的语义定位器引擎。我们在 WebView 里没有这个。

| 方案 | 优点 | 缺点 |
|---|---|---|
| `data-agent-ref` 属性 | 简单、O(1) 查找、不依赖任何库 | 页面 DOM 变化后 ref 失效 |
| CSS selector 路径 | 不修改 DOM | 脆弱，DOM 变化易断 |
| XPath | 不修改 DOM | 同上，且性能差 |
| role+name+nth | 语义稳定 | 需要自己实现 ARIA 匹配引擎，复杂 |

选择 `data-agent-ref`，因为在 snapshot → action 的短周期内（通常几秒），DOM 不太会变。如果 DOM 变了（导航、AJAX），上层工具应该重新 snapshot。

### 5.4 Query

```javascript
function queryByRef(ref, kind, limit) {
  const el = document.querySelector(`[data-agent-ref="${ref}"]`);
  if (!el) return { ref, error: 'not_found' };
  let value;
  switch (kind) {
    case 'text':   value = el.innerText; break;
    case 'html':   value = el.innerHTML; break;
    case 'value':  value = el.value ?? ''; break;
    case 'attrs':  value = JSON.stringify(
      Object.fromEntries(Array.from(el.attributes).map(a => [a.name, a.value]))
    ); break;
    case 'computed_styles':
      const s = getComputedStyle(el);
      value = JSON.stringify({
        display: s.display, color: s.color, fontSize: s.fontSize,
        backgroundColor: s.backgroundColor, visibility: s.visibility,
      }); break;
  }
  const truncated = value && value.length > limit;
  return {
    ref, kind,
    value: truncated ? value.slice(0, limit) + '...[truncated]' : value,
    truncated: !!truncated,
  };
}
```

### 5.5 页面级操作

```javascript
window.__agentBrowser.page = {
  scrollBy(x, y) {
    window.scrollBy(x, y);
    return { success: true, scrollX: window.scrollX, scrollY: window.scrollY };
  },
  scrollTo(x, y) {
    window.scrollTo(x, y);
    return { success: true };
  },
  getUrl()   { return location.href; },
  getTitle() { return document.title; },
  pressKey(key) {
    document.activeElement?.dispatchEvent(
      new KeyboardEvent('keydown', { key, bubbles: true })
    );
    document.activeElement?.dispatchEvent(
      new KeyboardEvent('keyup', { key, bubbles: true })
    );
    return { success: true, key };
  },
};
```

### 5.6 JSBridge 通信

```javascript
// 方式一：通过 evaluateJavascript 回调直接拿返回值
const result = window.__agentBrowser.snapshot(options);
return JSON.stringify(result);

// 方式二：通过 Android JSBridge 回传
window.AgentBridge.onSnapshot(JSON.stringify(result));
```

具体桥接方式由上层 WebView 工具层决定，本库只负责提供 JS 脚本内容和 Kotlin 侧解析。

## 6. Kotlin 侧设计

### 6.1 数据结构

```kotlin
// ---- JS 侧 Snapshot 配置 ----
data class JsSnapshotOptions(
    val maxNodes: Int = 500,
    val maxTextPerNode: Int = 200,
    val maxAttrValueLen: Int = 150,
    val interactiveOnly: Boolean = true,
    val cursorInteractive: Boolean = false,
    val scope: String? = null,  // CSS selector
)

// ---- Kotlin 侧 Render 配置 ----
data class RenderOptions(
    val maxCharsTotal: Int = 12_000,
    val maxNodes: Int = 200,
    val maxDepth: Int = 12,
    val compact: Boolean = true,
    val format: OutputFormat = OutputFormat.PLAIN_TEXT_TREE,
)

enum class OutputFormat {
    PLAIN_TEXT_TREE,   // 给模型看的缩进文本（对齐 agent-browser 输出风格）
    JSON,              // 给程序用的结构化 JSON
}

// ---- JS 侧 JSON 反序列化 ----
@Serializable
data class RawSnapshotNode(
    val tag: String,
    val role: String? = null,
    val ref: String? = null,
    val name: String? = null,
    val text: String? = null,
    val level: Int? = null,
    val attrs: Map<String, String> = emptyMap(),
    val cursorInteractive: Boolean = false,
    val children: List<RawSnapshotNode> = emptyList(),
)

@Serializable
data class RawSnapshot(
    val version: Int,
    val url: String?,
    val title: String?,
    val timestamp: Long,
    val tree: RawSnapshotNode,
    val stats: RawSnapshotStats,
)

@Serializable
data class RawSnapshotStats(
    val domNodes: Int,
    val visitedNodes: Int,
    val emittedNodes: Int,
    val skippedHidden: Int,
    val jsTimeMs: Int,
)

// ---- Kotlin 侧输出 ----
data class SnapshotResult(
    val text: String,              // 给模型的文本
柠檬叔，接着来。上面写到 `SnapshotResult` 的 `text` 字段，继续往下：

---

```kotlin
    val refs: Map<String, NodeRef>,    // ref -> 节点信息
    val stats: SnapshotStats,
)

data class NodeRef(
    val ref: String,
    val tag: String,
    val role: String?,
    val name: String?,
    val attrs: Map<String, String>,
    val textSnippet: String?,
)

data class SnapshotStats(
    val jsStats: RawSnapshotStats,
    val nodesEmitted: Int,
    val charsEmitted: Int,
    val truncated: Boolean,
    val truncateReasons: List<String>,
)

// ---- Action ----
@Serializable
data class ActionResult(
    val success: Boolean,
    val action: String? = null,
    val error: String? = null,
    val ref: String? = null,
    val value: String? = null,
    val checked: Boolean? = null,
    val values: List<String>? = null,
)

// ---- Query ----
enum class QueryKind { TEXT, HTML, VALUE, ATTRS, COMPUTED_STYLES }

@Serializable
data class QueryResult(
    val ref: String,
    val kind: String,
    val value: String? = null,
    val truncated: Boolean = false,
    val error: String? = null,
)
```

### 6.2 核心 API

```kotlin
object AgentBrowser {

    // ---- JS 脚本管理 ----

    /** 获取需要注入 WebView 的完整 JS 脚本（包含 snapshot + action + query + page） */
    fun getScript(): String

    // ---- Snapshot ----

    /** 生成 snapshot 调用的 JS 表达式 */
    fun snapshotJs(options: JsSnapshotOptions = JsSnapshotOptions()): String
    // 返回: "JSON.stringify(window.__agentBrowser.snapshot({maxNodes:500,...}))"

    /** 解析 snapshot JSON + 预算控制 + 格式化 → 模型可读文本 */
    fun renderSnapshot(json: String, options: RenderOptions = RenderOptions()): SnapshotResult

    // ---- Action ----

    /** 生成 action 调用的 JS 表达式 */
    fun actionJs(ref: String, action: String, params: Map<String, Any?> = emptyMap()): String
    // 返回: "JSON.stringify(window.__agentBrowser.action('e5','click',{}))"

    /** 解析 action 结果 JSON */
    fun parseActionResult(json: String): ActionResult

    // ---- Query ----

    /** 生成 query 调用的 JS 表达式 */
    fun queryJs(ref: String, kind: QueryKind, limit: Int = 2000): String

    /** 解析 query 结果 JSON */
    fun parseQueryResult(json: String): QueryResult

    // ---- Page Actions ----

    fun scrollJs(direction: String, amount: Int = 300): String
    fun pressKeyJs(key: String): String
    fun getUrlJs(): String
    fun getTitleJs(): String
}
```

### 6.3 Render 算法（预算控制）

对齐 agent-browser `snapshot.ts` 中的 compact 逻辑：遍历行，保留有 `[ref=]` 的行、有文本内容的行、以及有 ref 子孙的结构行。

```
输入：RawSnapshotNode 树 + RenderOptions
输出：snapshotText（字符串）+ refs（Map）

charBudget = options.maxCharsTotal
nodeCount = 0
lines = []
refs = {}

// 预处理：标记每个节点是否有 ref 子孙（用于 compact 剪枝）
fun markHasRefDescendant(node): Boolean
    if node.ref != null: return true
    return node.children.any { markHasRefDescendant(it) }

fun visit(node, depth):
    if depth > maxDepth:
        mark truncated("maxDepth"); return
    if charBudget <= 0:
        mark truncated("maxCharsTotal"); return
    if nodeCount >= maxNodes:
        mark truncated("maxNodes"); return

    role = node.role
    hasRef = node.ref != null

    // compact 模式：结构节点没有 ref 子孙 → 跳过整个子树
    if compact && isStructural(role) && !hasRefDescendant(node): return

    // 生成行
    if hasRef:
        line = formatRefLine(node, depth)
        // 如: "  - button "搜索" [ref=e5]"
        // 如: "  - searchbox [placeholder="搜索..."] [ref=e4]"
        // 如: "  - heading "今日金价" [level=1] [ref=e3]"
        if charBudget - line.length < 0:
            mark truncated("maxCharsTotal"); return
        lines.add(line)
        charBudget -= line.length
        nodeCount++
        refs[node.ref] = toNodeRef(node)
    else if isStructural(role) || isContent(role):
        // 结构/内容节点不占 ref，但输出缩进标记
        structLine = indent(depth) + "- ${role}:"
        if node.name != null: structLine += " \"${node.name}\""
        lines.add(structLine)
        charBudget -= structLine.length

    for child in node.children:
        visit(child, depth + 1)

// 执行
visit(tree, 0)

// 组装 header
header = "[snapshot] url=${url} title=\"${title}\" nodes=${nodeCount} truncated=${truncated}"
snapshotText = header + "\n" + lines.joinToString("\n")
```

### 6.4 输出格式（PLAIN_TEXT_TREE）

对齐 agent-browser 的 ARIA snapshot 风格，利用模型已有训练数据：

```
[snapshot] url=https://example.com title="今日金价" nodes=45 truncated=false
- navigation:
  - link "首页" [ref=e1]
  - link "价格" [ref=e2]
- main:
  - heading "今日金价" [level=1] [ref=e3]
  - searchbox [placeholder="搜索..."] [ref=e4]
  - button "搜索" [ref=e5]
  - list:
    - listitem "黄金 ¥580.00/g" [ref=e6]
    - listitem "白银 ¥7.20/g" [ref=e7]
    - ... (truncated, 15 more items)
```

## 7. Ref 生命周期与失效处理

1. Ref 在 snapshot 时分配，通过 `data-agent-ref` 属性标记在 DOM 上。
2. 每次 snapshot 会清除旧的 `data-agent-ref`，重新分配（ref 编号从 e1 重新开始）。
3. 如果 action/query 时 ref 找不到（DOM 变了），返回 `{ success: false, error: "ref_not_found" }`。
4. 上层工具收到 `ref_not_found` 时，应自动触发重新 snapshot，并告知模型"页面已变化，请重新查看"。

## 8. 上层工具定义（给模型看的 tool schema）

```kotlin
val WEB_TOOLS = listOf(
    Tool(
        name = "web_snapshot",
        description = "获取当前页面的元素快照。返回带 [ref=eN] 标注的元素树。后续操作使用 ref 指定目标。",
        parameters = mapOf(
            "interactive_only" to Param(type = "boolean", default = true,
                description = "true=只返回可交互元素，false=同时返回标题/列表项/图片等内容元素"),
        )
    ),
    Tool(
        name = "web_click",
        description = "点击指定 ref 的元素。",
        parameters = mapOf("ref" to Param(type = "string", required = true))
    ),
    Tool(
        name = "web_fill",
        description = "在指定 ref 的输入框中填入文本（会先清空）。",
        parameters = mapOf(
            "ref" to Param(type = "string", required = true),
            "value" to Param(type = "string", required = true),
        )
    ),
    Tool(
        name = "web_select",
        description = "在指定 ref 的下拉框中选择选项（按 value 或显示文本匹配）。",
        parameters = mapOf(
            "ref" to Param(type = "string", required = true),
            "values" to Param(type = "array", items = "string", required = true),
        )
    ),
    Tool(
        name = "web_check",
        description = "勾选指定 ref 的 checkbox 或 radio。",
        parameters = mapOf("ref" to Param(type = "string", required = true))
    ),
    Tool(
        name = "web_uncheck",
        description = "取消勾选指定 ref 的 checkbox。",
        parameters = mapOf("ref" to Param(type = "string", required = true))
    ),
    Tool(
        name = "web_scroll",
        description = "页面滚动。direction: up/down/left/right",
        parameters = mapOf(
            "direction" to Param(type = "string", required = true),
            "amount" to Param(type = "integer", default = 300),
        )
    ),
    Tool(
        name = "web_press_key",
        description = "在当前焦点元素上按键。如 Enter, Tab, Escape, Backspace, ArrowDown",
        parameters = mapOf("key" to Param(type = "string", required = true))
    ),
    Tool(
        name = "web_get_text",
        description = "获取指定 ref 元素的文本内容（用于读取详情）。",
        parameters = mapOf(
            "ref" to Param(type = "string", required = true),
            "max_length" to Param(type = "integer", default = 2000),
        )
    ),
    Tool(
        name = "web_get_value",
        description = "获取指定 ref 输入框的当前值。",
        parameters = mapOf("ref" to Param(type = "string", required = true))
    ),
)
```

## 9. 完整交互流程示例

```
用户: "帮我在淘宝搜索 iPhone 16"

Agent 思考: 需要先看看页面上有什么

[Tool Call] web_snapshot(interactive_only=true)
→ Kotlin: webView.evaluateJavascript(AgentBrowser.getScript(), null)  // 首次注入
→ Kotlin: webView.evaluateJavascript(AgentBrowser.snapshotJs(), callback)
→ JS: window.__agentBrowser.snapshot({...})
→ JSON 回传 → Kotlin: renderSnapshot(json)
→ Tool Result:
  [snapshot] url=https://www.taobao.com title="淘宝" nodes=38 truncated=false
  - navigation:
    - link "首页" [ref=e1]
    - link "天猫" [ref=e2]
  - main:
    - searchbox [placeholder="搜索"] [ref=e3]
    - button "搜索" [ref=e4]
    - ...

Agent 思考: 看到搜索框 e3 和搜索按钮 e4

[Tool Call] web_fill(ref="e3", value="iPhone 16")
→ Kotlin: webView.evaluateJavascript(AgentBrowser.actionJs("e3", "fill", mapOf("value" to "iPhone 16")), callback)
→ JS: window.__agentBrowser.action('e3', 'fill', {value: 'iPhone 16'})
→ Result: { success: true, action: "fill", value: "iPhone 16" }

[Tool Call] web_click(ref="e4")
→ Kotlin: webView.evaluateJavascript(AgentBrowser.actionJs("e4", "click"), callback)
→ JS: window.__agentBrowser.action('e4', 'click', {})
→ Result: { success: true, action: "click" }

Agent 思考: 点击了搜索，页面应该变了，重新 snapshot

[Tool Call] web_snapshot(interactive_only=false)
→ ... 新页面的 snapshot，包含搜索结果列表 ...
→ Tool Result:
  [snapshot] url=https://s.taobao.com/search?q=iPhone+16 title="iPhone 16 - 搜索结果" nodes=120 truncated=true truncateReasons=["maxNodes"]
  - main:
    - heading "搜索结果" [level=1] [ref=e1]
    - list:
      - listitem [ref=e2]:
        - link "Apple iPhone 16 128GB 黑色" [ref=e3]
        - text "¥5,999"
      - listitem [ref=e4]:
        - link "Apple iPhone 16 Pro Max 256GB" [ref=e5]
        - text "¥9,999"
      - ... (truncated, 38 more items)

Agent: 搜索结果已出来，排在前面的有：
1. Apple iPhone 16 128GB 黑色 - ¥5,999
2. Apple iPhone 16 Pro Max 256GB - ¥9,999
...
```

## 10. 推荐默认配置

### JS 侧

```javascript
{
  maxNodes: 500,
  maxTextPerNode: 200,
  maxAttrValueLen: 150,
  interactiveOnly: true,
  cursorInteractive: false,
  scope: null,  // document.body
}
```

### Kotlin 侧

```kotlin
RenderOptions(
    maxCharsTotal = 12_000,
    maxNodes = 200,
    maxDepth = 12,
    compact = true,
    format = OutputFormat.PLAIN_TEXT_TREE,
)
```

## 11. 交付物

### 11.1 agent-browser.js
- 单文件，无外部依赖，可直接注入 WebView
- 压缩后 < 15KB
- 包含 snapshot + action + query + page 四组函数
- 通过 `window.__agentBrowser` 命名空间暴露

### 11.2 agent-browser-kotlin（Kotlin 库）
- Gradle 工程，可发布为 maven artifact
- 依赖：`kotlinx-serialization-json`（解析 JS 回传的 JSON）
- 无 Android 依赖（纯 Kotlin/JVM，Android 项目可直接引用）
- 内嵌 `agent-browser.js` 为资源文件
- 核心 API：`AgentBrowser` object

### 11.3 单元测试
- Snapshot：大 DOM 预算控制、interactiveOnly、compact、cursorInteractive
- Render：字符预算截断、节点数截断、深度截断、truncateReasons 正确
- Action：各操作的 JS 生成正确性、ActionResult 解析
- Query：各 kind 的 JS 生成正确性、QueryResult 解析
- Ref 生命周期：重新 snapshot 后旧 ref 失效
- 边界：空 DOM、纯文本页面、深度嵌套、ref_not_found

### 11.4 README
- 架构图、完整交互流程示例
- JS 注入方式说明
- 推荐 tool schema
- 最佳实践：snapshot → action → re-snapshot 循环
- 禁止 outerHTML 直喂模型的警告

## 12. 验收标准

1. 完整闭环：snapshot → 模型看到 ref → action(ref) → 操作成功 → re-snapshot 看到变化。
2. 对真实大页面（新闻首页、电商商品页），snapshot.js 执行时间 < 100ms，输出 JSON < 100KB。
3. Kotlin render 后 `text.length <= maxCharsTotal`，不会 OOM。
4. click 能触发真实页面行为（链接跳转、按钮提交等）。
5. fill 能正确填入值并触发 React/Vue 等框架的响应式更新。
6. select 能正确选择下拉选项。
7. 输出包含可交互元素 refs（link/button/input）且包含必要 attrs。
8. `interactiveOnly=false` 时额外包含 heading/listitem/img 等内容节点。
9. compact 模式有效压缩：移除无 ref 子孙的空结构分支。
10. 不可见元素（`display:none`、`aria-hidden="true"` 等）被正确跳过。
11. ref_not_found 时返回明确错误，不崩溃。
12. Kotlin 库无 Android 依赖，可在纯 JVM 测试中运行。

## 13. 后续迭代

- P1：iframe 支持（`switchToFrame` / `switchToMainFrame`）
- P1：文件上传（`input[type=file]`）
- P1：WebFetch 场景的 Jsoup fallback（纯 HTML，无 WebView）
- P2：拖拽（drag and drop）
- P2：长按 / 双击
- P2：等待条件（`waitForSelector` / `waitForText`）
- P2：截图能力（WebView `capturePicture` → 压缩 → base64）

---

合并完成。v2 的 snapshot 细节（节点提取、可见性判断、JSON 格式、预算算法）和 v3 的 action 闭环（click/fill/select/check + ref 生命周期 + 完整交互流程）都整合进来了，同时补充了从 agent-browser 源码中确认的 cursor-interactive 检测、角色分类对齐、compact 剪枝逻辑。