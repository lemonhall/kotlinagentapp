package com.lsl.kotlin_agent_app.ui.instant_translation

import com.lsl.kotlin_agent_app.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InstantTranslationViewModelTtsTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun playTurn_speaksTranslatedTextWithTargetLanguage() = runTest {
        val speaker = FakeSpeaker()
        val vm =
            InstantTranslationViewModel(
                translator = FakeTranslator(),
                speaker = speaker,
                ioDispatcher = Dispatchers.Main,
            )

        vm.setTargetLanguage(code = "ja", label = "日本语")
        vm.onFinalTranscript("你好")
        advanceUntilIdle()

        vm.playTurn(vm.uiState.value.turns.single().id)
        advanceUntilIdle()

        assertEquals(listOf(SpeakCall(text = "Hello", languageCode = "ja")), speaker.calls)
    }

    @Test
    fun playTurn_whenSpeakerFails_setsError() = runTest {
        val vm =
            InstantTranslationViewModel(
                translator = FakeTranslator(),
                speaker = object : InstantTranslationSpeaker {
                    override suspend fun speak(text: String, languageCode: String) {
                        error("tts unavailable")
                    }
                },
                ioDispatcher = Dispatchers.Main,
            )

        vm.onFinalTranscript("你好")
        advanceUntilIdle()

        vm.playTurn(vm.uiState.value.turns.single().id)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.errorMessage?.contains("tts unavailable") == true)
    }

    private class FakeTranslator : InstantTranslator {
        override suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): String = "Hello"
    }

    private class FakeSpeaker : InstantTranslationSpeaker {
        val calls = mutableListOf<SpeakCall>()

        override suspend fun speak(text: String, languageCode: String) {
            calls += SpeakCall(text = text, languageCode = languageCode)
        }
    }

    private data class SpeakCall(
        val text: String,
        val languageCode: String,
    )
}
