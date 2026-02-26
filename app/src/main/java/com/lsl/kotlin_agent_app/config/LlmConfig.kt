package com.lsl.kotlin_agent_app.config

data class LlmConfig(
    val activeProviderId: String = "",
    val providers: List<ProviderEntry> = emptyList(),
    val tavilyUrl: String = "",
    val tavilyApiKey: String = "",
) {
    val activeProvider: ProviderEntry?
        get() = providers.firstOrNull { it.id == activeProviderId }
}
