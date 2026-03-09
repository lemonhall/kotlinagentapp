package com.lsl.kotlin_agent_app.ui.simultaneous_interpretation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimultaneousInterpretationViewModelTest {
    @Test
    fun startSession_updatesStateFromControllerEvents() {
        val controller = FakeLiveTranslateSessionController()
        val vm =
            SimultaneousInterpretationViewModel(
                sessionController = controller,
                apiKeyProvider = { "sk-test" },
            )

        vm.startSession()

        assertEquals("en", controller.lastStartConfig?.targetLanguageCode)
        controller.listener?.onSessionStarted(
            sessionPath = ".agents/workspace/simultaneous_interpretation/2026年03月09日 晚22点01分",
            isHeadsetConnected = false,
        )
        controller.listener?.onSourceTranscriptCompleted("你好")
        controller.listener?.onTranslationDelta("Hello")
        controller.listener?.onSegmentFinal(sourceText = "你好", translatedText = "Hello")

        val state = vm.uiState.value
        assertTrue(state.isRunning)
        assertFalse(state.isHeadsetConnected)
        assertEquals("你好", state.sourcePreview)
        assertEquals("Hello", state.translatedPreview)
        assertEquals(1, state.segments.size)
        assertEquals("Hello", state.segments.single().translatedText)
    }

    @Test
    fun startSession_withoutApiKey_setsError() {
        val controller = FakeLiveTranslateSessionController()
        val vm =
            SimultaneousInterpretationViewModel(
                sessionController = controller,
                apiKeyProvider = { "" },
            )

        vm.startSession()

        assertEquals("请先在 Settings 的 Voice Input 中填写 DASHSCOPE_API_KEY", vm.uiState.value.errorMessage)
        assertEquals(null, controller.lastStartConfig)
    }
}

private class FakeLiveTranslateSessionController : LiveTranslateSessionController {
    var listener: LiveTranslateSessionListener? = null
    var lastStartConfig: LiveTranslateSessionStartConfig? = null
    var stopCount: Int = 0

    override fun bind(listener: LiveTranslateSessionListener) {
        this.listener = listener
    }

    override fun start(config: LiveTranslateSessionStartConfig) {
        lastStartConfig = config
    }

    override fun stop() {
        stopCount += 1
    }
}