package com.lsl.kotlin_agent_app.media

import java.io.File

data class MusicPlaybackRequest(
    val agentsPath: String,
    val file: File,
    val metadata: Mp3Metadata,
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
}

