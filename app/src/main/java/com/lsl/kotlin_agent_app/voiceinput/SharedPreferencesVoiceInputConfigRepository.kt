package com.lsl.kotlin_agent_app.voiceinput

import android.content.SharedPreferences
import com.lsl.kotlin_agent_app.config.AppPrefsKeys
import com.lsl.kotlin_agent_app.config.EnvConfig

class SharedPreferencesVoiceInputConfigRepository(
    private val prefs: SharedPreferences,
    private val envApiKeyProvider: () -> String = { EnvConfig.dashScopeApiKey },
) {
    fun get(): VoiceInputConfig = VoiceInputConfig(apiKey = resolveApiKey())

    fun getEditableApiKey(): String = resolveApiKey()

    fun saveApiKey(apiKey: String) {
        prefs.edit().putString(AppPrefsKeys.ASR_DASHSCOPE_API_KEY, apiKey.trim()).apply()
    }

    private fun resolveApiKey(): String {
        val stored = prefs.getString(AppPrefsKeys.ASR_DASHSCOPE_API_KEY, null).orEmpty().trim()
        if (stored.isNotBlank()) return stored
        return envApiKeyProvider.invoke().trim()
    }
}
