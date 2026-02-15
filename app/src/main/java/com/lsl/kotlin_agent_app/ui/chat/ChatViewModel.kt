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
import me.lemonhall.openagentic.sdk.runtime.ProviderStreamEvent

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
        val requestConversation = _uiState.value.messages + userMessage

        _uiState.value =
            _uiState.value.copy(
                isSending = true,
                errorMessage = null,
                messages = _uiState.value.messages + userMessage + assistantMessage,
                toolTraces = _uiState.value.toolTraces + ToolTraceEvent(name = "openai-responses.stream", summary = "start"),
            )

        activeSendJob =
            viewModelScope.launch {
                try {
                    agent.streamReply(requestConversation).collect { ev ->
                        when (ev) {
                            is ProviderStreamEvent.TextDelta -> {
                                appendAssistantDelta(assistantMessageId = assistantMessage.id, delta = ev.delta)
                            }

                            is ProviderStreamEvent.Completed -> {
                                val finalText = ev.output.assistantText
                                if (!finalText.isNullOrBlank()) {
                                    setAssistantContent(assistantMessageId = assistantMessage.id, content = finalText)
                                }
                                _uiState.value =
                                    _uiState.value.copy(
                                        isSending = false,
                                        toolTraces = _uiState.value.toolTraces + ToolTraceEvent(name = "openai-responses.stream", summary = "completed"),
                                    )
                            }

                            is ProviderStreamEvent.Failed -> {
                                _uiState.value =
                                    _uiState.value.copy(
                                        isSending = false,
                                        errorMessage = ev.message,
                                        toolTraces = _uiState.value.toolTraces + ToolTraceEvent(name = "openai-responses.stream", summary = "failed"),
                                    )
                            }
                        }
                    }
                } catch (t: Throwable) {
                    _uiState.value =
                        _uiState.value.copy(
                            isSending = false,
                            errorMessage = t.message ?: t.toString(),
                            toolTraces = _uiState.value.toolTraces + ToolTraceEvent(name = "openai-responses.stream", summary = "exception"),
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
