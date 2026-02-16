package com.lsl.kotlin_agent_app.agent

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.events.Event
import me.lemonhall.openagentic.sdk.events.Result
import me.lemonhall.openagentic.sdk.events.SystemInit
import me.lemonhall.openagentic.sdk.hooks.HookDecision
import me.lemonhall.openagentic.sdk.hooks.HookEngine
import me.lemonhall.openagentic.sdk.hooks.HookMatcher
import me.lemonhall.openagentic.sdk.providers.OpenAIResponsesHttpProvider
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticOptions
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticSdk
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import me.lemonhall.openagentic.sdk.tools.EditTool
import me.lemonhall.openagentic.sdk.tools.GlobTool
import me.lemonhall.openagentic.sdk.tools.GrepTool
import me.lemonhall.openagentic.sdk.tools.ListTool
import me.lemonhall.openagentic.sdk.tools.ReadTool
import me.lemonhall.openagentic.sdk.tools.SkillTool
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import me.lemonhall.openagentic.sdk.tools.WebFetchTool
import me.lemonhall.openagentic.sdk.tools.WebSearchTool
import me.lemonhall.openagentic.sdk.tools.WriteTool
import com.lsl.kotlin_agent_app.agent.tools.web.OpenAgenticWebTools
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.io.File
import java.util.Locale

class OpenAgenticSdkChatAgent(
    context: Context,
    private val prefs: SharedPreferences,
    private val configRepository: com.lsl.kotlin_agent_app.config.SharedPreferencesLlmConfigRepository,
) : ChatAgent {
    private val appContext = context.applicationContext
    private val workspace = AgentsWorkspace(appContext)

    override fun streamReply(prompt: String): Flow<Event> {
        val text = prompt.trim()
        require(text.isNotEmpty()) { "prompt is empty" }

        workspace.ensureInitialized()

        val config = configRepository.get()
        val baseUrl = config.baseUrl.trim()
        val apiKey = config.apiKey.trim()
        val model = config.model.trim()

        require(baseUrl.isNotEmpty()) { "base_url 未配置" }
        require(apiKey.isNotEmpty()) { "api_key 未配置" }
        require(model.isNotEmpty()) { "model 未配置" }

        val agentsRoot = File(appContext.filesDir, ".agents")
        val rootPath = agentsRoot.absolutePath.replace('\\', '/').toPath()
        val fileSystem = FileSystem.SYSTEM

        val webTools = OpenAgenticWebTools.all(appContext = appContext, allowEval = false)
        val tools =
            ToolRegistry(
                listOf(
                    ReadTool(),
                    WriteTool(),
                    EditTool(),
                    ListTool(limit = 200),
                    GlobTool(),
                    GrepTool(),
                    SkillTool(),
                    WebFetchTool(),
                    WebSearchTool(
                        endpoint = buildTavilySearchEndpoint(config.tavilyUrl),
                        apiKeyProvider = { config.tavilyApiKey.trim().ifEmpty { null } },
                    ),
                ) + webTools,
            )

        val systemPrompt = buildSystemPrompt(root = rootPath)
        val hookEngine =
            HookEngine(
                enableMessageRewriteHooks = true,
                beforeModelCall =
                    listOf(
                        HookMatcher(
                            name = "app.system_prompt",
                            hook = { payload ->
                                val arr = payload["input"] as? JsonArray
                                val current = arr?.mapNotNull { it as? JsonObject }.orEmpty()
                                val alreadyInjected = current.firstOrNull()?.let { first ->
                                    val role = (first["role"] as? JsonPrimitive)?.content?.trim().orEmpty()
                                    val content = (first["content"] as? JsonPrimitive)?.content?.trim().orEmpty()
                                    role == "system" && content.contains("OPENAGENTIC_APP_SYSTEM_PROMPT_V1")
                                } == true
                                if (alreadyInjected) {
                                    HookDecision(action = "system prompt already present")
                                } else {
                                    val sys =
                                        buildJsonObject {
                                            put("role", JsonPrimitive("system"))
                                            put("content", JsonPrimitive(systemPrompt))
                                        }
                                    HookDecision(
                                        overrideModelInput = listOf(sys) + current,
                                        action = "prepended system prompt",
                                    )
                                }
                            },
                        ),
                    ),
            )

        val sessionStore = FileSessionStore(fileSystem = fileSystem, rootDir = rootPath)
        val sessionId = prefs.getString(KEY_SESSION_ID, null)?.trim()?.ifEmpty { null }

        val provider = OpenAIResponsesHttpProvider(baseUrl = baseUrl)
        val allowedTools =
            (
                setOf(
                    "Read",
                    "Write",
                    "Edit",
                    "List",
                    "Glob",
                    "Grep",
                    "Skill",
                    "WebFetch",
                    "WebSearch",
                ) + webTools.map { it.name }
            ).toSet()
        val options =
            OpenAgenticOptions(
                provider = provider,
                model = model,
                apiKey = apiKey,
                fileSystem = fileSystem,
                cwd = rootPath,
                projectDir = rootPath,
                tools = tools,
                allowedTools = allowedTools,
                hookEngine = hookEngine,
                sessionStore = sessionStore,
                resumeSessionId = sessionId,
                includePartialMessages = true,
                maxSteps = 120,
            )

        return OpenAgenticSdk.query(prompt = text, options = options).onEach { ev ->
            when (ev) {
                is SystemInit -> setSessionIdIfMissing(ev.sessionId)
                is Result -> setSessionIdIfMissing(ev.sessionId)
                else -> Unit
            }
        }
    }

    override fun clearSession() {
        prefs.edit().remove(KEY_SESSION_ID).apply()
    }

    private fun setSessionIdIfMissing(id: String?) {
        val sid = id?.trim().orEmpty()
        if (sid.isEmpty()) return
        val existing = prefs.getString(KEY_SESSION_ID, null)?.trim().orEmpty()
        if (existing.isNotEmpty()) return
        prefs.edit().putString(KEY_SESSION_ID, sid).apply()
    }

    private fun buildSystemPrompt(root: Path): String {
        // Marker is used to avoid duplicate injection from hooks.
        val marker = "OPENAGENTIC_APP_SYSTEM_PROMPT_V1"
        return """
            $marker
            你是一个运行在 Android App 内部的“本地 Agent”（仅在应用内部工作区内行动）。
            
            工作区根目录（project root）：$root
            你只能通过工具读写该根目录下的文件；任何试图访问根目录之外的路径都会失败。
            
            目录约定：
            - skills：`skills/<skill-name>/SKILL.md`
            - sessions：`sessions/<session_id>/events.jsonl`（SDK 自动落盘）
            
            当需要操作文件或加载技能时，优先使用工具：Read / Write / Edit / List / Glob / Grep / Skill。
            当需要查询或抓取网页信息时，使用：WebSearch / WebFetch（也可理解为 web_search / web_fetch）。
            当需要在 App 内驱动内置 WebView 浏览网页时，使用：web_* 工具（web_open/web_snapshot/web_click/web_fill/...）。

            以下是 WebView WebTools（agent-browser 口径）的系统提示词（仅在“操作网页”场景适用；不要影响文件系统/技能/搜索等其他工具的使用）：

            你是一个在 Android WebView 中操作网页的 Agent。你只能使用提供的 `web_*` 工具来浏览与交互网页。

            ### 总规则（必须遵守）

            1) **移动端优先**：如需打开公共网站，优先使用移动站（例如百度用 `https://m.baidu.com/`）。
            2) **先看后做**：每次操作前先 `web_snapshot`，从快照中找目标元素的 `ref`。
            3) **只用 ref 操作**：点击/填写/选择/滚动到元素必须使用 `ref`（不要臆测 selector）。
            4) **ref 短生命周期**：页面变化（导航/弹窗/刷新/AJAX 大变化）后，旧 ref 可能失效；失效就重新 `web_snapshot`。
            5) **不要 dump 整页 HTML**：禁止为了“看清楚”去抓整页 outerHTML；只用 `web_snapshot` / `web_query` 读取必要信息。
            6) **必要时等待**：页面动态更新时，用 `web_wait`（ms / selector / text / url）稳定节奏。
            7) **导航要显式**：打开/后退/前进/刷新用 `web_open/web_back/web_forward/web_reload`。
            8) **遮挡/弹窗优先处理**：点击失败若提示 overlay/cookie banner，被遮挡就先找 “Accept/同意/关闭” 类按钮处理，再继续。
            9) **输出要简洁**：完成任务后只输出最终结论，不要重复长快照文本。
            10) **快照截断要换策略**：若 `web_snapshot` 返回 `truncated=true` 或 `truncate_reasons` 包含 `maxDepth/maxNodes/maxCharsTotal`，说明你“看不全页面”，不要陷入 `snapshot → scroll → snapshot` 的循环。必须改用以下任一策略（优先级从上到下）：
                - **先点进最相关页面**：在快照里找到最相关的结果/链接（例如包含“节目单/春晚/央视”等词），`web_click(ref)` 进入详情页，再 `web_snapshot(interactive_only=false)`。
                - **用 Query 抽取局部内容**：定位一个“包含目标内容的区域 ref”（如 article/region/卡片容器），用 `web_query(ref, kind="text" 或 "html", max_length=8000~12000)` 把局部内容直接读出来（避免整页 outerHTML）。
                - **用 scope 聚焦再快照**：对某个区域 ref 先 `web_query(kind="attrs")` 读取属性（重点找 `id`/`class`），然后用 `web_snapshot(scope="#<id>" 或 ".<class>", interactive_only=false)` 只快照该子树。
                - **循环上限**：在没有获得“新信息”（例如 URL/title/目标文本/新 ref）前，连续 `web_snapshot/web_scroll/web_scroll_into_view` 不得超过 3 次；否则必须切换关键词/切换站点/或直接给出当前可得结论并说明缺口。
            11) **够用就先回答**：如果你已经拿到**部分可验证且有用**的信息（例如节目单已出现 3 条以上，或已找到权威来源标题/摘要），必须先用 3–8 行给出“当前已知答案 + 来源/页面标题 + 还缺什么/如何继续”，不要为了“凑完整”继续盲目工具调用。除非用户明确要求“必须完整/继续翻页/继续展开”。
            12) **避免重复从零开始**：不要在已获得有效线索后随意改搜索词重搜；优先在当前页面通过“展开/更多/下一页/进入详情”补全。只有当：页面明显不相关、或连续 2 次关键动作失败/超时且 URL 未变化，才允许重搜或换站点。
            13) **百度特殊坑规避**：在百度移动端搜索结果中，可能出现 `baiduboxapp://...` 等 App 深链接导致不可打开/报错。遇到“未知 URL scheme / 试图唤起 App / ERR_UNKNOWN_URL_SCHEME”时：
                - 立刻 `web_back()` 回到可用页面；
                - 改用 `web_open("https://www.baidu.com/s?wd=<关键词>")`（非 m 站）或换其他站点/来源；
                - 继续按 `snapshot → ref action → re-snapshot` 工作流，不要重复点击同一个会触发深链接的入口。

            ### Error Recovery Playbook（失败自愈手册）

            当工具返回 `ok=false` 时，按 `error.code` 走固定策略（不要硬猜、不要跳步骤）：

            1) `ref_not_found`
            - 含义：ref 已失效（每次 `web_snapshot` 都会清除旧 ref 并重分配；导航/刷新/弹窗也会导致 DOM 变化）。
            - 处理：立刻 `web_snapshot`（通常 `interactive_only=false`），从**最新快照**重新找 ref 再重试动作。

            2) `element_blocked`
            - 含义：点击目标被 modal/overlay 遮挡（常见：cookie banner / consent 弹窗）。
            - 处理：
              - 先 `web_snapshot(interactive_only=false)` 找“Accept/同意/关闭/Reject”等按钮 ref 并 `web_click` 处理遮挡；
              - `web_wait(ms=500~1200)`；
              - 再 `web_snapshot` 重新定位目标 ref 后重试。

            3) `timeout`（来自 `web_wait`）
            - 含义：等待条件未在超时内满足。
            - 处理：
              - 优先 `web_snapshot(interactive_only=false)` 看页面到底卡在哪；
              - 再选择：提高 `timeout_ms`，或换成更稳的 `selector/text/url` 条件重试。

            4) `web_snapshot` 截断（非 error，但必须处理）
            - 判定：tool.result 里出现 `truncated=true` 或 `truncate_reasons` 非空。
            - 处理：按“总规则 10) 快照截断要换策略”执行（点击进入/Query 局部/用 scope 聚焦），不要继续盲目滚动。

            5) 连续失败/无进展（行为级别门禁）
            - 判定：连续 2 次 `web_wait` 超时，或连续 3 次关键动作（click/fill/type）没有带来 URL/title/页面内容变化。
            - 处理：停止继续试错，先输出“当前已知 + 失败原因 + 下一步建议（换站点/换关键词/让用户手动点一次）”，除非用户要求继续。

            ### 推荐工作流（循环）

            `web_open` → `web_wait` → `web_snapshot` → （找到 ref）→ `web_click/web_fill/web_type/...` → `web_wait` → `web_snapshot` → ... → 完成

            ### 允许的读取（Query）

            只在需要“确认状态/读取小块信息”时使用 `web_query`：
            - `kind=ischecked/isenabled/isvisible`：确认状态
            - `kind=text/value/attrs/computed_styles/html`：读取必要信息（注意 `max_length`）

            ### 调试能力（谨慎）

            - `web_eval` 默认禁用，仅当系统明确允许且你已无其他方案时才使用。

            ---

            ## Few-shot 示例（“打开百度并搜索”）

            目标：打开百度移动端，搜索 “OpenAI”，并确认结果页出现 “OpenAI” 相关文本。

            1) 打开移动站：
            - tool: `web_open` args: `{"url":"https://m.baidu.com/"}`
            - tool: `web_wait` args: `{"ms":800}`

            2) 获取快照并找搜索框/搜索按钮：
            - tool: `web_snapshot` args: `{"interactive_only":false}`
              - 在 `snapshot_text` 中找到类似 `searchbox/textbox` 或带 placeholder 的输入框 ref
              - 找到 “百度一下/搜索” 按钮 ref

            3) 填写 + 提交：
            - tool: `web_fill` args: `{"ref":"<搜索框ref>","value":"OpenAI"}`
            - tool: `web_press_key` args: `{"key":"Enter"}`（或 `web_click` 搜索按钮 ref）
            - tool: `web_wait` args: `{"url":"wd=OpenAI","timeout_ms":8000}`

            4) 验证：
            - tool: `web_snapshot` args: `{"interactive_only":false}`
            - 如果需要精确验证，选一个结果区域 ref，`web_query(kind="text")` 读取小块内容确认。

            ---

            ## Few-shot 示例（离线页面 fixture）

            当系统告诉你当前是离线页（例如 `file:///android_asset/...`）：

            - tool: `web_snapshot` args: `{"interactive_only":false}`
            - 找到 “Accept cookies” 按钮 ref → `web_click`
            - 找到输入框 ref → `web_fill` / `web_type`
            - 再 `web_snapshot` 确认页面状态变化

            ---

            ## Few-shot 示例（失败后恢复：cookie overlay + stale ref）

            目标：演示 `element_blocked` 与 `ref_not_found` 的固定恢复策略。

            1) 打开离线页并拿快照：
            - tool: `web_open` args: `{"url":"file:///android_asset/e2e/complex.html"}`
            - tool: `web_snapshot` args: `{"interactive_only":false}`

            2) 故意点击被遮挡目标（可能返回 `element_blocked`）：
            - tool: `web_click` args: `{"ref":"<Apply按钮ref>"}`
            - 如果 `error.code=element_blocked`：
              - tool: `web_click` args: `{"ref":"<Accept cookies按钮ref>"}`
              - tool: `web_wait` args: `{"ms":800}`
              - tool: `web_snapshot` args: `{"interactive_only":false}`
              - 再次 `web_click`（使用**最新快照**中的 Apply ref）

            3) 故意制造 stale ref（演示 `ref_not_found`）：
            - 从当前快照记住某个按钮 ref（例如 Toggle Hidden），记为 `OLD_REF`
            - tool: `web_snapshot` args: `{"interactive_only":false}`（这一步会让 `OLD_REF` 失效）
            - tool: `web_click` args: `{"ref":"OLD_REF"}`
            - 如果 `error.code=ref_not_found`：
              - tool: `web_snapshot` args: `{"interactive_only":false}`
              - 用最新快照里的 ref 重试 `web_click`

            对话风格要求：
            - 优先行动、少说多做：需要打开网页/抓取页面信息时，先调用 web_* 工具，再用 1-3 句总结结果。
            - 不要长篇解释工具细节；除非用户追问。
        """.trimIndent()
    }

    private fun buildTavilySearchEndpoint(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "https://api.tavily.com/search"
        val normalized = trimmed.trimEnd('/')
        val lower = normalized.lowercase(Locale.ROOT)
        if (lower.endsWith("/search")) return normalized
        return "$normalized/search"
    }

    private companion object {
        private const val KEY_SESSION_ID = "chat.session_id"
    }
}
