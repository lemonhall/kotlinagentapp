package com.lsl.kotlin_agent_app.ui.chat

import com.lsl.kotlin_agent_app.MainDispatcherRule
import com.lsl.kotlin_agent_app.agent.ChatAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.lemonhall.openagentic.sdk.events.AssistantDelta
import me.lemonhall.openagentic.sdk.events.AssistantMessage
import me.lemonhall.openagentic.sdk.events.Event
import me.lemonhall.openagentic.sdk.events.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun sendUserMessage_streamingAppendsDeltaAndFinalText() = runTest {
        val agent =
            object : ChatAgent {
                override fun streamReply(prompt: String): Flow<Event> =
                    flow {
                        emit(AssistantDelta(textDelta = "A"))
                        emit(AssistantDelta(textDelta = "B"))
                        emit(AssistantMessage(text = "AB"))
                        emit(Result(finalText = "AB", sessionId = "s"))
                    }

                override fun clearSession() = Unit
            }

        val vm = ChatViewModel(agent, agentDispatcher = Dispatchers.Main)
        vm.sendUserMessage("hi")

        assertEquals(2, vm.uiState.value.messages.size)
        assertEquals(ChatRole.User, vm.uiState.value.messages[0].role)
        assertEquals("hi", vm.uiState.value.messages[0].content)
        assertEquals(ChatRole.Assistant, vm.uiState.value.messages[1].role)

        val assistantText = vm.uiState.value.messages[1].content
        assertEquals("AB", assistantText)
        assertFalse(vm.uiState.value.isSending)
    }

    @Test
    fun sendUserMessage_whenAgentThrows_setsErrorMessage() = runTest {
        val agent =
            object : ChatAgent {
                override fun streamReply(prompt: String): Flow<Event> {
                    throw IllegalArgumentException("api_key 未配置")
                }

                override fun clearSession() = Unit
            }

        val vm = ChatViewModel(agent, agentDispatcher = Dispatchers.Main)
        vm.sendUserMessage("hi")

        assertFalse(vm.uiState.value.isSending)
        assertNotNull(vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.errorMessage!!.contains("api_key"))
    }

    @Test
    fun sendUserMessage_ignoresBlankInput() = runTest {
        val agent =
            object : ChatAgent {
                override fun streamReply(prompt: String): Flow<Event> = flow { }

                override fun clearSession() = Unit
            }

        val vm = ChatViewModel(agent, agentDispatcher = Dispatchers.Main)
        vm.sendUserMessage("   ")

        assertEquals(0, vm.uiState.value.messages.size)
        assertEquals(0, vm.uiState.value.toolTraces.size)
    }

    @Test
    fun clearConversation_clearsUiStateAndResetsSession() = runTest {
        var cleared = false
        val agent =
            object : ChatAgent {
                override fun streamReply(prompt: String): Flow<Event> =
                    flow {
                        emit(AssistantDelta(textDelta = "X"))
                        emit(AssistantMessage(text = "X"))
                        emit(Result(finalText = "X", sessionId = "s"))
                    }

                override fun clearSession() {
                    cleared = true
                }
            }

        val vm = ChatViewModel(agent, agentDispatcher = Dispatchers.Main)
        vm.sendUserMessage("hi")
        assertEquals(2, vm.uiState.value.messages.size)

        vm.clearConversation()

        assertTrue(cleared)
        assertEquals(0, vm.uiState.value.messages.size)
        assertEquals(0, vm.uiState.value.toolTraces.size)
        assertFalse(vm.uiState.value.isSending)
    }

    @Test
    fun stopSending_cancelsActiveRequestAndUnblocksUi() = runTest {
        val agent =
            object : ChatAgent {
                override fun streamReply(prompt: String): Flow<Event> =
                    flow {
                        emit(AssistantDelta(textDelta = "A"))
                        delay(60_000)
                        emit(Result(finalText = "AB", sessionId = "s"))
                    }

                override fun clearSession() = Unit
            }

        val vm = ChatViewModel(agent, agentDispatcher = Dispatchers.Main)
        vm.sendUserMessage("hi")
        runCurrent()
        assertTrue(vm.uiState.value.isSending)

        vm.stopSending()
        runCurrent()

        assertFalse(vm.uiState.value.isSending)
        assertTrue(vm.uiState.value.toolTraces.any { it.summary.contains("canceled") })
    }
}
