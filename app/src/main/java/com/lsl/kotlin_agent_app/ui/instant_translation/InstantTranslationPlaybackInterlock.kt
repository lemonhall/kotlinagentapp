package com.lsl.kotlin_agent_app.ui.instant_translation

import com.lsl.kotlin_agent_app.voiceinput.VoiceInputUiState

internal class InstantTranslationPlaybackInterlock(
    private val voiceStateProvider: () -> VoiceInputUiState,
    private val stopListening: () -> Unit,
    private val finishArchiveSession: () -> Unit,
    private val resumeListening: () -> Unit,
    private val canResume: () -> Boolean = { true },
) : InstantTranslationPlaybackHooks {
    private var shouldResumeAfterPlayback: Boolean = false

    override fun beforePlayback() {
        val state = voiceStateProvider.invoke()
        shouldResumeAfterPlayback = state.isRecording || state.isStarting
        if (!shouldResumeAfterPlayback) return
        stopListening.invoke()
        finishArchiveSession.invoke()
    }

    override fun afterPlayback() {
        val shouldResume = shouldResumeAfterPlayback
        shouldResumeAfterPlayback = false
        if (!shouldResume) return
        if (!canResume.invoke()) return
        resumeListening.invoke()
    }
}
