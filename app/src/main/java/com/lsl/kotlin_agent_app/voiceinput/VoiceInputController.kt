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

    fun onAudioFrame(bytes: ByteArray) = Unit

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
    private var onAudioFrame: ((ByteArray) -> Unit)? = null
    private var onPartialTranscript: ((String) -> Unit)? = null
    private var onFinalTranscript: ((String) -> Unit)? = null

    fun start(
        initialDraft: String,
        onDraftChanged: (String) -> Unit = {},
        onAudioFrame: ((ByteArray) -> Unit)? = null,
        onPartialTranscript: ((String) -> Unit)? = null,
        onFinalTranscript: ((String) -> Unit)? = null,
    ) {
        if (activeEngine != null) return

        draftComposer = VoiceInputDraftComposer(initialText = initialDraft)
        this.onDraftChanged = onDraftChanged
        this.onAudioFrame = onAudioFrame
        this.onPartialTranscript = onPartialTranscript
        this.onFinalTranscript = onFinalTranscript
        _state.value = VoiceInputUiState(isStarting = true)

        runCatching {
            val engine = engineFactory.invoke()
            activeEngine = engine
            engine.start(
                object : VoiceInputListener {
                    override fun onListening() {
                        _state.value = _state.value.copy(isStarting = false, isRecording = true, errorMessage = null)
                    }

                    override fun onAudioFrame(bytes: ByteArray) {
                        this@VoiceInputController.onAudioFrame?.invoke(bytes)
                    }

                    override fun onPartialTranscript(text: String) {
                        val updated = draftComposer?.applyPartial(text) ?: text
                        this@VoiceInputController.onDraftChanged?.invoke(updated)
                        this@VoiceInputController.onPartialTranscript?.invoke(text)
                    }

                    override fun onFinalTranscript(text: String) {
                        val updated = draftComposer?.applyFinal(text) ?: text
                        this@VoiceInputController.onDraftChanged?.invoke(updated)
                        this@VoiceInputController.onFinalTranscript?.invoke(text)
                    }

                    override fun onError(error: Throwable) {
                        activeEngine = null
                        clearCallbacks()
                        _state.value = VoiceInputUiState(errorMessage = error.message ?: error.toString())
                    }

                    override fun onStopped() {
                        activeEngine = null
                        clearCallbacks()
                        _state.value = _state.value.copy(isStarting = false, isRecording = false)
                    }
                },
            )
        }.onFailure { t ->
            activeEngine = null
            clearCallbacks()
            _state.value = VoiceInputUiState(errorMessage = t.message ?: t.toString())
        }
    }

    fun stop() {
        val engine = activeEngine ?: return
        activeEngine = null
        clearCallbacks()
        engine.stop()
        _state.value = _state.value.copy(isStarting = false, isRecording = false)
    }

    fun setError(message: String) {
        _state.value = VoiceInputUiState(errorMessage = message)
    }

    private fun clearCallbacks() {
        draftComposer = null
        onDraftChanged = null
        onAudioFrame = null
        onPartialTranscript = null
        onFinalTranscript = null
    }
}
