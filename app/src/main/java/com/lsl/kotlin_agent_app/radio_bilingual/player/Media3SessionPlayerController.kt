package com.lsl.kotlin_agent_app.radio_bilingual.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class Media3SessionPlayerController(
    context: Context,
) : SessionPlayerController {
    private val appContext = context.applicationContext

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)

    private val player: ExoPlayer =
        ExoPlayer.Builder(appContext)
            .build()
            .apply {
                val aa =
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build()
                setAudioAttributes(aa, true)
                setHandleAudioBecomingNoisy(true)
                addListener(
                    object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            val msg = error.message?.trim()?.ifBlank { null } ?: "player error"
                            _state.update { it.copy(lastErrorMessage = msg) }
                        }
                    },
                )
            }

    private val _state =
        MutableStateFlow(
            SessionPlayerController.PlayerState(
                isPlaying = false,
                currentChunkIndex = 0,
                currentPositionMs = 0L,
                playbackSpeed = 1.0f,
                lastErrorMessage = null,
            ),
        )
    override val state: StateFlow<SessionPlayerController.PlayerState> = _state.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                delay(200)
                val idx = player.currentMediaItemIndex.coerceAtLeast(0)
                val pos = player.currentPosition.coerceAtLeast(0L)
                val isPlaying = player.isPlaying
                val speed = runCatching { player.playbackParameters.speed }.getOrNull() ?: 1.0f
                _state.update { st ->
                    st.copy(
                        isPlaying = isPlaying,
                        currentChunkIndex = idx,
                        currentPositionMs = pos,
                        playbackSpeed = speed,
                    )
                }
            }
        }
    }

    override fun loadSession(chunks: List<File>) {
        val items =
            chunks.map { f ->
                val uri = Uri.fromFile(f)
                androidx.media3.common.MediaItem.fromUri(uri)
            }
        player.stop()
        player.clearMediaItems()
        player.setMediaItems(items, /* resetPosition = */ true)
        player.prepare()
        _state.update { it.copy(currentChunkIndex = 0, currentPositionMs = 0L, lastErrorMessage = null) }
    }

    override fun play() {
        player.playWhenReady = true
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun togglePlayPause() {
        if (player.isPlaying) pause() else play()
    }

    override fun seekToChunk(chunkIndex: Int, positionMs: Long) {
        val idx = chunkIndex.coerceAtLeast(0)
        val pos = positionMs.coerceAtLeast(0L)
        player.seekTo(idx, pos)
    }

    override fun setSpeed(speed: Float) {
        val s = speed.coerceIn(0.25f, 3.0f)
        runCatching { player.setPlaybackSpeed(s) }.onFailure {
            runCatching {
                val p = player.playbackParameters
                player.playbackParameters = p.withSpeed(s)
            }
        }
        _state.update { it.copy(playbackSpeed = s) }
    }

    override fun close() {
        job.cancel()
        runCatching { player.release() }
    }
}

