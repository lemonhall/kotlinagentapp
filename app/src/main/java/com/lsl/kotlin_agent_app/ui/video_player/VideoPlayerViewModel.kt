package com.lsl.kotlin_agent_app.ui.video_player

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class VideoPlayerViewModel(
    private val appContext: Context,
) : ViewModel() {

    private val _displayName = MutableStateFlow("视频")
    val displayName: StateFlow<String> = _displayName

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private var loadedUri: Uri? = null

    val player: ExoPlayer =
        ExoPlayer.Builder(appContext)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 30_000,
                        /* maxBufferMs = */ 120_000,
                        /* bufferForPlaybackMs = */ 5_000,
                        /* bufferForPlaybackAfterRebufferMs = */ 10_000,
                    )
                    .build()
            )
            .build()
            .also { p ->
                p.addListener(
                    object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            _isBuffering.value = (playbackState == Player.STATE_BUFFERING)
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            _errorMessage.value = error.message ?: error.errorCodeName
                        }
                    }
                )
            }

    fun load(
        uri: Uri,
        mime: String?,
        displayName: String,
    ) {
        _displayName.value = displayName.ifBlank { "视频" }
        val prev = loadedUri
        if (prev != null && prev == uri) return
        loadedUri = uri
        _errorMessage.value = null

        val item =
            MediaItem.Builder()
                .setUri(uri)
                .apply { if (!mime.isNullOrBlank()) setMimeType(mime) }
                .build()

        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    fun retry() {
        _errorMessage.value = null
        player.prepare()
        player.playWhenReady = true
    }

    fun pause() {
        player.playWhenReady = false
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    class Factory(private val appContext: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(VideoPlayerViewModel::class.java)) {
                return VideoPlayerViewModel(appContext.applicationContext) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

