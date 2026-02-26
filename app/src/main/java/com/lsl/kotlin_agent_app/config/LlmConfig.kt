package com.lsl.kotlin_agent_app.config

data class LlmConfig(
    val provider: String = "openai",
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val anthropicBaseUrl: String = "",
    val anthropicApiKey: String = "",
    val anthropicModel: String = "",
    val deepseekBaseUrl: String = "",
    val deepseekApiKey: String = "",
    val deepseekModel: String = "",
    val tavilyUrl: String = "",
    val tavilyApiKey: String = "",
)
