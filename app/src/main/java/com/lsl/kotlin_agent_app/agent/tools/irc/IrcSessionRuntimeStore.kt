package com.lsl.kotlin_agent_app.agent.tools.irc

import android.content.Context
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.lemonhall.openagentic.sdk.events.AssistantMessage
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import okio.FileSystem
import okio.Path.Companion.toPath
import com.lsl.kotlin_agent_app.config.SharedPreferencesLlmConfigRepository
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.hooks.HookDecision
import me.lemonhall.openagentic.sdk.hooks.HookEngine
import me.lemonhall.openagentic.sdk.hooks.HookMatcher
import me.lemonhall.openagentic.sdk.providers.OpenAIResponsesHttpProvider
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticOptions
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticSdk
import me.lemonhall.openagentic.sdk.tools.ToolRegistry

internal object IrcSessionRuntimeStore {
    private const val maxLogLines: Int = 200
    private const val connectThrottleMs: Long = 15_000L
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var appContext: Context? = null

    fun installAppContext(context: Context) {
        appContext = context.applicationContext
    }

    private data class Entry(
        val status: MutableStateFlow<IrcStatusSnapshot> = MutableStateFlow(IrcStatusSnapshot.notInitialized()),
        var runtime: IrcSessionRuntime? = null,
        val mutex: Mutex = Mutex(),
        val logs: MutableStateFlow<List<String>> = MutableStateFlow(emptyList()),
        var lastConnectAttemptMs: Long = 0L,
        var activeConnectJob: Job? = null,
        val inbound: MutableStateFlow<List<IrcInboundMessage>> = MutableStateFlow(emptyList()),
        val questions: MutableStateFlow<List<IrcHumanQuestion>> = MutableStateFlow(emptyList()),
        val pendingTriage: ArrayDeque<IrcInboundMessage> = ArrayDeque(),
        var activeTriageJob: Job? = null,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun statusFlow(sessionKey: String): StateFlow<IrcStatusSnapshot> {
        val e = entries.getOrPut(sessionKey) { Entry() }
        return e.status.asStateFlow()
    }

    fun logsFlow(sessionKey: String): StateFlow<List<String>> {
        val e = entries.getOrPut(sessionKey) { Entry() }
        return e.logs.asStateFlow()
    }

    fun inboundFlow(sessionKey: String): StateFlow<List<IrcInboundMessage>> {
        val e = entries.getOrPut(sessionKey) { Entry() }
        return e.inbound.asStateFlow()
    }

    fun questionsFlow(sessionKey: String): StateFlow<List<IrcHumanQuestion>> {
        val e = entries.getOrPut(sessionKey) { Entry() }
        return e.questions.asStateFlow()
    }

    fun consumeQuestions(sessionKey: String): List<IrcHumanQuestion> {
        val e = entries.getOrPut(sessionKey) { Entry() }
        val prev = e.questions.value
        if (prev.isEmpty()) return emptyList()
        e.questions.value = emptyList()
        return prev
    }

    fun requestConnect(
        agentsRoot: File,
        sessionKey: String,
        force: Boolean,
    ) {
        val e = entries.getOrPut(sessionKey) { Entry() }
        if (e.activeConnectJob?.isActive == true) return
        e.activeConnectJob =
            backgroundScope.launch {
                try {
                    ensureConnectedAndJoinedIfPossibleInternal(agentsRoot = agentsRoot, sessionKey = sessionKey, force = force)
                } finally {
                    e.activeConnectJob = null
                }
            }
    }

    private suspend fun ensureConnectedAndJoinedIfPossibleInternal(
        agentsRoot: File,
        sessionKey: String,
        force: Boolean,
    ) {
        val e = entries.getOrPut(sessionKey) { Entry() }
        val now = System.currentTimeMillis()
        if (!force && (now - e.lastConnectAttemptMs) < connectThrottleMs) return
        e.lastConnectAttemptMs = now

        val config =
            IrcConfigLoader.loadFromAgentsRoot(agentsRoot)
                ?: run {
                    appendLog(e, "MissingCredentials: .agents/skills/${IrcConfigLoader.skillName}/secrets/.env incomplete")
                    e.status.value =
                        e.status.value.copy(
                            state = IrcConnectionState.Error,
                            lastError = IrcLastError(errorCode = "MissingCredentials", message = "IRC .env 未配置或字段不完整"),
                        )
                    return
                }
        if (config.nick.length > 9) {
            appendLog(e, "NickTooLong: IRC_NICK length=${config.nick.length} (>9)")
            e.status.value =
                e.status.value.copy(
                    state = IrcConnectionState.Error,
                    lastError = IrcLastError(errorCode = "NickTooLong", message = "IRC_NICK 长度必须 <= 9"),
                )
            return
        }

        val rt = getOrCreateRuntime(agentsRoot = agentsRoot, sessionKey = sessionKey, config = config)
        appendLog(e, "Connecting…")
        try {
            rt.ensureConnectedAndJoinedDefault()
            appendLog(e, "Joined.")
        } catch (t: CancellationException) {
            appendLog(e, "Cancelled: ${t.message ?: "composition left"}")
        } catch (t: Throwable) {
            appendLog(e, "Error(${t::class.java.simpleName}): ${t.message ?: "unknown"}")
        }
    }

    suspend fun getOrCreateRuntime(
        agentsRoot: File,
        sessionKey: String,
        config: IrcConfig,
    ): IrcSessionRuntime {
        val e = entries.getOrPut(sessionKey) { Entry() }
        return e.mutex.withLock {
            val existing = e.runtime
            if (existing != null) return@withLock existing
            val parentJob = backgroundScope.coroutineContext[Job]
            val scope = CoroutineScope(SupervisorJob(parentJob) + Dispatchers.IO)
            val rt =
                IrcSessionRuntime(
                    agentsRoot = agentsRoot,
                    sessionKey = sessionKey,
                    config = config,
                    scope = scope,
                    statusFlow = e.status,
                    onInbound = { msg -> onInboundInternal(entry = e, agentsRoot = agentsRoot, sessionKey = sessionKey, config = config, msg = msg) },
                    onLog = { msg -> appendLog(e, msg) },
                )
            e.runtime = rt
            rt
        }
    }

    fun closeSession(sessionKey: String) {
        val e = entries[sessionKey] ?: return
        val rt = e.runtime
        e.runtime = null
        rt?.close()
        e.status.value = IrcStatusSnapshot.notInitialized()
        e.logs.value = emptyList()
    }

    fun clearForTest() {
        entries.keys.toList().forEach { closeSession(it) }
        entries.clear()
    }

    private fun appendLog(
        entry: Entry,
        message: String,
    ) {
        val ts = System.currentTimeMillis()
        val line = "$ts $message"
        val prev = entry.logs.value
        val next =
            if (prev.size < maxLogLines) {
                prev + line
            } else {
                prev.drop(prev.size - (maxLogLines - 1)) + line
            }
        entry.logs.value = next
    }

    private fun onInboundInternal(
        entry: Entry,
        agentsRoot: File,
        sessionKey: String,
        config: IrcConfig,
        msg: IrcInboundMessage,
    ) {
        // UI: keep a small rolling window of inbound messages.
        val prev = entry.inbound.value
        val next = (prev + msg).takeLast(60)
        entry.inbound.value = next

        if (!config.autoForwardToAgent) return
        if (!isValidSessionId(sessionKey)) return

        synchronized(entry.pendingTriage) {
            entry.pendingTriage.addLast(msg)
        }
        if (entry.activeTriageJob?.isActive == true) return
        entry.activeTriageJob =
            backgroundScope.launch {
                try {
                    runTriageLoop(entry = entry, agentsRoot = agentsRoot, sessionKey = sessionKey)
                } finally {
                    entry.activeTriageJob = null
                }
            }
    }

    private suspend fun runTriageLoop(
        entry: Entry,
        agentsRoot: File,
        sessionKey: String,
    ) {
        while (true) {
            // Coalesce bursty inbound messages a bit.
            delay(800)
            val batch = ArrayList<IrcInboundMessage>(5)
            synchronized(entry.pendingTriage) {
                while (batch.size < 5 && entry.pendingTriage.isNotEmpty()) {
                    batch.add(entry.pendingTriage.removeFirst())
                }
            }
            if (batch.isEmpty()) return

            val ctx = appContext
            if (ctx == null) {
                appendLog(entry, "auto_forward triage skipped: appContext not installed")
                continue
            }

            val triage =
                runAutoForwardTriage(
                    appContext = ctx,
                    entry = entry,
                    agentsRoot = agentsRoot,
                    sessionKey = sessionKey,
                    batch = batch,
                )
            if (triage.isEmpty()) continue

            for (q in triage) {
                val prevQ = entry.questions.value
                entry.questions.value = (prevQ + q).takeLast(30)
            }
        }
    }

    private fun sessionStoreForAgentsRoot(agentsRoot: File): FileSessionStore {
        val rootPath = agentsRoot.canonicalFile.absolutePath.replace('\\', '/').toPath()
        return FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = rootPath)
    }

    private fun isValidSessionId(sessionId: String): Boolean {
        val sid = sessionId.trim()
        return sid.length == 32 && sid.all { it in "0123456789abcdefABCDEF" }
    }

    private data class TriageDecision(
        val id: String,
        val needsHuman: Boolean,
        val question: String,
    )

    private suspend fun runAutoForwardTriage(
        appContext: Context,
        entry: Entry,
        agentsRoot: File,
        sessionKey: String,
        batch: List<IrcInboundMessage>,
    ): List<IrcHumanQuestion> {
        val prefs = appContext.getSharedPreferences("kotlin-agent-app", Context.MODE_PRIVATE)
        val llm = SharedPreferencesLlmConfigRepository(prefs).get()
        val baseUrl = llm.baseUrl.trim()
        val apiKey = llm.apiKey.trim()
        val model = llm.model.trim()
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
            appendLog(entry, "auto_forward skipped: llm config incomplete")
            return emptyList()
        }

        val provider = OpenAIResponsesHttpProvider(baseUrl = baseUrl)
        val systemPrompt =
            """
            你是 IRC 新消息分流器（仅做判断，不执行任何工具/动作）。
            给定一组 IRC 新消息，请逐条判断“是否需要人类处理”。

            判断规则（倾向减少打扰）：
            - 只有当消息明确要求你做事、需要回复、涉及决策/授权/风险、或明确点名/询问时，才 needs_human=true。
            - 聊天闲聊、转发、无关信息、无需回应的内容，一律 needs_human=false。

            只输出 JSON，不要输出其它任何文字，格式如下：
            {"decisions":[{"id":"<id>","needs_human":true|false,"question":"<若 needs_human=true，用一句话向人类提问；否则空字符串>"}]}
            """.trimIndent()

        fun renderMsg(m: IrcInboundMessage): JsonObject {
            val text = m.text.replace("\u0000", "").replace("\r\n", "\n").trim().take(500)
            return buildJsonObject {
                put("id", JsonPrimitive(m.id))
                put("channel", JsonPrimitive(m.channel.take(64)))
                put("nick", JsonPrimitive(m.nick.take(64)))
                put("text", JsonPrimitive(text))
            }
        }

        val triageSessionId = triageSessionIdFor(sessionKey)
        val store = sessionStoreForAgentsRoot(agentsRoot)
        val rootPath = agentsRoot.canonicalFile.absolutePath.replace('\\', '/').toPath()
        val hookEngine = triageSystemPromptHookEngine(marker = "IRC_TRIAGE_V1", systemPrompt = systemPrompt)
        val options =
            OpenAgenticOptions(
                provider = provider,
                model = model,
                apiKey = apiKey,
                fileSystem = FileSystem.SYSTEM,
                cwd = rootPath,
                projectDir = rootPath,
                tools = ToolRegistry(emptyList()),
                allowedTools = emptySet(),
                hookEngine = hookEngine,
                sessionStore = store,
                resumeSessionId = triageSessionId,
                createSessionMetadata = mapOf("kind" to "irc_triage", "parent_session" to sessionKey),
                includePartialMessages = false,
                maxSteps = 6,
            )

        val userPrompt =
            buildString {
                appendLine("IRC 新消息批次（parent_session=$sessionKey）:")
                val arr: JsonElement = JsonArray(batch.map { renderMsg(it) })
                appendLine(Json.encodeToString(JsonElement.serializer(), arr))
            }.trimEnd()

        val raw =
            try {
                OpenAgenticSdk.run(prompt = userPrompt, options = options).finalText.trim()
            } catch (t: Throwable) {
                appendLog(entry, "auto_forward triage failed(${t::class.java.simpleName}): ${t.message ?: "unknown"}")
                return emptyList()
            }
        val jsonText = extractFirstJsonObject(raw) ?: run {
            appendLog(entry, "auto_forward triage invalid_json: ${raw.take(120)}")
            return emptyList()
        }

        val decisions =
            try {
                val root = Json.parseToJsonElement(jsonText).jsonObject
                val arr = root["decisions"]?.jsonArray ?: return emptyList()
                arr.mapNotNull { el ->
                    val obj = el.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val needs = obj["needs_human"]?.jsonPrimitive?.booleanOrNull ?: false
                    val question = obj["question"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (id.isBlank()) null else TriageDecision(id = id, needsHuman = needs, question = question)
                }
            } catch (t: Throwable) {
                appendLog(entry, "auto_forward triage parse failed(${t::class.java.simpleName}): ${t.message ?: "unknown"}")
                emptyList()
            }

        val byId = batch.associateBy { it.id }
        return decisions
            .asSequence()
            .filter { it.needsHuman && it.question.isNotBlank() }
            .mapNotNull { d ->
                val m = byId[d.id] ?: return@mapNotNull null
                IrcHumanQuestion(
                    inboundId = m.id,
                    tsMs = m.tsMs,
                    channel = m.channel,
                    nick = m.nick,
                    inboundText = m.text.replace("\u0000", "").replace("\r\n", "\n").trim().take(8000),
                    question = d.question.take(400),
                )
            }
            .toList()
    }

    private fun triageSessionIdFor(sessionKey: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(("irc_triage:" + sessionKey.trim()).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { b -> "%02x".format(b) }
    }

    private fun triageSystemPromptHookEngine(
        marker: String,
        systemPrompt: String,
    ): HookEngine {
        return HookEngine(
            enableMessageRewriteHooks = true,
            beforeModelCall =
                listOf(
                    HookMatcher(
                        name = "irc_triage.system_prompt",
                        hook = { payload ->
                            val arr = payload["input"] as? JsonArray
                            val current = arr?.mapNotNull { it as? JsonObject }.orEmpty()
                            val alreadyInjected = current.firstOrNull()?.let { first ->
                                val role = (first["role"] as? JsonPrimitive)?.content?.trim().orEmpty()
                                val content = (first["content"] as? JsonPrimitive)?.content?.trim().orEmpty()
                                role == "system" && content.contains(marker)
                            } == true
                            if (alreadyInjected) {
                                HookDecision(action = "system prompt already present")
                            } else {
                                val sys =
                                    buildJsonObject {
                                        put("role", JsonPrimitive("system"))
                                        put("content", JsonPrimitive("$marker\n$systemPrompt"))
                                    }
                                HookDecision(
                                    overrideModelInput = listOf(sys) + current,
                                    action = "prepended triage system prompt",
                                )
                            }
                        },
                    ),
                ),
        )
    }

    private fun extractFirstJsonObject(text: String): String? {
        val s = text.trim()
        if (s.isEmpty()) return null
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return s.substring(start, end + 1).trim()
    }
}
