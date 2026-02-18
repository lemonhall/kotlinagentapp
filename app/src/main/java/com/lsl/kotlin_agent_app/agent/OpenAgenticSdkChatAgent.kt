package com.lsl.kotlin_agent_app.agent

import android.content.Context
import android.content.SharedPreferences
import com.lsl.kotlin_agent_app.BuildConfig
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalExecTool
import com.lsl.kotlin_agent_app.agent.tools.web.OpenAgenticWebTools
import com.lsl.kotlin_agent_app.config.AppPrefsKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.events.HookEvent
import me.lemonhall.openagentic.sdk.events.Event
import me.lemonhall.openagentic.sdk.events.Result
import me.lemonhall.openagentic.sdk.events.SystemInit
import me.lemonhall.openagentic.sdk.events.ToolUse
import me.lemonhall.openagentic.sdk.events.ToolResult
import me.lemonhall.openagentic.sdk.events.RuntimeError
import me.lemonhall.openagentic.sdk.hooks.HookDecision
import me.lemonhall.openagentic.sdk.hooks.HookEngine
import me.lemonhall.openagentic.sdk.hooks.HookMatcher
import me.lemonhall.openagentic.sdk.providers.OpenAIResponsesHttpProvider
import me.lemonhall.openagentic.sdk.compaction.CompactionOptions
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticOptions
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticSdk
import me.lemonhall.openagentic.sdk.runtime.TaskContext
import me.lemonhall.openagentic.sdk.runtime.TaskRunner
import me.lemonhall.openagentic.sdk.subagents.BuiltInSubAgents
import me.lemonhall.openagentic.sdk.subagents.TaskRunners
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import me.lemonhall.openagentic.sdk.tools.EditTool
import me.lemonhall.openagentic.sdk.tools.GlobTool
import me.lemonhall.openagentic.sdk.tools.GrepTool
import me.lemonhall.openagentic.sdk.tools.ListTool
import me.lemonhall.openagentic.sdk.tools.ReadTool
import me.lemonhall.openagentic.sdk.tools.SkillTool
import me.lemonhall.openagentic.sdk.tools.TaskAgent
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import me.lemonhall.openagentic.sdk.tools.WebFetchTool
import me.lemonhall.openagentic.sdk.tools.WebSearchTool
import me.lemonhall.openagentic.sdk.tools.WriteTool
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import java.security.MessageDigest
import com.lsl.kotlin_agent_app.agent.tools.irc.IrcSessionRuntimeStore

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
                    TerminalExecTool(appContext = appContext),
                    WebFetchTool(),
                    WebSearchTool(
                        endpoint = buildTavilySearchEndpoint(config.tavilyUrl),
                        apiKeyProvider = { config.tavilyApiKey.trim().ifEmpty { null } },
                    ),
                ),
            )

        val systemPrompt = buildMainSystemPrompt(root = rootPath)
        val hookEngine =
            systemPromptHookEngine(marker = "OPENAGENTIC_APP_SYSTEM_PROMPT_V1", systemPrompt = systemPrompt)

        val sessionStore = FileSessionStore(fileSystem = fileSystem, rootDir = rootPath)
        val sessionId = prefs.getString(AppPrefsKeys.CHAT_SESSION_ID, null)?.trim()?.ifEmpty { null }

        val provider = OpenAIResponsesHttpProvider(baseUrl = baseUrl)
        val progressEvents = MutableSharedFlow<Event>(extraBufferCapacity = 256)
        fun emitProgress(text: String) {
            val msg = text.trim().takeIf { it.isNotBlank() } ?: return
            progressEvents.tryEmit(
                HookEvent(
                    hookPoint = "TaskProgress",
                    name = "task.progress",
                    matched = true,
                    action = msg.take(400),
                ),
            )
        }
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
                    "Task",
                    "terminal_exec",
                )
            ).toSet()

        val appSubAgentRunner =
            TaskRunner { agent: String, taskPrompt: String, context: TaskContext ->
                runSubAgent(
                    agent = agent,
                    prompt = taskPrompt,
                    parentContext = context,
                    rootPath = rootPath,
                    fileSystem = fileSystem,
                    provider = provider,
                    apiKey = apiKey,
                    model = model,
                    tavilyUrl = config.tavilyUrl,
                    tavilyApiKey = config.tavilyApiKey,
                    emitProgress = ::emitProgress,
                )
            }

        val baseOptionsForBuiltins =
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
                taskRunner = null,
                sessionStore = sessionStore,
                resumeSessionId = null,
                createSessionMetadata = mapOf("kind" to "task", "agent" to "explore"),
                compaction = CompactionOptions(contextLimit = 200_000),
                includePartialMessages = false,
                maxSteps = 80,
            )
        val builtInExploreRunner = TaskRunners.builtInExplore(baseOptions = baseOptionsForBuiltins)
        val taskRunner = TaskRunners.compose(builtInExploreRunner, appSubAgentRunner)

        val taskAgents =
            listOf(
                BuiltInSubAgents.exploreTaskAgent(),
                TaskAgent(name = "webview", description = "Drive an embedded WebView for interactive browsing.", allowedTools = setOf("web_*")),
                TaskAgent(name = "deep-research", description = "Deep research: search/fetch + write a Markdown report file.", allowedTools = setOf("Read", "Write", "Edit", "WebFetch", "WebSearch", "web_*")),
            )
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
                taskProgressEmitter = ::emitProgress,
                taskRunner = taskRunner,
                taskAgents = taskAgents,
                sessionStore = sessionStore,
                resumeSessionId = sessionId,
                createSessionMetadata = mapOf("kind" to "primary"),
                compaction =
                    CompactionOptions(
                        // Proxy providers vary; keep this large enough for gpt-5.2-class context windows.
                        // Tool outputs are separately bounded (e.g. WebFetch), so we don't need to compact at ~16k.
                        contextLimit = 200_000,
                    ),
                includePartialMessages = true,
                maxSteps = 120,
            )

        val sdkFlow =
            OpenAgenticSdk.query(prompt = text, options = options).onEach { ev ->
                when (ev) {
                    is SystemInit -> setSessionIdIfMissing(ev.sessionId)
                    is Result -> setSessionIdIfMissing(ev.sessionId)
                    else -> Unit
                }
            }
        // NOTE: progressEvents is a hot SharedFlow and never completes.
        // If we merge it directly with sdkFlow, the collector in UI never completes, and the next send will be blocked.
        // We tie the progress collector lifecycle to sdkFlow completion.
        return channelFlow {
            val progressJob = launch {
                progressEvents.collect { send(it) }
            }
            try {
                sdkFlow.collect { send(it) }
            } finally {
                progressJob.cancel()
            }
        }
    }

    override fun clearSession() {
        val existing = prefs.getString(AppPrefsKeys.CHAT_SESSION_ID, null)?.trim()?.ifEmpty { null }
        if (existing != null) {
            IrcSessionRuntimeStore.closeSession(existing)
        }
        prefs.edit().remove(AppPrefsKeys.CHAT_SESSION_ID).apply()
    }

    private fun setSessionIdIfMissing(id: String?) {
        val sid = id?.trim().orEmpty()
        if (sid.isEmpty()) return
        val existing = prefs.getString(AppPrefsKeys.CHAT_SESSION_ID, null)?.trim().orEmpty()
        if (existing.isNotEmpty()) return
        prefs.edit().putString(AppPrefsKeys.CHAT_SESSION_ID, sid).apply()
    }

    private fun systemPromptHookEngine(
        marker: String,
        systemPrompt: String,
        actionLabel: String? = null,
    ): HookEngine {
        return HookEngine(
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
                                role == "system" && content.contains(marker)
                            } == true
                            if (alreadyInjected) {
                                HookDecision(action = buildSystemPromptHookAction(base = "system prompt already present", label = actionLabel))
                            } else {
                                val sys =
                                    buildJsonObject {
                                        put("role", JsonPrimitive("system"))
                                        put("content", JsonPrimitive(systemPrompt))
                                    }
                                HookDecision(
                                    overrideModelInput = listOf(sys) + current,
                                    action = buildSystemPromptHookAction(base = "prepended system prompt", label = actionLabel),
                                )
                            }
                        },
                    ),
                ),
        )
    }

    private fun buildMainSystemPrompt(root: Path): String {
        // Marker is used to avoid duplicate injection from hooks.
        val marker = "OPENAGENTIC_APP_SYSTEM_PROMPT_V1"
        return """
            $marker
            你是一个运行在 Android App 内部的“本地 Agent”（仅在应用内部工作区内行动）。
            
            ${TimeContextInfo.build()}

            工作区根目录（project root）：$root
            你只能通过工具读写该根目录下的文件；任何试图访问根目录之外的路径都会失败。
            
            目录约定：
            - skills：`skills/<skill-name>/SKILL.md`
            - sessions：`sessions/<session_id>/events.jsonl`（SDK 自动落盘）
            
            当需要操作文件或加载技能时，优先使用工具：Read / Write / Edit / List / Glob / Grep / Skill。
            当需要执行“伪终端/白名单 CLI”命令时，使用：terminal_exec（注意：这不是 bash，不支持管道/重定向/多命令）。
            当需要查询或抓取网页信息时，使用：WebSearch / WebFetch（也可理解为 web_search / web_fetch）。
            
            当需要在 App 内驱动内置 WebView 浏览网页时，**必须**使用子会话工具：
            - `Task(agent="webview", prompt="...")`
            
            当用户要求进行“深度研究 / deep-research”时，**必须**使用子会话工具：
            - `Task(agent="deep-research", prompt="<用户问题原文>")`
            
            当 `Task(agent="deep-research", ...)` 成功后：主对话必须给用户一个简短自然语言摘要（不要贴全文），并附上 `report_path` 方便用户打开阅读。
            - 摘要优先使用 `Task` 返回的 `report_summary`（它来自子会话生成的报告内容）。
            - 若摘要缺失/明显不完整，再用 `Read(file_path=<report_path>)` 读取报告开头少量内容补足摘要（避免把整篇报告读进上下文）。

            当 `Task(agent="deep-research", ...)` 失败（例如返回 `ok=false` 或 `stop_reason="error"`）时：
            - **禁止**自动再次调用 `Task(deep-research, ...)`（避免重试风暴 / 多 session 连锁）
            - 直接向用户说明“深度研究失败”的原因（简短），并附上 `report_path`（报告里包含失败原因、已收集材料指针与续跑建议）
            - 可建议用户稍后重试、或缩小研究范围/减少子话题
            当 `Task(agent="webview", ...)` 成功后：主对话直接用自然语言回答用户（输出结论与必要证据），不要输出任何 `sessions/.../events.jsonl` 等路径/调试信息。
            
            约束：
            - 主会话禁止直接调用任何 `web_*` 工具（避免高噪音输出污染历史导致上下文溢出）。
            - `Task(webview, ...)` 会返回结构化摘要（含子会话 session id 的追溯指针）。
        """.trimIndent()
    }

    private fun buildWebViewSubAgentPrompt(root: Path): String {
        val marker = "OPENAGENTIC_APP_WEBVIEW_SUBAGENT_PROMPT_V1"
        return """
            $marker
            你是一个在 Android WebView 中操作网页的子 Agent。只能使用 `web_*` 工具浏览与交互网页。
            
            ${TimeContextInfo.build()}

            工作区根目录（project root）：$root
            你只能通过工具读写该根目录下的文件；任何试图访问根目录之外的路径都会失败。
            
            你必须优先输出“结论/证据/下一步”，不要把长快照文本原样复述到对话里。

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
            - **交互只用 ref**：所有点击/输入/勾选等交互必须使用快照中的 `ref`，不要臆测 selector。
            - **读取允许 selector**：当需要“批量提取列表/卡片数据”（如机票/酒店/商品列表）且 snapshot 被截断时，允许用 `web_eval` 运行只读 JS（JS 内可用 `document.querySelectorAll(...)`），把结果整理成紧凑 JSON 返回（必要时调大 `max_length`），不要把整页 HTML 打回对话。
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

            ## 🟠 错误恢复（按 error.code 走固定路径）

            | error.code | 含义 | 处理 |
            |---|---|---|
            | `ref_not_found` | ref 已失效 | `web_snapshot` 重新获取 ref，重试 |
            | `element_blocked` | 被弹窗/overlay 遮挡 | 找"关闭/同意/Accept"按钮点掉 → `web_wait(ms=500)` → 重新 snapshot → 重试 |
            | `timeout` | web_wait 超时 | `web_snapshot` 看当前状态，调整等待条件或换策略 |
            | 连续 2 次 timeout | 页面可能无法加载 | 停止，输出当前已知信息 + 失败原因 |
            | 连续 3 次动作无变化 | 陷入死循环 | 停止，输出当前结论 |

            ---

            ## 🟢 输出格式（强制）

            返回给主会话的内容必须满足：
            - 3-8 行结论
            - 1-3 条证据（标题/URL/页面内证据点）
            - 如不完整：说明还缺什么 + 继续步骤
            - 不复述长快照文本（只摘关键 ref/关键句即可）
        """.trimIndent()
    }

    private suspend fun runSubAgent(
        agent: String,
        prompt: String,
        parentContext: TaskContext,
        rootPath: Path,
        fileSystem: FileSystem,
        provider: OpenAIResponsesHttpProvider,
        apiKey: String,
        model: String,
        tavilyUrl: String,
        tavilyApiKey: String,
        emitProgress: ((String) -> Unit)? = null,
    ): JsonElement {
        if (agent != "webview" && agent != "deep-research") {
            return buildJsonObject {
                put("ok", JsonPrimitive(false))
                put("error_type", JsonPrimitive("UnknownSubAgent"))
                put("error_message", JsonPrimitive("unknown agent: $agent (supported: webview, deep-research)"))
            }
        }

        return if (agent == "webview") {
            emitProgress?.invoke("子任务(webview)：启动")
            val webTools = OpenAgenticWebTools.all(appContext = appContext, allowEval = BuildConfig.DEBUG)
            val tools = ToolRegistry(webTools)
            val allowedTools = webTools.map { it.name }.toSet()

            val systemPrompt = buildWebViewSubAgentPrompt(root = rootPath)
            val hookEngine = systemPromptHookEngine(marker = "OPENAGENTIC_APP_WEBVIEW_SUBAGENT_PROMPT_V1", systemPrompt = systemPrompt)
            val sessionStore = FileSessionStore(fileSystem = fileSystem, rootDir = rootPath)

            var lastResult: Result? = null
            var lastRuntimeError: RuntimeError? = null
            OpenAgenticSdk.query(
                prompt = prompt,
                options =
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
                        taskRunner = null,
                        sessionStore = sessionStore,
                        resumeSessionId = null,
                        createSessionMetadata =
                            mapOf(
                                "kind" to "task",
                                "agent" to agent,
                                "parent_session_id" to parentContext.sessionId,
                            ),
                        compaction = CompactionOptions(contextLimit = 200_000),
                        includePartialMessages = false,
                        maxSteps = 80,
                    ),
            ).collect { ev ->
                when (ev) {
                    is ToolUse -> emitProgress?.invoke("子任务(webview)：${humanizeProgressToolUse(ev.name, ev.input)}")
                    is ToolResult -> if (ev.isError) emitProgress?.invoke("子任务(webview)：工具失败 ${ev.errorType ?: "error"}")
                    is RuntimeError -> {
                        lastRuntimeError = ev
                        emitProgress?.invoke("子任务(webview)：运行错误 ${ev.errorType}")
                    }
                    is Result -> lastResult = ev
                    else -> Unit
                }
            }
            val result = lastResult ?: Result(finalText = "", sessionId = "", stopReason = "error")

            val summary =
                result.finalText
                    .trim()
                    .ifEmpty { "(empty)" }
                    .let { text ->
                        val max = 4000
                        if (text.length <= max) text else (text.take(1800) + "\n…truncated…\n" + text.takeLast(1800))
                    }

            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("agent", JsonPrimitive(agent))
                put("sub_session_id", JsonPrimitive(result.sessionId))
                put("answer", JsonPrimitive(summary))
                if (result.stopReason != null) put("stop_reason", JsonPrimitive(result.stopReason))
                if (lastRuntimeError?.errorType?.isNotBlank() == true) put("error_type", JsonPrimitive(lastRuntimeError!!.errorType))
                if (!lastRuntimeError?.errorMessage.isNullOrBlank()) put("error_message", JsonPrimitive(lastRuntimeError!!.errorMessage!!))
            }
        } else {
            emitProgress?.invoke("子任务(deep-research)：启动")
            val reportPathRel = allocateDeepResearchReportPath()
            val reportPathAbs = File(rootPath.toString(), reportPathRel).absolutePath.replace('\\', '/')
            val preface =
                """
                你正在执行 deep-research 子会话。你必须生成一个 Markdown 研究交付报告文件，并写入下面这个路径（必须精确一致）：
                
                report_path: $reportPathAbs
                
                交付要求：
                - 用 deep-research 的结构化格式（执行摘要/关键发现/详细分析/参考来源等）
                - 引用用 [1][2] 编号；参考来源列表放在末尾
                - 研究过程不要塞回主对话；最终在聊天里只输出 report_path（一行即可）
                """.trimIndent()

            val webTools = OpenAgenticWebTools.all(appContext = appContext, allowEval = BuildConfig.DEBUG)
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
                            endpoint = buildTavilySearchEndpoint(tavilyUrl),
                            apiKeyProvider = { tavilyApiKey.trim().ifEmpty { null } },
                        ),
                    ) + webTools,
                )
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

            val skillBody =
                try {
                    loadDeepResearchSkillBody(root = rootPath)
                } catch (_: Throwable) {
                    null
                }
            val systemPrompt = buildDeepResearchSubAgentPrompt(root = rootPath, deepResearchSkillBody = skillBody?.body)
            val hookEngine =
                systemPromptHookEngine(
                    marker = "OPENAGENTIC_APP_DEEP_RESEARCH_SUBAGENT_PROMPT_V1",
                    systemPrompt = systemPrompt,
                    actionLabel =
                        skillBody?.let {
                            val sha = sha256Hex(it.body).take(12)
                            "deep-research skill injected source=${it.source} chars=${it.body.length} sha256=$sha"
                        } ?: "deep-research skill missing",
                )
            val sessionStore = FileSessionStore(fileSystem = fileSystem, rootDir = rootPath)

            var lastResult: Result? = null
            var lastRuntimeError: RuntimeError? = null
            OpenAgenticSdk.query(
                prompt = preface + "\n\n" + prompt.trim(),
                options =
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
                        taskRunner = null,
                        sessionStore = sessionStore,
                        resumeSessionId = null,
                        createSessionMetadata =
                            mapOf(
                                "kind" to "task",
                                "agent" to agent,
                                "parent_session_id" to parentContext.sessionId,
                            ),
                        compaction = CompactionOptions(contextLimit = 200_000),
                        includePartialMessages = false,
                        maxSteps = 200,
                    ),
            ).collect { ev ->
                when (ev) {
                    is ToolUse -> emitProgress?.invoke("子任务(deep-research)：${humanizeProgressToolUse(ev.name, ev.input)}")
                    is ToolResult -> if (ev.isError) emitProgress?.invoke("子任务(deep-research)：工具失败 ${ev.errorType ?: "error"}")
                    is RuntimeError -> {
                        lastRuntimeError = ev
                        emitProgress?.invoke("子任务(deep-research)：运行错误 ${ev.errorType}")
                    }
                    is Result -> lastResult = ev
                    else -> Unit
                }
            }
            val result = lastResult ?: Result(finalText = "", sessionId = "", stopReason = "error")

            val ok = (result.stopReason ?: "").lowercase(Locale.ROOT) != "error" && lastRuntimeError == null

            val fallbackMarkdown =
                if (ok) {
                    result.finalText.trim().ifEmpty { "(empty)" }
                } else {
                    val errType = lastRuntimeError?.errorType?.ifBlank { null } ?: "ProviderError"
                    val errMsg = lastRuntimeError?.errorMessage?.trim()?.ifBlank { null } ?: "unknown"
                    """
                    # 深度研究报告（失败）

                    本次 deep-research 子会话未能完成最终报告生成，已按“失败可交付”原则落盘本文件，供追溯与续跑。

                    ## 失败原因
                    - error_type: $errType
                    - error_message: $errMsg
                    - stop_reason: ${result.stopReason ?: "error"}

                    ## 已收集材料（追溯指针）
                    - events: $rootPath/sessions/${result.sessionId}/events.jsonl
                    - tool-output: $rootPath/tool-output/ （如存在）

                    ## 续跑建议
                    1. 稍后重试（网络/代理瞬断通常可恢复）。
                    2. 缩小范围：减少子话题/限定时间范围/限定来源类型。
                    3. 如仍失败：导出本子会话 `events.jsonl` 供定位。
                    """.trimIndent()
                }

            ensureFileExistsWithFallback(
                absolutePath = reportPathAbs,
                fallbackMarkdown = fallbackMarkdown,
            )

            val reportSummary =
                try {
                    extractDeepResearchSummaryFromReport(absolutePath = reportPathAbs, maxChars = 1200)
                } catch (_: Throwable) {
                    null
                }

            buildJsonObject {
                put("ok", JsonPrimitive(ok))
                put("agent", JsonPrimitive(agent))
                put("parent_session_id", JsonPrimitive(parentContext.sessionId))
                put("parent_tool_use_id", JsonPrimitive(parentContext.toolUseId))
                put("sub_session_id", JsonPrimitive(result.sessionId))
                put("events_path", JsonPrimitive("sessions/${result.sessionId}/events.jsonl"))
                put("report_path", JsonPrimitive(reportPathRel))
                if (!reportSummary.isNullOrBlank()) put("report_summary", JsonPrimitive(reportSummary))
                if (result.stopReason != null) put("stop_reason", JsonPrimitive(result.stopReason))
                if (lastRuntimeError?.errorType?.isNotBlank() == true) put("error_type", JsonPrimitive(lastRuntimeError!!.errorType))
                if (!lastRuntimeError?.errorMessage.isNullOrBlank()) put("error_message", JsonPrimitive(lastRuntimeError!!.errorMessage!!))
            }
        }
    }

    private fun humanizeProgressToolUse(
        name: String,
        input: JsonObject?,
    ): String {
        fun str(key: String): String = (input?.get(key) as? JsonPrimitive)?.content?.trim().orEmpty()
        return when (name.trim()) {
            "WebSearch" -> str("query").takeIf { it.isNotBlank() }?.let { "搜索：${it.take(40)}" } ?: "搜索中"
            "WebFetch" -> {
                val url = str("url")
                val host = url.substringAfter("://", url).substringBefore('/').take(60)
                if (host.isNotBlank()) "抓取：$host" else "抓取网页"
            }
            "Read" -> str("file_path").takeIf { it.isNotBlank() }?.let { "读取文件：${it.takeLast(60)}" } ?: "读取文件"
            "Write" -> str("file_path").takeIf { it.isNotBlank() }?.let { "写入文件：${it.takeLast(60)}" } ?: "写入文件"
            "Edit" -> str("file_path").takeIf { it.isNotBlank() }?.let { "编辑文件：${it.takeLast(60)}" } ?: "编辑文件"
            "List" -> str("path").takeIf { it.isNotBlank() }?.let { "列目录：${it.takeLast(60)}" } ?: "列目录"
            "Glob" -> str("pattern").takeIf { it.isNotBlank() }?.let { "匹配文件：${it.take(60)}" } ?: "匹配文件"
            "Grep" -> str("pattern").takeIf { it.isNotBlank() }?.let { "搜索文本：${it.take(40)}" } ?: "搜索文本"
            "Skill" -> str("name").takeIf { it.isNotBlank() }?.let { "加载技能：${it.take(40)}" } ?: "加载技能"
            "Task" -> str("agent").takeIf { it.isNotBlank() }?.let { "运行子任务：${it.take(32)}" } ?: "运行子任务"
            "terminal_exec" -> str("command").takeIf { it.isNotBlank() }?.let { "运行命令：${it.take(60)}" } ?: "运行命令"
            "web_open" -> {
                val url = str("url")
                val host = url.substringAfter("://", url).substringBefore('/').take(60)
                if (host.isNotBlank()) "打开网页：$host" else "打开网页"
            }
            "web_wait" -> "等待页面就绪"
            "web_snapshot" -> "读取页面快照"
            "web_click" -> "点击页面元素"
            "web_fill" -> "填写输入框"
            "web_type" -> "输入文本"
            "web_eval" -> "执行页面脚本"
            else -> name.trim().ifBlank { "处理中" }.take(40)
        }
    }

    private fun buildDeepResearchSubAgentPrompt(
        root: Path,
        deepResearchSkillBody: String?,
    ): String {
        val marker = "OPENAGENTIC_APP_DEEP_RESEARCH_SUBAGENT_PROMPT_V1"
        return """
            $marker
            你是一个“深度研究（deep-research）”子 Agent。你的目标是产出一个可阅读的研究交付报告 Markdown 文件，并只把报告路径返回给主会话。
            
            ${TimeContextInfo.build()}

            工作区根目录（project root）：$root
            你只能通过工具读写该根目录下的文件；任何试图访问根目录之外的路径都会失败。
            
            约束：
            - 优先使用 WebFetch/WebSearch 做快速抓取与检索。
            - 仅当网页明显依赖 JS 渲染、或 WebFetch 无法获得正文时，才使用 `web_*`（WebView）工具。
            - 若 `web_*` 返回未绑定（例如提示需要先打开 Web 页签初始化 WebView），立刻降级回 WebFetch/WebSearch，不要死磕。
            - 研究过程不要在聊天里输出长正文；正文写入 report_path 指定文件。
            - 最终在聊天里只输出一行：`report_path: <path>`（必须包含 report_path 字样）。
            
            ---
            
            ## 已加载 Skill：deep-research
            
            下方是当前 App 内置的 deep-research 技能正文（供你严格遵循）。你不需要也不应该再次调用 `Skill(name="deep-research")` 来加载它。
            
            ${deepResearchSkillBody?.trim().orEmpty()}
        """.trimIndent()
    }

    private fun allocateDeepResearchReportPath(): String {
        val dir = "artifacts/reports/deep-research"
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val ts = fmt.format(Date())
        return "$dir/${ts}_Deep-Research.md"
    }

    private suspend fun ensureFileExistsWithFallback(
        absolutePath: String,
        fallbackMarkdown: String,
    ) {
        val f = File(absolutePath)
        withContext(Dispatchers.IO) {
            val parent = f.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            val shouldWrite =
                if (!f.exists() || f.length() <= 0L) {
                    true
                } else {
                    // Overwrite a placeholder-only file (common when a sub-task fails before producing content).
                    // Keep this bounded to avoid reading large reports.
                    try {
                        val cap = 32 * 1024
                        val bounded = ByteArray(cap)
                        val len =
                            FileInputStream(f).use { input ->
                                var offset = 0
                                while (offset < cap) {
                                    val n = input.read(bounded, offset, cap - offset)
                                    if (n <= 0) break
                                    offset += n
                                }
                                offset
                            }
                        val text = bounded.copyOf(len).toString(Charsets.UTF_8).trim()
                        text.isBlank() || text == "(empty)"
                    } catch (_: Throwable) {
                        false
                    }
                }
            if (shouldWrite) {
                f.writeText(fallbackMarkdown.ifBlank { "(empty)" } + "\n", Charsets.UTF_8)
            }
        }
    }

    private data class LoadedSkillBody(
        val body: String,
        val source: String,
    )

    private fun loadDeepResearchSkillBody(root: Path): LoadedSkillBody? {
        val rel = "skills/deep-research/SKILL.md"
        val f = File(root.toString(), rel)
        if (f.exists() && f.isFile) {
            val raw = f.readText(Charsets.UTF_8)
            val body = stripYamlFrontmatter(raw).trim().ifBlank { null } ?: return null
            return LoadedSkillBody(body = body, source = rel)
        }

        // Fallback: read bundled asset directly (best-effort).
        return try {
            val assetPath = "builtin_skills/deep-research/SKILL.md"
            val raw =
                appContext.assets.open(assetPath).use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                }
            val body = stripYamlFrontmatter(raw).trim().ifBlank { null } ?: return null
            LoadedSkillBody(body = body, source = "asset:$assetPath")
        } catch (_: Throwable) {
            null
        }
    }

    private fun stripYamlFrontmatter(raw: String): String {
        val s = raw.trimStart()
        if (!s.startsWith("---")) return raw
        val lines = s.split('\n')
        if (lines.isEmpty()) return raw
        if (lines.first().trim() != "---") return raw
        val endIdx = lines.indexOfFirst { it.trim() == "---" && it != lines.first() }
        if (endIdx <= 0) return raw
        return lines.drop(endIdx + 1).joinToString("\n")
    }

    private fun buildTavilySearchEndpoint(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "https://api.tavily.com/search"
        val normalized = trimmed.trimEnd('/')
        val lower = normalized.lowercase(Locale.ROOT)
        if (lower.endsWith("/search")) return normalized
        return "$normalized/search"
    }

    private fun buildSystemPromptHookAction(
        base: String,
        label: String?,
    ): String {
        val l = label?.trim().orEmpty()
        return if (l.isEmpty()) base else "$base ($l)"
    }

    private fun sha256Hex(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { b -> "%02x".format(b) }
    }

    private fun extractDeepResearchSummaryFromReport(
        absolutePath: String,
        maxChars: Int,
    ): String? {
        val f = File(absolutePath)
        if (!f.exists() || !f.isFile) return null
        val raw =
            try {
                // Keep this bounded; we only need the top of the report for a summary.
                val bytes = f.readBytes()
                val cap = 256 * 1024
                val bounded = if (bytes.size <= cap) bytes else bytes.copyOf(cap)
                bounded.toString(Charsets.UTF_8)
            } catch (_: Throwable) {
                return null
            }
        val text = raw.replace("\r\n", "\n").trim()
        if (text.isBlank()) return null

        val section =
            extractMarkdownSection(
                markdown = text,
                headingPatterns =
                    listOf(
                        Regex("(?im)^#{1,3}\\s*(执行摘要)\\s*$"),
                        Regex("(?im)^#{1,3}\\s*(Executive\\s+Summary)\\s*$"),
                    ),
            ) ?: extractMarkdownSection(
                markdown = text,
                headingPatterns =
                    listOf(
                        Regex("(?im)^#{1,3}\\s*(关键发现|Key\\s+Findings)\\s*$"),
                    ),
            )

        val candidate =
            (section ?: text)
                .trim()
                .replace(Regex("(?m)^#{1,6}\\s+.*$"), "")
                .trim()
                .ifBlank { null }
                ?: return null

        val limit = maxChars.coerceAtLeast(0)
        if (limit <= 0) return ""
        return if (candidate.length <= limit) candidate else headTailForUi(text = candidate, maxChars = limit)
    }

    private fun extractMarkdownSection(
        markdown: String,
        headingPatterns: List<Regex>,
    ): String? {
        val md = markdown.replace("\r\n", "\n")
        val startMatch =
            headingPatterns.firstNotNullOfOrNull { rx -> rx.find(md) }
                ?: return null
        val start = startMatch.range.last + 1
        if (start >= md.length) return null
        val after = md.substring(start)
        val nextHeading = Regex("(?m)^#{1,3}\\s+\\S.*$").find(after)
        val body = if (nextHeading != null) after.substring(0, nextHeading.range.first) else after
        return body.trim().ifBlank { null }
    }

    private fun headTailForUi(
        text: String,
        maxChars: Int,
    ): String {
        val limit = maxChars.coerceAtLeast(0)
        if (limit <= 0) return ""
        if (text.length <= limit) return text
        val marker = "\n…(truncated)…\n"
        val remaining = (limit - marker.length).coerceAtLeast(0)
        val headLen = remaining / 2
        val tailLen = remaining - headLen
        return text.take(headLen) + marker + text.takeLast(tailLen)
    }

    private companion object {
    }
}
