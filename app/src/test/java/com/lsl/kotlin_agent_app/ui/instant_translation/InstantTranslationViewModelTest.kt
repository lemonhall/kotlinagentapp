package com.lsl.kotlin_agent_app.ui.instant_translation

import com.lsl.kotlin_agent_app.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InstantTranslationViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun partialTranscript_updatesListeningPreview() {
        val vm = InstantTranslationViewModel(translator = FakeInstantTranslator(), speaker = NoopSpeaker(), ioDispatcher = Dispatchers.Main)

        vm.onPartialTranscript("你好")

        assertEquals("你好", vm.uiState.value.listeningPreview)
    }

    @Test
    fun finalTranscript_translatesAndAppendsTurn() = runTest {
        val vm = InstantTranslationViewModel(translator = FakeInstantTranslator(), speaker = NoopSpeaker(), ioDispatcher = Dispatchers.Main)

        vm.onFinalTranscript("你好")
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.turns.size)
        assertEquals("你好", vm.uiState.value.turns[0].sourceText)
        assertEquals("en", vm.uiState.value.turns[0].targetLanguageCode)
        assertEquals("Hello", vm.uiState.value.turns[0].translatedText)
        assertFalse(vm.uiState.value.turns[0].isPending)
        assertEquals("", vm.uiState.value.listeningPreview)
    }

    @Test
    fun finalTranscript_recordsArchiveSessionPathOnTurn() = runTest {
        val vm = InstantTranslationViewModel(translator = FakeInstantTranslator(), speaker = NoopSpeaker(), ioDispatcher = Dispatchers.Main)

        vm.onFinalTranscript(
            text = "你好",
            archiveSessionRelativePath = ".agents/workspace/instant_translation/2026年03月09日 晚22点01分",
        )
        advanceUntilIdle()

        assertEquals(
            ".agents/workspace/instant_translation/2026年03月09日 晚22点01分",
            vm.uiState.value.turns[0].archiveSessionRelativePath,
        )
    }

    @Test
    fun finalTranscript_whenTranslatorFails_setsError() = runTest {
        val vm =
            InstantTranslationViewModel(
                translator = object : InstantTranslator {
                    override suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): String {
                        error("provider missing")
                    }
                },
                speaker = NoopSpeaker(),
                ioDispatcher = Dispatchers.Main,
            )

        vm.onFinalTranscript("你好")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.errorMessage?.contains("provider missing") == true)
        assertEquals(1, vm.uiState.value.turns.size)
        assertFalse(vm.uiState.value.turns[0].isPending)
    }

    @Test
    fun setTargetLanguage_updatesSelection() {
        val vm = InstantTranslationViewModel(translator = FakeInstantTranslator(), speaker = NoopSpeaker(), ioDispatcher = Dispatchers.Main)

        vm.setTargetLanguage(code = "ja", label = "日本语")

        assertEquals("ja", vm.uiState.value.targetLanguageCode)
        assertEquals("日本语", vm.uiState.value.targetLanguageLabel)
    }

    private class FakeInstantTranslator : InstantTranslator {
        override suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): String {
            return when (text) {
                "你好" -> "Hello"
                else -> "[$targetLanguage] $text"
            }
        }
    }

    private class NoopSpeaker : InstantTranslationSpeaker {
        override suspend fun speak(text: String, languageCode: String) = Unit
    }
}
