package com.lsl.kotlin_agent_app.media

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.Metadata
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Media3MusicTransport(
    private val appContext: Context,
) : MusicTransport {
    private val mutex = Mutex()
    private var controller: MediaController? = null
    private var listener: MusicTransportListener? = null
    private var isPlayerListenerAttached: Boolean = false

    private val playerListener =
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    listener?.onPlaybackEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                listener?.onPlayerError(error.message)
            }

            override fun onMetadata(metadata: Metadata) {
                // Reserved for future: lyrics/cover extraction via stream metadata.
            }
        }

    override suspend fun connect() {
        mutex.withLock {
            if (controller != null) return
            MusicPlaybackService.ensureStarted(appContext)
            val token = SessionToken(appContext, ComponentName(appContext, MusicPlaybackService::class.java))
            val future = MediaController.Builder(appContext, token).buildAsync()
            controller = future.await()
            if (!isPlayerListenerAttached) {
                controller?.addListener(playerListener)
                isPlayerListenerAttached = true
            }
        }
    }

    override suspend fun play(request: MusicPlaybackRequest) {
        connect()
        val c = controller ?: return
        val md = request.metadata
        val item =
            MediaItem.Builder()
                .setUri(Uri.fromFile(request.file))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(md.title)
                        .setArtist(md.artist)
                        .setAlbumTitle(md.album)
                        .build()
                )
                .build()
        c.setMediaItem(item)
        c.prepare()
        c.play()
    }

    override suspend fun pause() {
        connect()
        controller?.pause()
    }

    override suspend fun resume() {
        connect()
        controller?.play()
    }

    override suspend fun stop() {
        connect()
        controller?.stop()
        controller?.clearMediaItems()
    }

    override suspend fun seekTo(positionMs: Long) {
        connect()
        controller?.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun currentPositionMs(): Long = controller?.currentPosition ?: 0L

    override fun durationMs(): Long? {
        val d = controller?.duration ?: return null
        return if (d > 0L) d else null
    }

    override fun isPlaying(): Boolean = controller?.isPlaying == true

    override suspend fun setVolume(volume: Float) {
        connect()
        controller?.volume = volume.coerceIn(0f, 1f)
    }

    override fun volume(): Float? = controller?.volume

    override fun setListener(listener: MusicTransportListener?) {
        this.listener = listener
    }
}
