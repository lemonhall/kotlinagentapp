package com.lsl.kotlin_agent_app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lsl.kotlin_agent_app.agent.ChatAgent
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.events.AssistantDelta
import me.lemonhall.openagentic.sdk.events.AssistantMessage
import me.lemonhall.openagentic.sdk.events.Event
import me.lemonhall.openagentic.sdk.events.HookEvent
import me.lemonhall.openagentic.sdk.events.Result
import me.lemonhall.openagentic.sdk.events.RuntimeError
import me.lemonhall.openagentic.sdk.events.ToolResult
import me.lemonhall.openagentic.sdk.events.ToolUse
import me.lemonhall.openagentic.sdk.events.UserMessage
import me.lemonhall.openagentic.sdk.events.UserQuestion
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore

interface AgentsFiles {
    fun ensureInitialized()

    fun readTextFile(
        path: String,
        maxBytes: Long,
    ): String
}

class ChatViewModel(
    private val agent: ChatAgent,
    private val files: AgentsFiles,
    private val getActiveSessionId: () -> String?,
    private val storeRootDir: String,
    private val agentDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var activeSendJob: Job? = null
    private var activeHistoryJob: Job? = null
    private var activeStatusTickerJob: Job? = null
    private var activeAssistantMessageId: String? = null
    private var loadedSessionId: String? = null
    private var statusStartedAtMs: Long = 0L
    private var statusStep: Int = 0
    private var statusBase: String? = null
    private val toolNameByUseId = linkedMapOf<String, String>()
    private var lastWebviewTaskAnswer: String? = null
    private var lastDeepResearchReportLink: ReportLink? = null

    fun syncSessionHistoryIfNeeded(force: Boolean = false) {
        val sid = getActiveSessionId.invoke()?.trim()?.ifEmpty { null }
        val sending = (activeSendJob?.isActive == true)
        val st0 = _uiState.value

        // If there's no active session id yet (e.g. first request before SystemInit),
        // never clobber an in-flight UI.
        if (sid == null) {
            if (sending || st0.messages.isNotEmpty()) return
            loadedSessionId = null
            _uiState.value = ChatUiState()
            return
        }

        if (!force && sid == loadedSessionId) return
        if (!force && sending) return

        activeHistoryJob?.cancel()
        activeHistoryJob = null

        activeHistoryJob =
            viewModelScope.launch {
                try {
                    withContext(agentDispatcher) { files.ensureInitialized() }
                    val store = FileSessionStore.system(storeRootDir.replace('\\', '/').trim())
                    val events = withContext(agentDispatcher) { store.readEvents(sid) }
                    val messages = replayMessagesFromEvents(events, maxMessages = 120)
                    loadedSessionId = sid
                    _uiState.value = ChatUiState(messages = messages)
                } catch (t: Throwable) {
                    _uiState.value = _uiState.value.copy(errorMessage = t.message ?: t.toString())
                }
            }
    }

    fun sendUserMessage(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return

        if (activeSendJob?.isActive == true) return
        lastWebviewTaskAnswer = null
        lastDeepResearchReportLink = null

        val userMessage = ChatMessage(role = ChatRole.User, content = text)
        val assistantMessage = ChatMessage(role = ChatRole.Assistant, content = "")
        activeAssistantMessageId = assistantMessage.id
        statusStartedAtMs = System.currentTimeMillis()
        statusStep = 0
        statusBase = null
        _uiState.value =
            _uiState.value.copy(
                isSending = true,
                errorMessage = null,
                messages = _uiState.value.messages + userMessage + assistantMessage,
                toolTraces = _uiState.value.toolTraces + ToolTraceEvent(name = "agent.run", summary = "start"),
            )

        setStatusBase(assistantMessageId = assistantMessage.id, base = "准备中")
        startStatusTicker(assistantMessageId = assistantMessage.id)

        activeSendJob =
            viewModelScope.launch {
                try {
                    agent.streamReply(prompt = text)
                        .flowOn(agentDispatcher)
                        .collect { ev -> handleEvent(ev, assistantMessageId = assistantMessage.id) }
                } catch (t: Throwable) {
                    _uiState.value =
                        _uiState.value.copy(
                            isSending = false,
                            errorMessage = t.message ?: t.toString(),
                            toolTraces =
                                _uiState.value.toolTraces +
                                    ToolTraceEvent(
                                        name = "agent.run",
                                        summary = "exception",
                                        details = t.stackTraceToString().ifBlank { t.message ?: t.toString() },
                                        isError = true,
                                    ),
                        )
                }
            }
    }

    fun clearError() {
        if (_uiState.value.errorMessage == null) return
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun clearConversation() {
        activeSendJob?.cancel()
        activeSendJob = null
        activeHistoryJob?.cancel()
        activeHistoryJob = null
        activeStatusTickerJob?.cancel()
        activeStatusTickerJob = null
        activeAssistantMessageId = null
        loadedSessionId = null
        statusBase = null
        agent.clearSession()
        toolNameByUseId.clear()
        lastWebviewTaskAnswer = null
        lastDeepResearchReportLink = null
        _uiState.value = ChatUiState()
    }

    fun openReportViewer(path: String) {
        val normalized = normalizeAgentsPathFromReportPath(path)
        if (normalized == null) {
            _uiState.value =
                _uiState.value.copy(
                    reportViewerPath = path,
                    reportViewerText = null,
                    reportViewerError = "无法识别报告路径：$path",
                    isReportViewerLoading = false,
                )
            return
        }
        _uiState.value =
            _uiState.value.copy(
                reportViewerPath = normalized,
                reportViewerText = null,
                reportViewerError = null,
                isReportViewerLoading = true,
            )
        viewModelScope.launch {
            try {
                val text =
                    withContext(Dispatchers.IO) {
                        files.ensureInitialized()
                        files.readTextFile(normalized, maxBytes = 512 * 1024)
                    }
                val now = _uiState.value
                _uiState.value =
                    now.copy(
                        reportViewerPath = normalized,
                        reportViewerText = text,
                        reportViewerError = null,
                        isReportViewerLoading = false,
                    )
            } catch (t: Throwable) {
                val now = _uiState.value
                _uiState.value =
                    now.copy(
                        reportViewerPath = normalized,
                        reportViewerText = null,
                        reportViewerError = t.message ?: "Open failed",
                        isReportViewerLoading = false,
                    )
            }
        }
    }

    fun closeReportViewer() {
        val st = _uiState.value
        if (st.reportViewerPath == null && st.reportViewerText == null && st.reportViewerError == null) return
        _uiState.value =
            st.copy(
                reportViewerPath = null,
                reportViewerText = null,
                reportViewerError = null,
                isReportViewerLoading = false,
            )
    }

    fun stopSending() {
        if (activeSendJob?.isActive != true) return
        activeSendJob?.cancel()
        activeSendJob = null
        activeStatusTickerJob?.cancel()
        activeStatusTickerJob = null
        activeAssistantMessageId?.let { id ->
            val elapsed = System.currentTimeMillis() - statusStartedAtMs
            setAssistantStatusLine(assistantMessageId = id, statusLine = "已取消\n（${formatElapsedMs(elapsed)}）")
        }
        activeAssistantMessageId = null
        statusBase = null
        _uiState.value =
            _uiState.value.copy(
                isSending = false,
                toolTraces = _uiState.value.toolTraces + ToolTraceEvent(name = "agent.run", summary = "canceled"),
            )
    }

    private fun handleEvent(
        ev: Event,
        assistantMessageId: String,
    ) {
        ensureInFlightUiIfNeeded(assistantMessageId = assistantMessageId)
        when (ev) {
            is AssistantDelta -> {
                val delta = ev.textDelta
                if (delta.isNotEmpty()) appendAssistantDelta(assistantMessageId = assistantMessageId, delta = delta)
            }

            is AssistantMessage -> {
                val full = ev.text
                if (full.isNotBlank()) setAssistantContent(assistantMessageId = assistantMessageId, content = full)
            }

            is ToolUse -> {
                toolNameByUseId[ev.toolUseId] = ev.name.ifBlank { "Tool" }
                setStatusBase(assistantMessageId = assistantMessageId, base = humanizeToolUse(ev.name, ev.input))
                val inputSummary = summarizeJson(ev.input)
                val summary =
                    if (inputSummary.isBlank()) "call_id=${ev.toolUseId}" else "call_id=${ev.toolUseId} $inputSummary"
                _uiState.value =
                    _uiState.value.copy(
                        toolTraces =
                            _uiState.value.toolTraces +
                                ToolTraceEvent(
                                    name = ev.name.ifBlank { "Tool" },
                                    summary = summary,
                                    details = prettyJson(ev.input),
                                ),
                    )
            }

            is ToolResult -> {
                val toolName = toolNameByUseId[ev.toolUseId]
                val summary =
                    if (ev.isError) {
                        listOfNotNull(ev.errorType, ev.errorMessage).joinToString(": ").ifBlank { "error" }
                    } else {
                        "ok"
                    }
                if (ev.isError) {
                    setStatusBase(assistantMessageId = assistantMessageId, base = "工具失败：${toolName ?: ev.toolUseId}")
                }
                captureWebviewTaskAnswer(toolName = toolName, output = ev.output)
                captureDeepResearchReport(toolName = toolName, output = ev.output, assistantMessageId = assistantMessageId)
                _uiState.value =
                    _uiState.value.copy(
                        toolTraces =
                            _uiState.value.toolTraces +
                                ToolTraceEvent(
                                    name = "↳ ${toolName ?: "Tool"} (${ev.toolUseId})",
                                    summary = summary,
                                    details = buildToolResultDetails(ev),
                                    isError = ev.isError,
                                ),
                    )
            }

            is HookEvent -> {
                if (ev.hookPoint == "TaskProgress") {
                    val action = ev.action?.trim().orEmpty()
                    if (action.isNotBlank()) setStatusBase(assistantMessageId = assistantMessageId, base = action)
                    return
                }

                val sum = listOfNotNull(ev.name.takeIf { it.isNotBlank() }, ev.action).joinToString(" ").ifBlank { "hook" }
                _uiState.value =
                    _uiState.value.copy(
                        toolTraces =
                            _uiState.value.toolTraces +
                                ToolTraceEvent(
                                    name = "Hook:${ev.hookPoint}",
                                    summary = sum,
                                    details =
                                        listOfNotNull(
                                            ev.name.takeIf { it.isNotBlank() },
                                            ev.action,
                                            ev.errorType?.let { "error_type=$it" },
                                            ev.errorMessage?.let { "error_message=$it" },
                                        ).joinToString("\n").ifBlank { null },
                                    isError = ev.errorType != null || ev.errorMessage != null,
                                ),
                    )
            }

            is RuntimeError -> {
                setStatusBase(assistantMessageId = assistantMessageId, base = "运行错误：${ev.errorType.ifBlank { "error" }}")
                _uiState.value =
                    _uiState.value.copy(
                        errorMessage = ev.errorMessage ?: ev.errorType,
                        toolTraces =
                            _uiState.value.toolTraces +
                                ToolTraceEvent(
                                    name = "runtime.error",
                                    summary = ev.errorType,
                                    details = listOfNotNull(ev.errorType, ev.errorMessage).joinToString("\n").ifBlank { null },
                                    isError = true,
                                ),
                    )
            }

            is Result -> {
                val summary = ev.stopReason ?: "end"
                val finalText = ev.finalText
                val fallback = lastWebviewTaskAnswer?.trim().orEmpty()
                if (fallback.isNotBlank() && isLikelyPointerOnlyResponse(finalText)) {
                    setAssistantContent(assistantMessageId = assistantMessageId, content = fallback)
                } else if (finalText.isNotBlank()) {
                    setAssistantContent(assistantMessageId = assistantMessageId, content = finalText)
                }
                setAssistantStatusLine(assistantMessageId = assistantMessageId, statusLine = null)
                lastWebviewTaskAnswer = null
                lastDeepResearchReportLink = null
                activeStatusTickerJob?.cancel()
                activeStatusTickerJob = null
                activeAssistantMessageId = null
                statusBase = null
                _uiState.value =
                    _uiState.value.copy(
                        isSending = false,
                        toolTraces = _uiState.value.toolTraces + ToolTraceEvent(name = "agent.run", summary = summary),
                    )
            }

            else -> Unit
        }
    }

    private fun startStatusTicker(assistantMessageId: String) {
        activeStatusTickerJob?.cancel()
        activeStatusTickerJob =
            viewModelScope.launch {
                while (_uiState.value.isSending && activeAssistantMessageId == assistantMessageId) {
                    val base = statusBase
                    if (!base.isNullOrBlank()) {
                        setAssistantStatusLine(
                            assistantMessageId = assistantMessageId,
                            statusLine = buildStatusLine(base = base, step = statusStep),
                        )
                    }
                    delay(1_000)
                }
            }
    }

    private fun setStatusBase(
        assistantMessageId: String,
        base: String,
    ) {
        val b = base.trim().takeIf { it.isNotEmpty() } ?: return
        if (statusBase == b) {
            setAssistantStatusLine(assistantMessageId = assistantMessageId, statusLine = buildStatusLine(base = b, step = statusStep))
            return
        }
        statusBase = b
        statusStep += 1
        setAssistantStatusLine(assistantMessageId = assistantMessageId, statusLine = buildStatusLine(base = b, step = statusStep))
    }

    private fun buildStatusLine(
        base: String,
        step: Int,
    ): String {
        val elapsed = System.currentTimeMillis() - statusStartedAtMs
        val stepPrefix = if (step > 0) "步骤$step " else ""
        return "$stepPrefix$base\n（${formatElapsedMs(elapsed)}）"
    }

    private fun formatElapsedMs(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0) / 1000).toInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "%dh %02dm %02ds".format(h, m, s)
            m > 0 -> "%dm %02ds".format(m, s)
            else -> "%ds".format(s)
        }
    }

    private fun setAssistantStatusLine(
        assistantMessageId: String,
        statusLine: String?,
    ) {
        var st = _uiState.value
        var idx = st.messages.indexOfFirst { it.id == assistantMessageId }
        if (idx < 0) {
            ensureAssistantMessageExists(assistantMessageId)
            st = _uiState.value
            idx = st.messages.indexOfFirst { it.id == assistantMessageId }
        }
        if (idx < 0) return
        val msg = st.messages[idx]
        if (msg.role != ChatRole.Assistant) return
        if (msg.statusLine == statusLine) return
        val newMsg = msg.copy(statusLine = statusLine)
        val newMessages = st.messages.toMutableList()
        newMessages[idx] = newMsg
        _uiState.value = st.copy(messages = newMessages)
    }

    private fun humanizeToolUse(
        name: String,
        input: JsonObject?,
    ): String {
        val n = name.trim()
        fun str(key: String): String = (input?.get(key) as? JsonPrimitive)?.content?.trim().orEmpty()
        return when (n) {
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
            else -> n.ifBlank { "处理中" }.take(40)
        }
    }

    private fun summarizeJson(obj: JsonObject?): String {
        if (obj == null) return ""
        if (obj.isEmpty()) return ""
        return obj.entries.joinToString(", ", prefix = "{", postfix = "}") { (k, v) ->
            val vv =
                when (v) {
                    is JsonPrimitive -> v.contentOrShort()
                    else -> v.toString().take(120)
                }
            "$k=$vv"
        }.take(220)
    }

    private fun JsonPrimitive.contentOrShort(): String {
        return if (this.isString) this.content.take(120) else this.content.take(120)
    }

    private fun buildToolResultDetails(ev: ToolResult): String? {
        val parts = mutableListOf<String>()
        if (ev.isError) {
            if (!ev.errorType.isNullOrBlank()) parts.add("error_type=${ev.errorType}")
            if (!ev.errorMessage.isNullOrBlank()) parts.add("error_message=${ev.errorMessage}")
        } else {
            parts.add("ok")
        }
        val out = prettyJson(ev.output)
        if (!out.isNullOrBlank()) {
            parts.add("output:")
            parts.add(out)
        }
        return parts.joinToString("\n").trim().ifBlank { null }?.take(6000)
    }

    private fun prettyJson(el: JsonElement?): String? {
        if (el == null) return null
        return try {
            el.toString()
        } catch (_: Throwable) {
            null
        }?.trim()?.ifBlank { null }?.take(6000)
    }

    private fun captureWebviewTaskAnswer(
        toolName: String?,
        output: JsonElement?,
    ) {
        if (toolName != "Task") return
        val obj = output as? JsonObject ?: return
        val agent = (obj["agent"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (agent != "webview") return
        val answer = (obj["answer"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (answer.isBlank()) return
        lastWebviewTaskAnswer = answer
    }

    private fun captureDeepResearchReport(
        toolName: String?,
        output: JsonElement?,
        assistantMessageId: String,
    ) {
        if (toolName != "Task") return
        val obj = output as? JsonObject ?: return
        val agent = (obj["agent"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (agent != "deep-research") return
        val rawPath = (obj["report_path"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (rawPath.isBlank()) return
        val normalized = normalizeAgentsPathFromReportPath(rawPath) ?: return
        val summary = (obj["report_summary"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }

        val link = ReportLink(path = normalized, summary = summary)
        lastDeepResearchReportLink = link

        val prev = _uiState.value
        val nextMap = (prev.reportLinksByMessageId + (assistantMessageId to link)).toMutableMap()
        // Keep memory bounded: keep only the latest 24 entries.
        if (nextMap.size > 24) {
            val keep = prev.messages.takeLast(24).map { it.id }.toSet()
            nextMap.keys.toList().forEach { k -> if (!keep.contains(k)) nextMap.remove(k) }
        }
        _uiState.value = prev.copy(reportLinksByMessageId = nextMap)
    }

    private fun isLikelyPointerOnlyResponse(rawText: String?): Boolean {
        val text = rawText?.trim().orEmpty()
        if (text.isBlank()) return true
        val lower = text.lowercase()
        if (lower == "(empty)") return true
        if (lower.startsWith("report_path:") && lower.contains("sessions/") && lower.contains("events.jsonl")) return true
        if (lower.startsWith("events_path:") && lower.contains("sessions/") && lower.contains("events.jsonl")) return true
        if (lower.matches(Regex("^sessions/[a-z0-9_-]+/events\\.jsonl$"))) return true
        if (lower.contains("events.jsonl") && lower.contains("sessions/") && text.length <= 160) return true
        return false
    }

    private fun normalizeAgentsPathFromReportPath(rawPath: String): String? {
        val p = rawPath.trim().replace('\\', '/')
        if (p.isBlank()) return null
        if (p.startsWith(".agents/") || p == ".agents") return p
        if (p.startsWith("artifacts/") || p.startsWith("sessions/") || p.startsWith("skills/")) return ".agents/$p"
        val idx = p.indexOf("/.agents/")
        if (idx >= 0) {
            val rel = p.substring(idx + "/.agents/".length).trimStart('/')
            if (rel.isBlank()) return null
            return ".agents/$rel"
        }
        return null
    }

    private fun appendAssistantDelta(
        assistantMessageId: String,
        delta: String,
    ) {
        var st = _uiState.value
        var idx = st.messages.indexOfFirst { it.id == assistantMessageId }
        if (idx < 0) {
            ensureAssistantMessageExists(assistantMessageId)
            st = _uiState.value
            idx = st.messages.indexOfFirst { it.id == assistantMessageId }
        }
        if (idx < 0) return
        val msg = st.messages[idx]
        _uiState.value = st.copy(messages = st.messages.toMutableList().also { it[idx] = msg.copy(content = msg.content + delta) })
    }

    private fun setAssistantContent(
        assistantMessageId: String,
        content: String,
    ) {
        var st = _uiState.value
        var idx = st.messages.indexOfFirst { it.id == assistantMessageId }
        if (idx < 0) {
            ensureAssistantMessageExists(assistantMessageId)
            st = _uiState.value
            idx = st.messages.indexOfFirst { it.id == assistantMessageId }
        }
        if (idx < 0) return
        val msg = st.messages[idx]
        _uiState.value = st.copy(messages = st.messages.toMutableList().also { it[idx] = msg.copy(content = content) })
    }

    private fun ensureInFlightUiIfNeeded(assistantMessageId: String) {
        if (activeSendJob?.isActive != true) return
        val st = _uiState.value
        if (!st.isSending) {
            _uiState.value = st.copy(isSending = true)
        }
        if (activeAssistantMessageId == assistantMessageId && activeStatusTickerJob?.isActive != true) {
            startStatusTicker(assistantMessageId = assistantMessageId)
        }
        ensureAssistantMessageExists(assistantMessageId)
    }

    private fun ensureAssistantMessageExists(assistantMessageId: String) {
        val st = _uiState.value
        if (st.messages.any { it.id == assistantMessageId }) return
        val msg = ChatMessage(id = assistantMessageId, role = ChatRole.Assistant, content = "")
        _uiState.value = st.copy(messages = st.messages + msg)
    }

    private fun replayMessagesFromEvents(
        events: List<Event>,
        maxMessages: Int,
    ): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        var lastAssistantText: String? = null
        for (e in events) {
            when (e) {
                is UserMessage -> out.add(ChatMessage(role = ChatRole.User, content = e.text.trim()))
                is UserQuestion -> out.add(ChatMessage(role = ChatRole.User, content = e.prompt.trim()))
                is AssistantMessage -> {
                    val text = e.text.trim()
                    if (text.isNotBlank()) {
                        lastAssistantText = text
                        out.add(ChatMessage(role = ChatRole.Assistant, content = text))
                    }
                }
                is RuntimeError -> {
                    val err = listOfNotNull(e.errorType.trim().ifBlank { null }, e.errorMessage?.trim()?.ifBlank { null }).joinToString(": ").trim()
                    if (err.isNotBlank()) out.add(ChatMessage(role = ChatRole.Assistant, content = "运行错误：$err"))
                }
                is Result -> {
                    val text = e.finalText.trim()
                    if (text.isNotBlank() && text != lastAssistantText) {
                        lastAssistantText = text
                        out.add(ChatMessage(role = ChatRole.Assistant, content = text))
                    }
                }
                else -> Unit
            }
        }
        val normalized =
            out
                .mapNotNull { m ->
                    val c = m.content.trim()
                    if (c.isBlank()) null else m.copy(content = c)
                }
        val limit = maxMessages.coerceAtLeast(1)
        return if (normalized.size <= limit) normalized else normalized.takeLast(limit)
    }
}
