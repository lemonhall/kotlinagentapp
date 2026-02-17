package com.lsl.kotlin_agent_app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lsl.kotlin_agent_app.agent.ChatAgent
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

class ChatViewModel(
    private val agent: ChatAgent,
    private val agentDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var activeSendJob: Job? = null
    private val toolNameByUseId = linkedMapOf<String, String>()
    private var lastWebviewTaskAnswer: String? = null

    fun sendUserMessage(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return

        if (activeSendJob?.isActive == true) return
        lastWebviewTaskAnswer = null

        val userMessage = ChatMessage(role = ChatRole.User, content = text)
        val assistantMessage = ChatMessage(role = ChatRole.Assistant, content = "")
        _uiState.value =
            _uiState.value.copy(
                isSending = true,
                errorMessage = null,
                messages = _uiState.value.messages + userMessage + assistantMessage,
                toolTraces = _uiState.value.toolTraces + ToolTraceEvent(name = "agent.run", summary = "start"),
            )

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
        agent.clearSession()
        toolNameByUseId.clear()
        lastWebviewTaskAnswer = null
        _uiState.value = ChatUiState()
    }

    fun stopSending() {
        if (activeSendJob?.isActive != true) return
        activeSendJob?.cancel()
        activeSendJob = null
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
                captureWebviewTaskAnswer(toolName = toolName, output = ev.output)
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
                lastWebviewTaskAnswer = null
                _uiState.value =
                    _uiState.value.copy(
                        isSending = false,
                        toolTraces = _uiState.value.toolTraces + ToolTraceEvent(name = "agent.run", summary = summary),
                    )
            }

            else -> Unit
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

    private fun appendAssistantDelta(
        assistantMessageId: String,
        delta: String,
    ) {
        val st = _uiState.value
        val idx = st.messages.indexOfFirst { it.id == assistantMessageId }
        if (idx < 0) return
        val msg = st.messages[idx]
        _uiState.value = st.copy(messages = st.messages.toMutableList().also { it[idx] = msg.copy(content = msg.content + delta) })
    }

    private fun setAssistantContent(
        assistantMessageId: String,
        content: String,
    ) {
        val st = _uiState.value
        val idx = st.messages.indexOfFirst { it.id == assistantMessageId }
        if (idx < 0) return
        val msg = st.messages[idx]
        _uiState.value = st.copy(messages = st.messages.toMutableList().also { it[idx] = msg.copy(content = content) })
    }
}
