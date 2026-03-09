package com.lsl.kotlin_agent_app.translation

import com.lsl.kotlin_agent_app.config.ProviderEntry
import com.lsl.kotlin_agent_app.config.ProviderType
import me.lemonhall.openagentic.sdk.providers.AnthropicMessagesHttpProvider
import me.lemonhall.openagentic.sdk.providers.OpenAIChatCompletionsHttpProvider
import me.lemonhall.openagentic.sdk.providers.OpenAIResponsesHttpProvider
import me.lemonhall.openagentic.sdk.providers.StreamingResponsesProvider

internal object TranslationProviderFactory {
    fun create(entry: ProviderEntry): StreamingResponsesProvider {
        return when (entry.type) {
            ProviderType.OPENAI_RESPONSES ->
                OpenAIResponsesHttpProvider(
                    baseUrl = entry.baseUrl.trim().ifBlank { "https://api.openai.com/v1" },
                    defaultStore = false,
                )

            ProviderType.OPENAI_CHATCOMPLETIONS ->
                OpenAIChatCompletionsHttpProvider(
                    baseUrl = entry.baseUrl.trim().ifBlank { "https://api.openai.com/v1" },
                )

            ProviderType.ANTHROPIC_MESSAGES ->
                AnthropicMessagesHttpProvider(
                    baseUrl = entry.baseUrl.trim().ifBlank { "https://api.anthropic.com" },
                )
        }
    }
}