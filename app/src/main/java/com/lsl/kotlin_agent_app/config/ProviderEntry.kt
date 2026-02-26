package com.lsl.kotlin_agent_app.config

import kotlinx.serialization.Serializable

@Serializable
data class ProviderEntry(
    val id: String,
    val displayName: String,
    val type: ProviderType,
    val baseUrl: String,
    val apiKey: String,
    val selectedModel: String,
    val models: List<String> = emptyList(),
)

@Serializable
enum class ProviderType(val label: String) {
    OPENAI_RESPONSES("OpenAI Responses"),
    OPENAI_CHATCOMPLETIONS("OpenAI ChatCompletions"),
    ANTHROPIC_MESSAGES("Anthropic Messages"),
}
