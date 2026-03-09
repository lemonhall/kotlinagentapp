package com.lsl.kotlin_agent_app.voiceinput

import com.lsl.kotlin_agent_app.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceInputControllerTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun start_partialAndFinalTranscriptsUpdateDraft() = runTest {
        val engine = FakeVoiceInputEngine()
        val controller = VoiceInputController(engineFactory = { engine })
        val drafts = mutableListOf<String>()

        controller.start(initialDraft = "帮我", onDraftChanged = { drafts += it })

        assertTrue(controller.state.value.isRecording)
        engine.emitPartial("打开设置")
        assertEquals("帮我打开设置", drafts.last())

        engine.emitFinal("打开设置。")
        assertEquals("帮我打开设置。", drafts.last())

        engine.emitStopped()
        assertFalse(controller.state.value.isRecording)
    }

    @Test
    fun start_whenEngineThrows_setsErrorState() = runTest {
        val controller =
            VoiceInputController(
                engineFactory = { error("missing api key") },
            )

        controller.start(initialDraft = "", onDraftChanged = { })

        assertFalse(controller.state.value.isRecording)
        assertTrue(controller.state.value.errorMessage?.contains("missing api key") == true)
    }

    private class FakeVoiceInputEngine : VoiceInputEngine {
        private var listener: VoiceInputListener? = null

        override fun start(listener: VoiceInputListener) {
            this.listener = listener
            listener.onListening()
        }

        override fun stop() {
            listener?.onStopped()
        }

        fun emitPartial(text: String) {
            listener?.onPartialTranscript(text)
        }

        fun emitFinal(text: String) {
            listener?.onFinalTranscript(text)
        }

        fun emitStopped() {
            listener?.onStopped()
        }
    }
}
