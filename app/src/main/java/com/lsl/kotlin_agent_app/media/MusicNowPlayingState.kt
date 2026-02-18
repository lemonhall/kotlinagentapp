package com.lsl.kotlin_agent_app.media

enum class MusicPlaybackState {
    Idle,
    Playing,
    Paused,
    Stopped,
    Error,
}

data class MusicNowPlayingState(
    val agentsPath: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val playbackState: MusicPlaybackState = MusicPlaybackState.Idle,
    val queueIndex: Int? = null,
    val queueSize: Int? = null,
    val playbackMode: MusicPlaybackMode = MusicPlaybackMode.SequentialLoop,
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val coverArtBytes: ByteArray? = null,
    val lyrics: String? = null,
    val warningMessage: String? = null,
    val errorMessage: String? = null,
)

