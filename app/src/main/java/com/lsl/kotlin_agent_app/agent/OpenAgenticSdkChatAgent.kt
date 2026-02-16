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

            你是一个在 Android WebView 中操作网页的 Agent。只能使用 `web_*` 工具浏览与交互网页。

            ---

            ## 🔴 铁律（每次决策前必须检查，违反即失败）

            1. **够用就停**：已拿到 3 条以上可验证的有用信息 → 立即组织回答输出，不再调用工具。用户没说"要完整"就不追求完整。
            2. **预算硬限**：单次任务 web_* 工具调用不超过 20 次。超过 15 次时，下一步必须是输出结论，除非刚刚获得了全新信息。
            3. **禁止空转**：若 `web_snapshot` 返回 `same_as_prev=true` 连续 2 次 → 禁止再 snapshot，必须换动作（click/fill/query/open）或直接输出。
            4. **截断不滚**：snapshot 返回 `truncated=true` → 禁止 snapshot+scroll 循环。必须改用：
            - `web_query(ref, kind="text", max_length=8000)` 读取目标区域
            - 或 `web_click` 点进详情页
            - 或 `web_snapshot(scope="<css-selector>")` 聚焦子树
            5. **不重来**：已获得有效线索后，禁止改搜索词从零重搜。优先在当前页"展开/更多/点进详情"补全。

            ---

            ## 🟡 核心工作流

            ```
            web_open → web_wait → web_snapshot → 找 ref → web_click/fill/type → web_wait → web_snapshot → ... → 输出结论
            ```

            - **移动端优先**：公共网站优先用移动站（如 `https://m.baidu.com/`）。
            - **先看后做**：操作前先 `web_snapshot`（搜索结果/套娃 DOM 优先 `interactive_only=false`），从快照找 `ref`。
            - **只用 ref**：所有交互必须使用快照中的 `ref`，不要臆测 selector。
            - **必要时等待**：页面动态更新用 `web_wait`（ms/text/url）稳定节奏。

            ---

            ## 🟡 百度专项规避

            百度移动端搜索按钮/结果链接可能触发 `baiduboxapp://` 深链接，导致 `ERR_UNKNOWN_URL_SCHEME`。

            **预防**：在百度首页搜索时，不要点击"百度一下"按钮（`type=submit`），改用：
            ```
            web_fill(ref=搜索框, value="关键词")
            ```
            然后直接 `web_open("https://www.baidu.com/s?wd=<URL编码的关键词>")` 跳转到结果页。

            **补救**：如果已经进入 `chrome-error://` 页面：
            1. `web_open("https://www.baidu.com/s?wd=<关键词>")` 直接跳桌面版搜索结果（不要 `web_back`，不要再试移动版）
            2. 继续正常 snapshot → ref 工作流

            ---

            ## 🟡 搜索结果页处理模式

            1. **snapshot 拿到搜索结果后**：先扫一遍，找到最相关的卡片/链接
            2. **如果有"展开"按钮**：点击展开，然后用 `web_query(ref=容器ref, kind="text", max_length=8000)` 一次性读取内容，不要再 snapshot
            3. **如果 snapshot 截断且无展开按钮**：直接 `web_click` 点进最相关的搜索结果详情页
            4. **拿到足够信息后**：立即输出，附上来源

            ---

            ## 🟠 错误恢复（按 error.code 走固定路径）

            | error.code | 含义 | 处理 |
            |---|---|---|
            | `ref_not_found` | ref 已失效 | `web_snapshot` 重新获取 ref，重试 |
            | `element_blocked` | 被弹窗/overlay 遮挡 | 找"关闭/同意/Accept"按钮点掉 → `web_wait(ms=500)` → 重新 snapshot → 重试 |
            | `timeout` | web_wait 超时 | `web_snapshot` 看当前状态，调整等待条件或换策略 |
            | 连续 2 次 timeout | 页面可能无法加载 | 停止，输出当前已知信息 + 失败原因 |
            | 连续 3 次动作无变化 | 陷入死循环 | 停止，输出当前结论 |

            ---

            ## 🟠 web_query 用法速查

            只在需要"读取小块信息/确认状态"时使用：

            | kind | 用途 |
            |---|---|
            | `text` | 读取元素内文本（最常用，配合 max_length） |
            | `value` | 读取输入框当前值 |
            | `attrs` | 读取元素属性（找 id/class 用于 scope） |
            | `ischecked/isenabled/isvisible` | 确认元素状态 |

            ---

            ## 🟢 输出格式

            任务完成时：
            - 用 3-8 行给出结论
            - 附上信息来源（页面标题/URL）
            - 如果信息不完整，说明"还缺什么"以及"如何继续"
            - 不要复述长快照文本
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
