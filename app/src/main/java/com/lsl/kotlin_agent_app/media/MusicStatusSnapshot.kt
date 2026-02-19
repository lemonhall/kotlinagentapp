package com.lsl.kotlin_agent_app.media

data class MusicStatusSnapshot(
    val nowPlaying: MusicNowPlayingState,
    val transport: MusicTransportSnapshot,
)

