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
                maxSteps = 20,
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

            Web 工具使用规则（必须遵守）：
            - 先看后做：任何点击/填写前先 web_snapshot，从 snapshot_text 中找到 [ref=eN]。
            - 只用 ref 操作：click/fill/select 等只接受 ref，不要臆测 selector。
            - ref 短生命周期：页面导航/刷新/弹窗导致 DOM 变化后，旧 ref 可能失效；失效就重新 web_snapshot。
            - 严禁 dump 整页 HTML：不要为了“看清楚”去抓 document.body.outerHTML；用 web_snapshot + web_query(limit) 获取必要信息。

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
