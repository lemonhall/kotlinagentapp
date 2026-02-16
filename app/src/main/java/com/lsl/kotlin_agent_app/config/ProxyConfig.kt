package com.lsl.kotlin_agent_app.config

data class ProxyConfig(
    val enabled: Boolean,
    val httpProxy: String = "",
    val httpsProxy: String = "",
)

