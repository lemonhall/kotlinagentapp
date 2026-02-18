package com.lsl.kotlin_agent_app.media

data class Mp3Metadata(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
)

