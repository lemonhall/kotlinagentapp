package com.lsl.kotlin_agent_app.config

import android.content.SharedPreferences
import com.lsl.kotlin_agent_app.BuildConfig

class SharedPreferencesLlmConfigRepository(
    private val prefs: SharedPreferences,
) : LlmConfigRepository {
    override fun get(): LlmConfig {
        val stored =
            LlmConfig(
                baseUrl = prefs.getString(KEY_BASE_URL, "") ?: "",
                apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
                model = prefs.getString(KEY_MODEL, "") ?: "",
            )

        val shouldSeedDefaults =
            BuildConfig.DEBUG &&
                stored.baseUrl.isBlank() &&
                stored.apiKey.isBlank() &&
                stored.model.isBlank() &&
                BuildConfig.DEFAULT_OPENAI_BASE_URL.isNotBlank() &&
                BuildConfig.DEFAULT_OPENAI_API_KEY.isNotBlank() &&
                BuildConfig.DEFAULT_MODEL.isNotBlank()

        if (shouldSeedDefaults) {
            val seeded =
                LlmConfig(
                    baseUrl = BuildConfig.DEFAULT_OPENAI_BASE_URL,
                    apiKey = BuildConfig.DEFAULT_OPENAI_API_KEY,
                    model = BuildConfig.DEFAULT_MODEL,
                )
            set(seeded)
            return seeded
        }

        return stored
    }

    override fun set(config: LlmConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_API_KEY, config.apiKey)
            .putString(KEY_MODEL, config.model)
            .apply()
    }

    fun getPreviousResponseId(): String? {
        return prefs.getString(KEY_PREVIOUS_RESPONSE_ID, null)?.trim()?.ifEmpty { null }
    }

    fun setPreviousResponseId(id: String?) {
        prefs.edit().putString(KEY_PREVIOUS_RESPONSE_ID, id?.trim()?.ifEmpty { null }).apply()
    }

    private companion object {
        private const val KEY_BASE_URL = "llm.base_url"
        private const val KEY_API_KEY = "llm.api_key"
        private const val KEY_MODEL = "llm.model"

        const val KEY_PREVIOUS_RESPONSE_ID = "llm.previous_response_id"
    }
}
