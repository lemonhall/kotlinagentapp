package com.lsl.kotlin_agent_app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatViewModelTest {
    @Test
    fun sendUserMessage_appendsUserAndAssistantMessages() {
        val viewModel = ChatViewModel()

        viewModel.sendUserMessage("hi")

        val messages = viewModel.uiState.value.messages
        assertEquals(2, messages.size)
        assertEquals(ChatRole.User, messages[0].role)
        assertEquals("hi", messages[0].content)
        assertEquals(ChatRole.Assistant, messages[1].role)
        assertTrue(messages[1].content.contains("Echo: hi"))
    }

    @Test
    fun sendUserMessage_ignoresBlankInput() {
        val viewModel = ChatViewModel()

        viewModel.sendUserMessage("   ")

        assertEquals(0, viewModel.uiState.value.messages.size)
        assertEquals(0, viewModel.uiState.value.toolTraces.size)
    }
}

