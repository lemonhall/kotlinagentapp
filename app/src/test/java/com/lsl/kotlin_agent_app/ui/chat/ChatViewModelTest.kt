package com.lsl.kotlin_agent_app.ui.chat

import com.lsl.kotlin_agent_app.MainDispatcherRule
import com.lsl.kotlin_agent_app.agent.ChatAgent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import me.lemonhall.openagentic.sdk.providers.ModelOutput
import me.lemonhall.openagentic.sdk.runtime.ProviderStreamEvent
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
                override fun streamReply(conversation: List<ChatMessage>): Flow<ProviderStreamEvent> =
                    flow {
                        emit(ProviderStreamEvent.TextDelta("A"))
                        emit(ProviderStreamEvent.TextDelta("B"))
                        emit(
                            ProviderStreamEvent.Completed(
                                ModelOutput(
                                    assistantText = "AB",
                                    toolCalls = emptyList(),
                                ),
                            ),
                        )
                    }

                override fun clearSession() = Unit
            }

        val vm = ChatViewModel(agent)
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
                override fun streamReply(conversation: List<ChatMessage>): Flow<ProviderStreamEvent> {
                    throw IllegalArgumentException("api_key 未配置")
                }

                override fun clearSession() = Unit
            }

        val vm = ChatViewModel(agent)
        vm.sendUserMessage("hi")

        assertFalse(vm.uiState.value.isSending)
        assertNotNull(vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.errorMessage!!.contains("api_key"))
    }

    @Test
    fun sendUserMessage_ignoresBlankInput() = runTest {
        val agent =
            object : ChatAgent {
                override fun streamReply(conversation: List<ChatMessage>): Flow<ProviderStreamEvent> = flow { }

                override fun clearSession() = Unit
            }

        val vm = ChatViewModel(agent)
        vm.sendUserMessage("   ")

        assertEquals(0, vm.uiState.value.messages.size)
        assertEquals(0, vm.uiState.value.toolTraces.size)
    }

    @Test
    fun clearConversation_clearsUiStateAndResetsSession() = runTest {
        var cleared = false
        val agent =
            object : ChatAgent {
                override fun streamReply(conversation: List<ChatMessage>): Flow<ProviderStreamEvent> =
                    flow {
                        emit(ProviderStreamEvent.TextDelta("X"))
                        emit(ProviderStreamEvent.Completed(ModelOutput(assistantText = "X", toolCalls = emptyList())))
                    }

                override fun clearSession() {
                    cleared = true
                }
            }

        val vm = ChatViewModel(agent)
        vm.sendUserMessage("hi")
        assertEquals(2, vm.uiState.value.messages.size)

        vm.clearConversation()

        assertTrue(cleared)
        assertEquals(0, vm.uiState.value.messages.size)
        assertEquals(0, vm.uiState.value.toolTraces.size)
        assertFalse(vm.uiState.value.isSending)
    }
}
