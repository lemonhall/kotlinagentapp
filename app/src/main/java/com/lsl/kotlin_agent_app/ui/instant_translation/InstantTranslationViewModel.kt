package com.lsl.kotlin_agent_app.ui.instant_translation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lsl.kotlin_agent_app.config.SharedPreferencesLlmConfigRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InstantTranslationViewModel(
    private val translator: InstantTranslator,
    private val speaker: InstantTranslationSpeaker,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _uiState = MutableStateFlow(InstantTranslationUiState())
    val uiState: StateFlow<InstantTranslationUiState> = _uiState.asStateFlow()

    private var nextTurnId: Long = 1L

    fun onPartialTranscript(text: String) {
        _uiState.update {
            it.copy(
                listeningPreview = text.trim(),
                errorMessage = null,
            )
        }
    }

    fun onFinalTranscript(text: String) {
        val sourceText = text.trim()
        if (sourceText.isBlank()) return

        val turnId = nextTurnId++
        val targetLanguage = _uiState.value.targetLanguageCode
        val targetLanguageLabel = _uiState.value.targetLanguageLabel
        _uiState.update {
            it.copy(
                listeningPreview = "",
                errorMessage = null,
                turns =
                    it.turns +
                        InstantTranslationTurn(
                            id = turnId,
                            sourceText = sourceText,
                            targetLanguageCode = targetLanguage,
                            targetLanguageLabel = targetLanguageLabel,
                        ),
            )
        }

        viewModelScope.launch {
            try {
                val translated =
                    withContext(ioDispatcher) {
                        translator.translate(
                            text = sourceText,
                            sourceLanguage = "auto",
                            targetLanguage = targetLanguage,
                        )
                    }
                _uiState.update { state ->
                    state.copy(
                        turns =
                            state.turns.map { turn ->
                                if (turn.id == turnId) {
                                    turn.copy(translatedText = translated, isPending = false)
                                } else {
                                    turn
                                }
                            },
                    )
                }
            } catch (t: Throwable) {
                _uiState.update { state ->
                    state.copy(
                        errorMessage = t.message ?: "\u7ffb\u8bd1\u5931\u8d25",
                        turns =
                            state.turns.map { turn ->
                                if (turn.id == turnId) {
                                    turn.copy(isPending = false)
                                } else {
                                    turn
                                }
                            },
                    )
                }
            }
        }
    }

    fun setTargetLanguage(
        code: String,
        label: String,
    ) {
        _uiState.update {
            it.copy(
                targetLanguageCode = code.trim().ifBlank { it.targetLanguageCode },
                targetLanguageLabel = label.trim().ifBlank { it.targetLanguageLabel },
            )
        }
    }

    fun clearTurns() {
        _uiState.update {
            it.copy(
                listeningPreview = "",
                turns = emptyList(),
                playingTurnId = null,
                errorMessage = null,
            )
        }
    }

    fun playTurn(turnId: Long) {
        val turn =
            _uiState.value.turns.firstOrNull { it.id == turnId }
                ?: return
        val translated = turn.translatedText.trim()
        if (turn.isPending || translated.isBlank()) return

        _uiState.update {
            it.copy(
                playingTurnId = turnId,
                errorMessage = null,
            )
        }

        viewModelScope.launch {
            try {
                speaker.speak(
                    text = translated,
                    languageCode = turn.targetLanguageCode,
                )
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = t.message ?: "\u64ad\u653e TTS \u5931\u8d25")
                }
            } finally {
                _uiState.update { state ->
                    state.copy(playingTurnId = if (state.playingTurnId == turnId) null else state.playingTurnId)
                }
            }
        }
    }

    fun setError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    internal class Factory(
        private val appContext: Context,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(InstantTranslationViewModel::class.java)) {
                val prefs = appContext.getSharedPreferences("kotlin-agent-app", Context.MODE_PRIVATE)
                val llmConfigRepository = SharedPreferencesLlmConfigRepository(prefs)
                @Suppress("UNCHECKED_CAST")
                return InstantTranslationViewModel(
                    translator = OpenAgenticInstantTranslator(llmConfigRepository = llmConfigRepository),
                    speaker = AndroidInstantTranslationSpeaker(appContext = appContext),
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
        }
    }
}
