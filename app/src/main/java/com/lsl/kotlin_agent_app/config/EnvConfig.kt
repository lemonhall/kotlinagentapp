package com.lsl.kotlin_agent_app.config

import com.lsl.kotlin_agent_app.BuildConfig

internal object EnvConfig {
    val dashScopeApiKey: String
        get() = BuildConfig.DEFAULT_DASHSCOPE_API_KEY.trim()

    val dashScopeBaseUrl: String
        get() =
            BuildConfig.DEFAULT_DASHSCOPE_BASE_URL
                .trim()
                .ifBlank { "https://dashscope.aliyuncs.com/api/v1" }

    val asrModel: String
        get() =
            BuildConfig.DEFAULT_ASR_MODEL
                .trim()
                .ifBlank { "qwen3-asr-flash-filetrans" }
}

