package com.lsl.kotlin_agent_app.recorder

internal data class RecorderRuntimeState(
    val sessionId: String,
    val state: String,
    val elapsedMs: Long,
    val level01: Float,
)

