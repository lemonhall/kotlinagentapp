package com.lsl.kotlin_agent_app.ui.simultaneous_interpretation

import android.content.SharedPreferences
import com.lsl.kotlin_agent_app.config.AppPrefsKeys

internal class SharedPreferencesSimultaneousInterpretationConfigRepository(
    private val prefs: SharedPreferences,
) {
    fun getAudioCaptureMode(): LiveTranslateAudioCaptureMode {
        val stored = prefs.getString(AppPrefsKeys.SIMINT_AUDIO_CAPTURE_MODE, null).orEmpty().trim()
        return runCatching { LiveTranslateAudioCaptureMode.valueOf(stored) }
            .getOrDefault(LiveTranslateAudioCaptureMode.SENSITIVE)
    }

    fun saveAudioCaptureMode(mode: LiveTranslateAudioCaptureMode) {
        prefs.edit().putString(AppPrefsKeys.SIMINT_AUDIO_CAPTURE_MODE, mode.name).apply()
    }
}

