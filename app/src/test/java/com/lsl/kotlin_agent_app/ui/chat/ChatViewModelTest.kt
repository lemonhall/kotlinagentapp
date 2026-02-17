package com.lsl.kotlin_agent_app.ui.chat

import com.lsl.kotlin_agent_app.MainDispatcherRule
import com.lsl.kotlin_agent_app.agent.ChatAgent
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.events.AssistantDelta
import me.lemonhall.openagentic.sdk.events.AssistantMessage
import me.lemonhall.openagentic.sdk.events.Event
import me.lemonhall.openagentic.sdk.events.Result
import me.lemonhall.openagentic.sdk.events.SystemInit
import me.lemonhall.openagentic.sdk.events.ToolUse
import me.lemonhall.openagentic.sdk.events.UserMessage
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import okio.FileSystem
import okio.Path.Companion.toPath
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

    private val files =
        object : AgentsFiles {
            override fun ensureInitialized() = Unit

            override fun readTextFile(path: String, maxBytes: Long): String = ""
        }

    private val storeRootDir: String = Files.createTempDirectory("agents-root-").toFile().absolutePath

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

        val vm =
            ChatViewModel(
                agent = agent,
                files = files,
                getActiveSessionId = { null },
                storeRootDir = storeRootDir,
                agentDispatcher = Dispatchers.Main,
            )
        vm.sendUserMessage("hi")

        assertEquals(2, vm.uiState.value.messages.size)
        assertEquals(ChatRole.User, vm.uiState.value.messages[0].role)
        assertEquals("hi", vm.uiState.value.messages[0].content)
        assertEquals(ChatRole.Assistant, vm.uiState.value.messages[1].role)

        val assistantText = vm.uiState.value.messages[1].content
        assertEquals("AB", assistantText)
        assertEquals(null, vm.uiState.value.messages[1].statusLine)
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

        val vm =
            ChatViewModel(
                agent = agent,
                files = files,
                getActiveSessionId = { null },
                storeRootDir = storeRootDir,
                agentDispatcher = Dispatchers.Main,
            )
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

        val vm =
            ChatViewModel(
                agent = agent,
                files = files,
                getActiveSessionId = { null },
                storeRootDir = storeRootDir,
                agentDispatcher = Dispatchers.Main,
            )
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

        val vm =
            ChatViewModel(
                agent = agent,
                files = files,
                getActiveSessionId = { null },
                storeRootDir = storeRootDir,
                agentDispatcher = Dispatchers.Main,
            )
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

        val vm =
            ChatViewModel(
                agent = agent,
                files = files,
                getActiveSessionId = { null },
                storeRootDir = storeRootDir,
                agentDispatcher = Dispatchers.Main,
            )
        vm.sendUserMessage("hi")
        runCurrent()
        assertTrue(vm.uiState.value.isSending)

        vm.stopSending()
        runCurrent()

        assertFalse(vm.uiState.value.isSending)
        assertTrue(vm.uiState.value.toolTraces.any { it.summary.contains("canceled") })
    }

    @Test
    fun sendUserMessage_toolUseUpdatesStatusLine() = runTest {
        val agent =
            object : ChatAgent {
                override fun streamReply(prompt: String): Flow<Event> =
                    flow {
                        emit(
                            ToolUse(
                                toolUseId = "t1",
                                name = "WebSearch",
                                input = buildJsonObject { put("query", JsonPrimitive("kotlin flow merge")) },
                            ),
                        )
                        delay(60_000)
                    }

                override fun clearSession() = Unit
            }

        val vm =
            ChatViewModel(
                agent = agent,
                files = files,
                getActiveSessionId = { null },
                storeRootDir = storeRootDir,
                agentDispatcher = Dispatchers.Main,
            )
        vm.sendUserMessage("hi")
        runCurrent()

        val assistant = vm.uiState.value.messages.last()
        assertEquals(ChatRole.Assistant, assistant.role)
        assertTrue(assistant.statusLine?.contains("搜索") == true)
        assertTrue(vm.uiState.value.isSending)

        vm.stopSending()
        runCurrent()
        assertFalse(vm.uiState.value.isSending)
    }

    @Test
    fun syncSessionHistoryIfNeeded_replaysUserAndAssistant_withoutDuplicatingResult() = runTest {
        val rootNio = Files.createTempDirectory("openagentic-test-")
        val root = rootNio.toString().replace('\\', '/').toPath()
        val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
        val sid = store.createSession()

        store.appendEvent(sid, SystemInit(sessionId = sid, cwd = "/x", sdkVersion = "0.0.0"))
        store.appendEvent(sid, UserMessage(text = "hi"))
        store.appendEvent(sid, AssistantMessage(text = "ok"))
        store.appendEvent(sid, Result(finalText = "ok", sessionId = sid))

        val agent =
            object : ChatAgent {
                override fun streamReply(prompt: String): Flow<Event> = flow { }
                override fun clearSession() = Unit
            }

        val vm =
            ChatViewModel(
                agent = agent,
                files = files,
                getActiveSessionId = { sid },
                storeRootDir = rootNio.toFile().absolutePath,
                agentDispatcher = Dispatchers.Main,
            )

        vm.syncSessionHistoryIfNeeded(force = true)
        runCurrent()

        assertEquals(2, vm.uiState.value.messages.size)
        assertEquals(ChatRole.User, vm.uiState.value.messages[0].role)
        assertEquals("hi", vm.uiState.value.messages[0].content)
        assertEquals(ChatRole.Assistant, vm.uiState.value.messages[1].role)
        assertEquals("ok", vm.uiState.value.messages[1].content)
    }
}
