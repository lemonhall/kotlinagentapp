package com.lsl.kotlin_agent_app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lsl.kotlin_agent_app.agent.ChatAgent
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var activeSendJob: Job? = null

    fun sendUserMessage(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return

        if (activeSendJob?.isActive == true) return

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
                    agent.streamReply(prompt = text).collect { ev -> handleEvent(ev, assistantMessageId = assistantMessage.id) }
                } catch (t: Throwable) {
                    _uiState.value =
                        _uiState.value.copy(
                            isSending = false,
                            errorMessage = t.message ?: t.toString(),
                            toolTraces = _uiState.value.toolTraces + ToolTraceEvent(name = "agent.run", summary = "exception"),
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
        _uiState.value = ChatUiState()
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
                val inputSummary = summarizeJson(ev.input)
                val summary =
                    if (inputSummary.isBlank()) "call_id=${ev.toolUseId}" else "call_id=${ev.toolUseId} $inputSummary"
                _uiState.value =
                    _uiState.value.copy(
                        toolTraces = _uiState.value.toolTraces + ToolTraceEvent(name = ev.name.ifBlank { "Tool" }, summary = summary),
                    )
            }

            is ToolResult -> {
                val summary =
                    if (ev.isError) {
                        listOfNotNull(ev.errorType, ev.errorMessage).joinToString(": ").ifBlank { "error" }
                    } else {
                        "ok"
                    }
                _uiState.value =
                    _uiState.value.copy(
                        toolTraces = _uiState.value.toolTraces + ToolTraceEvent(name = "↳ ${ev.toolUseId}", summary = summary),
                    )
            }

            is HookEvent -> {
                val sum = listOfNotNull(ev.name.takeIf { it.isNotBlank() }, ev.action).joinToString(" ").ifBlank { "hook" }
                _uiState.value =
                    _uiState.value.copy(
                        toolTraces = _uiState.value.toolTraces + ToolTraceEvent(name = "Hook:${ev.hookPoint}", summary = sum),
                    )
            }

            is RuntimeError -> {
                _uiState.value =
                    _uiState.value.copy(
                        errorMessage = ev.errorMessage ?: ev.errorType,
                        toolTraces = _uiState.value.toolTraces + ToolTraceEvent(name = "runtime.error", summary = ev.errorType),
                    )
            }

            is Result -> {
                val summary = ev.stopReason ?: "end"
                val finalText = ev.finalText
                if (finalText.isNotBlank()) setAssistantContent(assistantMessageId = assistantMessageId, content = finalText)
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
