package com.lsl.kotlin_agent_app.config

data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val tavilyUrl: String = "",
    val tavilyApiKey: String = "",
)
