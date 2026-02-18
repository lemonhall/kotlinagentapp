package com.lsl.kotlin_agent_app.media

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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

    override suspend fun connect() {
        mutex.withLock {
            if (controller != null) return
            MusicPlaybackService.ensureStarted(appContext)
            val token = SessionToken(appContext, ComponentName(appContext, MusicPlaybackService::class.java))
            val future = MediaController.Builder(appContext, token).buildAsync()
            controller = future.await()
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
}

