package com.lsl.kotlin_agent_app.config

import com.lsl.kotlin_agent_app.config.ProviderType.ANTHROPIC_MESSAGES
import com.lsl.kotlin_agent_app.config.ProviderType.OPENAI_CHATCOMPLETIONS
import com.lsl.kotlin_agent_app.config.ProviderType.OPENAI_RESPONSES

object ProviderPresets {
    data class Preset(
        val displayName: String,
        val type: ProviderType,
        val defaultBaseUrl: String,
        val defaultModels: List<String>,
    )

    val ALL = listOf(
        Preset("OpenAI", OPENAI_RESPONSES, "https://api.openai.com/v1", listOf("gpt-4.1-mini", "gpt-4.1", "o4-mini")),
        Preset("Anthropic", ANTHROPIC_MESSAGES, "https://api.anthropic.com", listOf("claude-sonnet-4-20250514")),
        Preset("DeepSeek", OPENAI_CHATCOMPLETIONS, "https://api.deepseek.com/v1", listOf("deepseek-chat", "deepseek-reasoner")),
        Preset("Kimi / Moonshot", OPENAI_CHATCOMPLETIONS, "https://api.moonshot.cn/v1", listOf("moonshot-v1-auto")),
        Preset("GLM / 智谱", OPENAI_CHATCOMPLETIONS, "https://open.bigmodel.cn/api/paas/v4", listOf("glm-4-flash", "glm-4-plus")),
        Preset("Gemini", OPENAI_CHATCOMPLETIONS, "https://generativelanguage.googleapis.com/v1beta/openai", listOf("gemini-2.5-flash")),
        Preset("Grok", OPENAI_CHATCOMPLETIONS, "https://api.x.ai/v1", listOf("grok-3-mini-fast")),
        Preset("阿里通义", OPENAI_CHATCOMPLETIONS, "https://dashscope.aliyuncs.com/compatible-mode/v1", listOf("qwen-plus", "qwen-max")),
        Preset("Custom (OpenAI-compatible)", OPENAI_CHATCOMPLETIONS, "", emptyList()),
    )
}
