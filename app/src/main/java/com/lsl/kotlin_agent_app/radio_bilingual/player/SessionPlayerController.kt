package com.lsl.kotlin_agent_app.radio_bilingual.player

import java.io.Closeable
import java.io.File
import kotlinx.coroutines.flow.StateFlow

internal interface SessionPlayerController : Closeable {

    data class PlayerState(
        val isPlaying: Boolean,
        val currentChunkIndex: Int,
        val currentPositionMs: Long,
        val playbackSpeed: Float,
        val lastErrorMessage: String? = null,
    )

    val state: StateFlow<PlayerState>

    fun loadSession(chunks: List<File>)

    fun play()

    fun pause()

    fun togglePlayPause()

    fun seekToChunk(chunkIndex: Int, positionMs: Long)

    fun setSpeed(speed: Float)

    override fun close()
}

