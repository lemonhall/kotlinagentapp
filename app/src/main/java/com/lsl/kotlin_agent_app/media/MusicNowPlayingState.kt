package com.lsl.kotlin_agent_app.media

data class MusicNowPlayingState(
    val agentsPath: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long? = null,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val warningMessage: String? = null,
    val errorMessage: String? = null,
)

