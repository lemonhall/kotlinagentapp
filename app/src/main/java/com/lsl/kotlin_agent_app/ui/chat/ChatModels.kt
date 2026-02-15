package com.lsl.kotlin_agent_app.ui.chat

import java.util.UUID

enum class ChatRole {
    User,
    Assistant,
    Tool,
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val content: String,
    val timestampMs: Long = System.currentTimeMillis(),
)

data class ToolTraceEvent(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val summary: String,
    val details: String? = null,
    val isError: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis(),
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val toolTraces: List<ToolTraceEvent> = emptyList(),
    val isSending: Boolean = false,
    val errorMessage: String? = null,
)
