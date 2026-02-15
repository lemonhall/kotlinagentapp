package com.lsl.kotlin_agent_app.config

interface LlmConfigRepository {
    fun get(): LlmConfig
    fun set(config: LlmConfig)
}

