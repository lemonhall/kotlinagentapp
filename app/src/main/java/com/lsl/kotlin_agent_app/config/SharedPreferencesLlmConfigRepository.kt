package com.lsl.kotlin_agent_app.config

import android.content.SharedPreferences
import com.lsl.kotlin_agent_app.BuildConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class SharedPreferencesLlmConfigRepository(
    private val prefs: SharedPreferences,
) : LlmConfigRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override fun get(): LlmConfig {
        migrateIfNeeded()

        val providersJson = prefs.getString(KEY_PROVIDERS_JSON, null)
        val providers: List<ProviderEntry> = if (providersJson.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<ProviderEntry>>(providersJson) }
                .getOrDefault(emptyList())
        }
        val activeId = prefs.getString(KEY_ACTIVE_PROVIDER_ID, "") ?: ""

        val stored = LlmConfig(
            activeProviderId = activeId,
            providers = providers,
            tavilyUrl = prefs.getString(KEY_TAVILY_URL, "") ?: "",
            tavilyApiKey = prefs.getString(KEY_TAVILY_API_KEY, "") ?: "",
        )

        if (BuildConfig.DEBUG) {
            val seeded = seedFromBuildConfig(stored)
            if (seeded != stored) {
                set(seeded)
                return seeded
            }
        }
        return stored
    }

    override fun set(config: LlmConfig) {
        prefs.edit()
            .putString(KEY_ACTIVE_PROVIDER_ID, config.activeProviderId)
            .putString(KEY_PROVIDERS_JSON, json.encodeToString(config.providers))
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

    // ── Migration from old per-provider fields ──────────────────────────

    private fun migrateIfNeeded() {
        val oldProvider = prefs.getString(OLD_KEY_PROVIDER, null)
        val hasNewFormat = prefs.contains(KEY_PROVIDERS_JSON)
        if (oldProvider == null || hasNewFormat) return

        val entries = mutableListOf<ProviderEntry>()
        var activeId = ""

        // OpenAI
        val oaiUrl = prefs.getString(OLD_KEY_BASE_URL, "") ?: ""
        val oaiKey = prefs.getString(OLD_KEY_API_KEY, "") ?: ""
        val oaiModel = prefs.getString(OLD_KEY_MODEL, "") ?: ""
        if (oaiUrl.isNotBlank() || oaiKey.isNotBlank()) {
            val id = UUID.randomUUID().toString()
            entries += ProviderEntry(
                id = id, displayName = "OpenAI",
                type = ProviderType.OPENAI_RESPONSES,
                baseUrl = oaiUrl, apiKey = oaiKey, selectedModel = oaiModel,
                models = listOf("gpt-4.1-mini", "gpt-4.1", "o4-mini"),
            )
            if (oldProvider.trim().lowercase() == "openai" || oldProvider.isBlank()) activeId = id
        }

        // Anthropic
        val antUrl = prefs.getString(OLD_KEY_ANTHROPIC_BASE_URL, "") ?: ""
        val antKey = prefs.getString(OLD_KEY_ANTHROPIC_API_KEY, "") ?: ""
        val antModel = prefs.getString(OLD_KEY_ANTHROPIC_MODEL, "") ?: ""
        if (antUrl.isNotBlank() || antKey.isNotBlank()) {
            val id = UUID.randomUUID().toString()
            entries += ProviderEntry(
                id = id, displayName = "Anthropic",
                type = ProviderType.ANTHROPIC_MESSAGES,
                baseUrl = antUrl, apiKey = antKey, selectedModel = antModel,
                models = listOf("claude-sonnet-4-20250514"),
            )
            if (oldProvider.trim().lowercase() == "anthropic") activeId = id
        }

        // DeepSeek
        val dsUrl = prefs.getString(OLD_KEY_DEEPSEEK_BASE_URL, "") ?: ""
        val dsKey = prefs.getString(OLD_KEY_DEEPSEEK_API_KEY, "") ?: ""
        val dsModel = prefs.getString(OLD_KEY_DEEPSEEK_MODEL, "") ?: ""
        if (dsUrl.isNotBlank() || dsKey.isNotBlank()) {
            val id = UUID.randomUUID().toString()
            entries += ProviderEntry(
                id = id, displayName = "DeepSeek",
                type = ProviderType.OPENAI_CHATCOMPLETIONS,
                baseUrl = dsUrl, apiKey = dsKey, selectedModel = dsModel,
                models = listOf("deepseek-chat", "deepseek-reasoner"),
            )
            if (oldProvider.trim().lowercase() == "deepseek") activeId = id
        }

        if (activeId.isBlank() && entries.isNotEmpty()) activeId = entries.first().id

        val tavilyUrl = prefs.getString(OLD_KEY_TAVILY_URL, "") ?: ""
        val tavilyKey = prefs.getString(OLD_KEY_TAVILY_API_KEY, "") ?: ""

        prefs.edit()
            .putString(KEY_ACTIVE_PROVIDER_ID, activeId)
            .putString(KEY_PROVIDERS_JSON, json.encodeToString(entries))
            .putString(KEY_TAVILY_URL, tavilyUrl)
            .putString(KEY_TAVILY_API_KEY, tavilyKey)
            .remove(OLD_KEY_PROVIDER)
            .remove(OLD_KEY_BASE_URL).remove(OLD_KEY_API_KEY).remove(OLD_KEY_MODEL)
            .remove(OLD_KEY_ANTHROPIC_BASE_URL).remove(OLD_KEY_ANTHROPIC_API_KEY).remove(OLD_KEY_ANTHROPIC_MODEL)
            .remove(OLD_KEY_DEEPSEEK_BASE_URL).remove(OLD_KEY_DEEPSEEK_API_KEY).remove(OLD_KEY_DEEPSEEK_MODEL)
            .remove(OLD_KEY_TAVILY_URL).remove(OLD_KEY_TAVILY_API_KEY)
            .apply()
    }

    // ── BuildConfig seed (DEBUG only) ───────────────────────────────────

    private fun seedFromBuildConfig(stored: LlmConfig): LlmConfig {
        val providers = stored.providers.toMutableList()
        var activeId = stored.activeProviderId
        var changed = false

        fun ensureProvider(
            name: String, type: ProviderType,
            bcUrl: String, bcKey: String, bcModel: String,
            defaultModels: List<String>,
        ) {
            val existing = providers.firstOrNull { it.displayName == name }
            if (existing != null) {
                val patched = existing.copy(
                    baseUrl = existing.baseUrl.ifBlank { bcUrl },
                    apiKey = existing.apiKey.ifBlank { bcKey },
                    selectedModel = existing.selectedModel.ifBlank { bcModel },
                )
                if (patched != existing) {
                    providers[providers.indexOf(existing)] = patched
                    changed = true
                }
            } else if (bcUrl.isNotBlank() || bcKey.isNotBlank()) {
                val id = UUID.randomUUID().toString()
                providers += ProviderEntry(
                    id = id, displayName = name, type = type,
                    baseUrl = bcUrl, apiKey = bcKey, selectedModel = bcModel,
                    models = defaultModels,
                )
                changed = true
            }
        }

        ensureProvider("OpenAI", ProviderType.OPENAI_RESPONSES,
            BuildConfig.DEFAULT_OPENAI_BASE_URL, BuildConfig.DEFAULT_OPENAI_API_KEY, BuildConfig.DEFAULT_MODEL,
            listOf("gpt-4.1-mini", "gpt-4.1", "o4-mini"))
        ensureProvider("Anthropic", ProviderType.ANTHROPIC_MESSAGES,
            BuildConfig.DEFAULT_ANTHROPIC_BASE_URL, BuildConfig.DEFAULT_ANTHROPIC_API_KEY, BuildConfig.DEFAULT_ANTHROPIC_MODEL,
            listOf("claude-sonnet-4-20250514"))
        ensureProvider("DeepSeek", ProviderType.OPENAI_CHATCOMPLETIONS,
            BuildConfig.DEFAULT_DEEPSEEK_BASE_URL, BuildConfig.DEFAULT_DEEPSEEK_API_KEY, BuildConfig.DEFAULT_DEEPSEEK_MODEL,
            listOf("deepseek-chat", "deepseek-reasoner"))

        if (activeId.isBlank() && providers.isNotEmpty()) {
            val defaultName = BuildConfig.DEFAULT_PROVIDER.trim().ifBlank { "openai" }
            val match = providers.firstOrNull { it.displayName.lowercase() == defaultName.lowercase() }
            activeId = match?.id ?: providers.first().id
            changed = true
        }

        val tavilyUrl = stored.tavilyUrl.ifBlank { BuildConfig.DEFAULT_TAVILY_URL }
        val tavilyKey = stored.tavilyApiKey.ifBlank { BuildConfig.DEFAULT_TAVILY_API_KEY }
        if (tavilyUrl != stored.tavilyUrl || tavilyKey != stored.tavilyApiKey) changed = true

        return if (changed) {
            LlmConfig(activeProviderId = activeId, providers = providers, tavilyUrl = tavilyUrl, tavilyApiKey = tavilyKey)
        } else {
            stored.copy(activeProviderId = activeId)
        }
    }

    private companion object {
        const val KEY_ACTIVE_PROVIDER_ID = "llm.active_provider_id"
        const val KEY_PROVIDERS_JSON = "llm.providers_json"
        const val KEY_TAVILY_URL = "tools.tavily_url"
        const val KEY_TAVILY_API_KEY = "tools.tavily_api_key"
        const val KEY_PREVIOUS_RESPONSE_ID = "llm.previous_response_id"

        // Old keys for migration
        const val OLD_KEY_PROVIDER = "llm.provider"
        const val OLD_KEY_BASE_URL = "llm.base_url"
        const val OLD_KEY_API_KEY = "llm.api_key"
        const val OLD_KEY_MODEL = "llm.model"
        const val OLD_KEY_ANTHROPIC_BASE_URL = "llm.anthropic_base_url"
        const val OLD_KEY_ANTHROPIC_API_KEY = "llm.anthropic_api_key"
        const val OLD_KEY_ANTHROPIC_MODEL = "llm.anthropic_model"
        const val OLD_KEY_DEEPSEEK_BASE_URL = "llm.deepseek_base_url"
        const val OLD_KEY_DEEPSEEK_API_KEY = "llm.deepseek_api_key"
        const val OLD_KEY_DEEPSEEK_MODEL = "llm.deepseek_model"
        const val OLD_KEY_TAVILY_URL = "tools.tavily_url"
        const val OLD_KEY_TAVILY_API_KEY = "tools.tavily_api_key"
    }
}
