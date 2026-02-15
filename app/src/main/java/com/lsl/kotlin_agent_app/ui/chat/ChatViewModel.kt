package com.lsl.kotlin_agent_app.ui.chat

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChatViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    fun sendUserMessage(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return

        val userMessage = ChatMessage(role = ChatRole.User, content = text)

        _uiState.value = _uiState.value.copy(
            isSending = true,
            errorMessage = null,
            messages = _uiState.value.messages + userMessage,
        )

        val assistantMessage = ChatMessage(role = ChatRole.Assistant, content = "Echo: $text")
        val traceEvent = ToolTraceEvent(name = "mock.echo", summary = "echo user input")

        _uiState.value = _uiState.value.copy(
            isSending = false,
            messages = _uiState.value.messages + assistantMessage,
            toolTraces = _uiState.value.toolTraces + traceEvent,
        )
    }

    fun clearError() {
        if (_uiState.value.errorMessage == null) return
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}

