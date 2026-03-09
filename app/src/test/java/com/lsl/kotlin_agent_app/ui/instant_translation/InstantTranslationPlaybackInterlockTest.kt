package com.lsl.kotlin_agent_app.ui.instant_translation

import com.lsl.kotlin_agent_app.voiceinput.VoiceInputUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class InstantTranslationPlaybackInterlockTest {
    @Test
    fun beforeAndAfterPlayback_pauseAndResumeAsrWhenRecording() {
        val calls = mutableListOf<String>()
        val interlock =
            InstantTranslationPlaybackInterlock(
                voiceStateProvider = { VoiceInputUiState(isRecording = true) },
                stopListening = { calls += "stop" },
                finishArchiveSession = { calls += "finishArchive" },
                resumeListening = { calls += "resume" },
            )

        interlock.beforePlayback()
        interlock.afterPlayback()

        assertEquals(listOf("stop", "finishArchive", "resume"), calls)
    }

    @Test
    fun beforeAndAfterPlayback_doNothingWhenAsrAlreadyIdle() {
        val calls = mutableListOf<String>()
        val interlock =
            InstantTranslationPlaybackInterlock(
                voiceStateProvider = { VoiceInputUiState() },
                stopListening = { calls += "stop" },
                finishArchiveSession = { calls += "finishArchive" },
                resumeListening = { calls += "resume" },
            )

        interlock.beforePlayback()
        interlock.afterPlayback()

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun afterPlayback_skipsResumeWhenLifecycleDisallowsIt() {
        val calls = mutableListOf<String>()
        val interlock =
            InstantTranslationPlaybackInterlock(
                voiceStateProvider = { VoiceInputUiState(isRecording = true) },
                stopListening = { calls += "stop" },
                finishArchiveSession = { calls += "finishArchive" },
                resumeListening = { calls += "resume" },
                canResume = { false },
            )

        interlock.beforePlayback()
        interlock.afterPlayback()

        assertEquals(listOf("stop", "finishArchive"), calls)
    }
}
