package com.lsl.kotlin_agent_app.media

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.withContext

class MusicPlayerController(
    private val appContext: Context,
    private val transport: MusicTransport,
    private val metadataReader: Mp3MetadataReader = Mp3MetadataReader(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(MusicNowPlayingState())
    val state: StateFlow<MusicNowPlayingState> = _state.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                delay(500)
                val pos = transport.currentPositionMs().coerceAtLeast(0L)
                val dur = transport.durationMs()?.takeIf { it > 0L }
                _state.update { st ->
                    if (st.agentsPath.isNullOrBlank()) st
                    else st.copy(
                        positionMs = pos,
                        durationMs = dur ?: st.durationMs,
                        isPlaying = transport.isPlaying(),
                    )
                }
            }
        }
    }

    fun close() {
        job.cancel()
    }

    fun playAgentsMp3(agentsPath: String) {
        val p = agentsPath.trim()
        if (!isInMusicsTree(p)) {
            _state.update { it.copy(errorMessage = "仅允许播放 musics/ 目录下的 mp3") }
            return
        }
        scope.launch {
            val warning = computeNotificationWarning()
            val file = File(appContext.filesDir, p)
            val metadata =
                withContext(ioDispatcher) {
                    metadataReader.readBestEffort(file)
                }
            try {
                transport.play(MusicPlaybackRequest(agentsPath = p, file = file, metadata = metadata))
                _state.update {
                    it.copy(
                        agentsPath = p,
                        title = metadata.title,
                        artist = metadata.artist,
                        durationMs = metadata.durationMs,
                        isPlaying = true,
                        warningMessage = warning,
                        errorMessage = null,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(errorMessage = t.message ?: "播放失败") }
            }
        }
    }

    fun togglePlayPause() {
        scope.launch {
            try {
                if (transport.isPlaying()) {
                    transport.pause()
                    _state.update { it.copy(isPlaying = false, errorMessage = null) }
                } else {
                    transport.resume()
                    _state.update { it.copy(isPlaying = true, errorMessage = null) }
                }
            } catch (t: Throwable) {
                _state.update { it.copy(errorMessage = t.message ?: "操作失败") }
            }
        }
    }

    fun stop() {
        scope.launch {
            try {
                transport.stop()
                _state.update { MusicNowPlayingState() }
            } catch (t: Throwable) {
                _state.update { it.copy(errorMessage = t.message ?: "停止失败") }
            }
        }
    }

    private fun isInMusicsTree(agentsPath: String): Boolean {
        val p = agentsPath.replace('\\', '/').trim().trimStart('/')
        return p == ".agents/workspace/musics" || p.startsWith(".agents/workspace/musics/")
    }

    private fun computeNotificationWarning(): String? {
        if (Build.VERSION.SDK_INT < 33) return null
        return try {
            val enabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled()
            if (enabled) null else "通知权限未开启：后台/锁屏播放可能被系统终止（请到 musics/ 里的“排障”查看）"
        } catch (_: Throwable) {
            null
        }
    }
}
