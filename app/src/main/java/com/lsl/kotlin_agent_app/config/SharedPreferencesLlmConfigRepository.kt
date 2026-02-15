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
                tavilyUrl = prefs.getString(KEY_TAVILY_URL, "") ?: "",
                tavilyApiKey = prefs.getString(KEY_TAVILY_API_KEY, "") ?: "",
            )

        if (BuildConfig.DEBUG) {
            val seeded =
                stored.copy(
                    baseUrl = stored.baseUrl.ifBlank { BuildConfig.DEFAULT_OPENAI_BASE_URL },
                    apiKey = stored.apiKey.ifBlank { BuildConfig.DEFAULT_OPENAI_API_KEY },
                    model = stored.model.ifBlank { BuildConfig.DEFAULT_MODEL },
                    tavilyUrl = stored.tavilyUrl.ifBlank { BuildConfig.DEFAULT_TAVILY_URL },
                    tavilyApiKey = stored.tavilyApiKey.ifBlank { BuildConfig.DEFAULT_TAVILY_API_KEY },
                )
            if (seeded != stored) {
                set(seeded)
                return seeded
            }
        }

        return stored
    }

    override fun set(config: LlmConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_API_KEY, config.apiKey)
            .putString(KEY_MODEL, config.model)
            .putString(KEY_TAVILY_URL, config.tavilyUrl)
            .putString(KEY_TAVILY_API_KEY, config.tavilyApiKey)
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
        private const val KEY_TAVILY_URL = "tools.tavily_url"
        private const val KEY_TAVILY_API_KEY = "tools.tavily_api_key"

        const val KEY_PREVIOUS_RESPONSE_ID = "llm.previous_response_id"
    }
}
