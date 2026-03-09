package com.lsl.kotlin_agent_app.voiceinput

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VoiceInputUiState(
    val isStarting: Boolean = false,
    val isRecording: Boolean = false,
    val errorMessage: String? = null,
)

interface VoiceInputListener {
    fun onListening()

    fun onPartialTranscript(text: String)

    fun onFinalTranscript(text: String)

    fun onError(error: Throwable)

    fun onStopped()
}

interface VoiceInputEngine {
    fun start(listener: VoiceInputListener)

    fun stop()
}

class VoiceInputController(
    private val engineFactory: () -> VoiceInputEngine,
) {
    private val _state = MutableStateFlow(VoiceInputUiState())
    val state: StateFlow<VoiceInputUiState> = _state.asStateFlow()

    private var activeEngine: VoiceInputEngine? = null
    private var draftComposer: VoiceInputDraftComposer? = null
    private var onDraftChanged: ((String) -> Unit)? = null

    fun start(
        initialDraft: String,
        onDraftChanged: (String) -> Unit,
    ) {
        if (activeEngine != null) return

        draftComposer = VoiceInputDraftComposer(initialText = initialDraft)
        this.onDraftChanged = onDraftChanged
        _state.value = VoiceInputUiState(isStarting = true)

        runCatching {
            val engine = engineFactory.invoke()
            activeEngine = engine
            engine.start(
                object : VoiceInputListener {
                    override fun onListening() {
                        _state.value = _state.value.copy(isStarting = false, isRecording = true, errorMessage = null)
                    }

                    override fun onPartialTranscript(text: String) {
                        val updated = draftComposer?.applyPartial(text) ?: return
                        onDraftChanged.invoke(updated)
                    }

                    override fun onFinalTranscript(text: String) {
                        val updated = draftComposer?.applyFinal(text) ?: return
                        onDraftChanged.invoke(updated)
                    }

                    override fun onError(error: Throwable) {
                        activeEngine = null
                        _state.value = VoiceInputUiState(errorMessage = error.message ?: error.toString())
                    }

                    override fun onStopped() {
                        activeEngine = null
                        _state.value = _state.value.copy(isStarting = false, isRecording = false)
                    }
                },
            )
        }.onFailure { t ->
            activeEngine = null
            _state.value = VoiceInputUiState(errorMessage = t.message ?: t.toString())
        }
    }

    fun stop() {
        val engine = activeEngine ?: return
        activeEngine = null
        engine.stop()
        _state.value = _state.value.copy(isStarting = false, isRecording = false)
    }

    fun setError(message: String) {
        _state.value = VoiceInputUiState(errorMessage = message)
    }
}
