package com.lsl.kotlin_agent_app.media

data class MusicMediaMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
)

data class MusicPlaybackRequest(
    val agentsPath: String,
    val uri: String,
    val metadata: MusicMediaMetadata = MusicMediaMetadata(),
    val isLive: Boolean = false,
)

interface MusicTransport {
    suspend fun connect()
    suspend fun play(request: MusicPlaybackRequest)
    suspend fun pause()
    suspend fun resume()
    suspend fun stop()
    suspend fun seekTo(positionMs: Long)

    fun currentPositionMs(): Long
    fun durationMs(): Long?
    fun isPlaying(): Boolean

    suspend fun setVolume(volume: Float)
    fun volume(): Float?

    fun setListener(listener: MusicTransportListener?)
}

interface MusicTransportListener {
    fun onPlaybackEnded()
    fun onPlayerError(message: String?)
}
