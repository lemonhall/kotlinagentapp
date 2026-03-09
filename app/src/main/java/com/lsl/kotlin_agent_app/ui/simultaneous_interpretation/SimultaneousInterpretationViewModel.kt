package com.lsl.kotlin_agent_app.ui.simultaneous_interpretation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.lsl.kotlin_agent_app.voiceinput.SharedPreferencesVoiceInputConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class SimultaneousInterpretationViewModel(
    private val sessionController: LiveTranslateSessionController,
    private val apiKeyProvider: () -> String,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SimultaneousInterpretationUiState())
    internal val uiState: StateFlow<SimultaneousInterpretationUiState> = _uiState.asStateFlow()

    private var nextSegmentId: Long = 1L

    init {
        sessionController.bind(
            object : LiveTranslateSessionListener {
                override fun onSessionConnecting() {
                    _uiState.update {
                        it.copy(
                            isConnecting = true,
                            isRunning = false,
                            statusText = "连接中…",
                            errorMessage = null,
                            sourcePreview = "",
                            translatedPreview = "",
                        )
                    }
                }

                override fun onSessionStarted(
                    sessionPath: String,
                    isHeadsetConnected: Boolean,
                ) {
                    _uiState.update {
                        it.copy(
                            isConnecting = false,
                            isRunning = true,
                            isHeadsetConnected = isHeadsetConnected,
                            statusText = "实时同传中",
                            sessionPath = sessionPath,
                            errorMessage = null,
                        )
                    }
                }

                override fun onSourceTranscriptCompleted(text: String) {
                    _uiState.update {
                        it.copy(sourcePreview = text.trim())
                    }
                }

                override fun onTranslationDelta(text: String) {
                    _uiState.update {
                        it.copy(translatedPreview = text)
                    }
                }

                override fun onSegmentFinal(
                    sourceText: String,
                    translatedText: String,
                ) {
                    val segment =
                        SimultaneousInterpretationSegment(
                            id = nextSegmentId++,
                            sourceText = sourceText,
                            translatedText = translatedText,
                        )
                    _uiState.update {
                        it.copy(
                            sourcePreview = sourceText,
                            translatedPreview = translatedText,
                            segments = it.segments + segment,
                        )
                    }
                }

                override fun onError(message: String) {
                    _uiState.update {
                        it.copy(errorMessage = message)
                    }
                }

                override fun onSessionStopped() {
                    _uiState.update {
                        it.copy(
                            isConnecting = false,
                            isRunning = false,
                            statusText = "已停止",
                        )
                    }
                }
            },
        )
    }

    fun startSession() {
        val apiKey = apiKeyProvider.invoke().trim()
        if (apiKey.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = "请先在 Settings 的 Voice Input 中填写 DASHSCOPE_API_KEY")
            }
            return
        }
        val state = _uiState.value
        sessionController.start(
            LiveTranslateSessionStartConfig(
                apiKey = apiKey,
                targetLanguageCode = state.targetLanguageCode,
                targetLanguageLabel = state.targetLanguageLabel,
            ),
        )
    }

    fun stopSession() {
        sessionController.stop()
    }

    fun toggleSession() {
        val state = _uiState.value
        if (state.isRunning || state.isConnecting) {
            stopSession()
        } else {
            startSession()
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

    fun setError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    override fun onCleared() {
        sessionController.stop()
        super.onCleared()
    }

    internal class Factory(
        private val appContext: Context,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SimultaneousInterpretationViewModel::class.java)) {
                val prefs = appContext.getSharedPreferences("kotlin-agent-app", Context.MODE_PRIVATE)
                val configRepo = SharedPreferencesVoiceInputConfigRepository(prefs)
                @Suppress("UNCHECKED_CAST")
                return SimultaneousInterpretationViewModel(
                    sessionController =
                        DefaultLiveTranslateSessionController(
                            context = appContext,
                            archiveManager = SimultaneousInterpretationArchiveManager(appContext),
                        ),
                    apiKeyProvider = { configRepo.get().apiKey },
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
        }
    }
}