package com.lsl.kotlin_agent_app.media

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.lsl.kotlin_agent_app.config.AppPrefsKeys
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
    private val extrasReader: Mp3NowPlayingExtrasReader = Mp3NowPlayingExtrasReader(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val prefs = appContext.getSharedPreferences("kotlin-agent-app", Context.MODE_PRIVATE)

    private val queueCtrl = PlaybackQueueController()

    private var playbackMode: MusicPlaybackMode =
        prefs.getString(AppPrefsKeys.MUSIC_PLAYBACK_MODE, null)
            ?.trim()
            ?.let { runCatching { MusicPlaybackMode.valueOf(it) }.getOrNull() }
            ?: MusicPlaybackMode.SequentialLoop

    private var volume: Float =
        runCatching { prefs.getFloat(AppPrefsKeys.MUSIC_VOLUME, 1.0f) }.getOrNull()
            ?.coerceIn(0f, 1f)
            ?: 1.0f

    private var lastNonZeroVolume: Float =
        runCatching { prefs.getFloat(AppPrefsKeys.MUSIC_LAST_NONZERO_VOLUME, 1.0f) }.getOrNull()
            ?.coerceIn(0f, 1f)
            ?: 1.0f

    private var isMuted: Boolean = prefs.getBoolean(AppPrefsKeys.MUSIC_MUTED, false)

    private val _state =
        MutableStateFlow(
            MusicNowPlayingState(
                playbackMode = playbackMode,
                volume = volume,
                isMuted = isMuted,
                playbackState = MusicPlaybackState.Idle,
            )
        )
    val state: StateFlow<MusicNowPlayingState> = _state.asStateFlow()

    init {
        queueCtrl.setMode(playbackMode)
        transport.setListener(
            object : MusicTransportListener {
                override fun onPlaybackEnded() {
                    scope.launch {
                        runCatching { handlePlaybackEndedNow() }
                    }
                }

                override fun onPlayerError(message: String?) {
                    _state.update { it.copy(playbackState = MusicPlaybackState.Error, errorMessage = message ?: "player error") }
                }
            }
        )

        scope.launch {
            while (isActive) {
                delay(500)
                val pos = transport.currentPositionMs().coerceAtLeast(0L)
                val dur = transport.durationMs()?.takeIf { it > 0L }
                val q = queueCtrl.snapshot()
                _state.update { st ->
                    if (st.agentsPath.isNullOrBlank()) st
                    else st.copy(
                        positionMs = pos,
                        durationMs = dur ?: st.durationMs,
                        isPlaying = transport.isPlaying(),
                        playbackState =
                            if (transport.isPlaying()) MusicPlaybackState.Playing else MusicPlaybackState.Paused,
                        queueIndex = q.index,
                        queueSize = q.items.size.takeIf { it > 0 },
                    )
                }
            }
        }
    }

    fun close() {
        job.cancel()
    }

    fun playAgentsMp3(agentsPath: String) {
        val p = normalizeAgentsPathInput(agentsPath)
        if (!isInMusicsTree(p)) {
            _state.update { it.copy(playbackState = MusicPlaybackState.Error, errorMessage = "仅允许播放 musics/ 目录下的 mp3") }
            return
        }
        scope.launch { runCatching { playAgentsMp3Now(p) } }
    }

    fun togglePlayPause() {
        scope.launch {
            try {
                if (transport.isPlaying()) {
                    pauseNow()
                } else {
                    resumeNow()
                }
            } catch (t: Throwable) {
                _state.update { it.copy(playbackState = MusicPlaybackState.Error, errorMessage = t.message ?: "操作失败") }
            }
        }
    }

    fun stop() {
        scope.launch { runCatching { stopNow() } }
    }

    fun next() {
        scope.launch { runCatching { nextNow() } }
    }

    fun prev() {
        scope.launch { runCatching { prevNow() } }
    }

    fun cyclePlaybackMode() {
        scope.launch { runCatching { cyclePlaybackModeNow() } }
    }

    fun setVolume(volume: Float) {
        scope.launch { runCatching { setVolumeNow(volume) } }
    }

    fun toggleMute() {
        scope.launch { runCatching { toggleMuteNow() } }
    }

    suspend fun statusSnapshot(): MusicNowPlayingState {
        val st = _state.value
        val playing = transport.isPlaying()
        val pos = transport.currentPositionMs().coerceAtLeast(0L)
        val dur = transport.durationMs()?.takeIf { it > 0L } ?: st.durationMs
        val q = queueCtrl.snapshot()
        val qi = q.index
        val qs = q.items.size.takeIf { it > 0 }
        val playbackState =
            when {
                st.playbackState == MusicPlaybackState.Error -> MusicPlaybackState.Error
                st.playbackState == MusicPlaybackState.Stopped -> MusicPlaybackState.Stopped
                st.agentsPath.isNullOrBlank() -> MusicPlaybackState.Idle
                playing -> MusicPlaybackState.Playing
                else -> MusicPlaybackState.Paused
            }
        return st.copy(
            isPlaying = playing,
            positionMs = pos,
            durationMs = dur,
            playbackState = playbackState,
            queueIndex = qi,
            queueSize = qs,
            playbackMode = playbackMode,
            volume = volume,
            isMuted = isMuted,
        )
    }

    suspend fun playAgentsMp3Now(agentsPathInput: String) {
        val p = normalizeAgentsPathInput(agentsPathInput)
        if (!isInMusicsTree(p)) {
            _state.update { it.copy(playbackState = MusicPlaybackState.Error, errorMessage = "仅允许播放 musics/ 目录下的 mp3") }
            throw IllegalArgumentException("path not allowed: $agentsPathInput")
        }

        val warning = computeNotificationWarning()
        val file = File(appContext.filesDir, p)
        val (metadata, extras) =
            withContext(ioDispatcher) {
                metadataReader.readBestEffort(file) to extrasReader.readBestEffort(file)
            }

        val (newQueue, _) = buildDeterministicQueue(currentAgentsPath = p)
        val q = queueCtrl.setQueue(newQueue, current = p)

        transport.play(MusicPlaybackRequest(agentsPath = p, file = file, metadata = metadata))
        applyVolumeToTransportNow()
        _state.update {
            it.copy(
                agentsPath = p,
                title = metadata.title,
                artist = metadata.artist,
                album = metadata.album,
                durationMs = metadata.durationMs,
                positionMs = 0L,
                isPlaying = true,
                playbackState = MusicPlaybackState.Playing,
                queueIndex = q.index,
                queueSize = q.items.size.takeIf { it > 0 },
                playbackMode = playbackMode,
                volume = volume,
                isMuted = isMuted,
                coverArtBytes = extras.coverArtBytes,
                lyrics = extras.lyrics,
                warningMessage = warning,
                errorMessage = null,
            )
        }
    }

    suspend fun pauseNow() {
        transport.pause()
        _state.update {
            it.copy(
                isPlaying = false,
                playbackState = if (it.agentsPath.isNullOrBlank()) MusicPlaybackState.Idle else MusicPlaybackState.Paused,
                errorMessage = null,
            )
        }
    }

    suspend fun resumeNow() {
        transport.resume()
        applyVolumeToTransportNow()
        _state.update {
            it.copy(
                isPlaying = true,
                playbackState = if (it.agentsPath.isNullOrBlank()) MusicPlaybackState.Idle else MusicPlaybackState.Playing,
                errorMessage = null,
            )
        }
    }

    suspend fun stopNow() {
        transport.stop()
        queueCtrl.clear()
        _state.update {
            MusicNowPlayingState(
                playbackState = MusicPlaybackState.Stopped,
                playbackMode = playbackMode,
                volume = volume,
                isMuted = isMuted,
            )
        }
    }

    suspend fun seekToNow(positionMs: Long) {
        val ms = positionMs.coerceAtLeast(0L)
        transport.seekTo(ms)
        _state.update { it.copy(positionMs = ms, errorMessage = null) }
    }

    suspend fun nextNow() {
        val next = queueCtrl.manualNextIndex() ?: return
        playQueueIndexNow(next)
    }

    suspend fun prevNow() {
        val prev = queueCtrl.manualPrevIndex() ?: return
        playQueueIndexNow(prev)
    }

    private fun isInMusicsTree(agentsPath: String): Boolean {
        val p = agentsPath.replace('\\', '/').trim().trimStart('/')
        return p == ".agents/workspace/musics" || p.startsWith(".agents/workspace/musics/")
    }

    private fun normalizeAgentsPathInput(raw: String): String {
        val p0 = raw.replace('\\', '/').trim().trimStart('/')
        return when {
            p0.startsWith(".agents/") -> p0
            p0.startsWith("workspace/") -> ".agents/$p0"
            p0.startsWith("musics/") -> ".agents/workspace/$p0"
            else -> p0
        }
    }

    private fun buildDeterministicQueue(currentAgentsPath: String): Pair<List<String>, Int> {
        val musicsDir = File(appContext.filesDir, ".agents/workspace/musics")
        val candidates =
            musicsDir
                .walkTopDown()
                .filter { it.isFile && it.name.lowercase().endsWith(".mp3") }
                .map { f ->
                    val rel = f.relativeTo(musicsDir).path.replace('\\', '/')
                    ".agents/workspace/musics/" + rel
                }
                .toList()
                .sortedBy { it.lowercase() }

        if (candidates.isEmpty()) {
            return listOf(currentAgentsPath) to 0
        }
        val idx = candidates.indexOfFirst { it == currentAgentsPath }.takeIf { it >= 0 } ?: 0
        return candidates to idx
    }

    private suspend fun playQueueIndexNow(index: Int) {
        val q = queueCtrl.snapshot().items
        if (q.isEmpty()) return
        val i = index.coerceIn(0, q.size - 1)
        val p = q[i]
        val file = File(appContext.filesDir, p)
        val (metadata, extras) =
            withContext(ioDispatcher) {
                metadataReader.readBestEffort(file) to extrasReader.readBestEffort(file)
            }

        transport.play(MusicPlaybackRequest(agentsPath = p, file = file, metadata = metadata))
        applyVolumeToTransportNow()
        _state.update {
            it.copy(
                agentsPath = p,
                title = metadata.title,
                artist = metadata.artist,
                album = metadata.album,
                durationMs = metadata.durationMs,
                positionMs = 0L,
                isPlaying = true,
                playbackState = MusicPlaybackState.Playing,
                queueIndex = i,
                queueSize = q.size.takeIf { it > 0 },
                playbackMode = playbackMode,
                volume = volume,
                isMuted = isMuted,
                coverArtBytes = extras.coverArtBytes,
                lyrics = extras.lyrics,
                errorMessage = null,
            )
        }
    }

    private suspend fun handlePlaybackEndedNow() {
        val next = queueCtrl.onEndedNextIndex()
        if (next == null) {
            _state.update {
                it.copy(
                    isPlaying = false,
                    playbackState = if (it.agentsPath.isNullOrBlank()) MusicPlaybackState.Idle else MusicPlaybackState.Paused,
                    errorMessage = null,
                )
            }
            return
        }

        if (playbackMode == MusicPlaybackMode.RepeatOne) {
            transport.seekTo(0L)
            transport.resume()
            applyVolumeToTransportNow()
            _state.update {
                it.copy(
                    positionMs = 0L,
                    isPlaying = true,
                    playbackState = MusicPlaybackState.Playing,
                    errorMessage = null,
                )
            }
            return
        }

        playQueueIndexNow(next)
    }

    private suspend fun cyclePlaybackModeNow() {
        val next =
            when (playbackMode) {
                MusicPlaybackMode.SequentialLoop -> MusicPlaybackMode.ShuffleLoop
                MusicPlaybackMode.ShuffleLoop -> MusicPlaybackMode.RepeatOne
                MusicPlaybackMode.RepeatOne -> MusicPlaybackMode.PlayOnce
                MusicPlaybackMode.PlayOnce -> MusicPlaybackMode.SequentialLoop
            }
        playbackMode = next
        queueCtrl.setMode(next)
        prefs.edit().putString(AppPrefsKeys.MUSIC_PLAYBACK_MODE, next.name).apply()
        _state.update { it.copy(playbackMode = next, errorMessage = null) }
    }

    private suspend fun setVolumeNow(value: Float) {
        val v = value.coerceIn(0f, 1f)
        volume = v
        if (v > 0f) lastNonZeroVolume = v
        if (v == 0f) isMuted = true
        persistVolumePrefs()
        applyVolumeToTransportNow()
        _state.update { it.copy(volume = volume, isMuted = isMuted, errorMessage = null) }
    }

    private suspend fun toggleMuteNow() {
        isMuted =
            if (!isMuted) {
                if (volume > 0f) lastNonZeroVolume = volume
                true
            } else {
                false
            }
        if (!isMuted && volume == 0f) {
            volume = lastNonZeroVolume.takeIf { it > 0f } ?: 1.0f
        }
        persistVolumePrefs()
        applyVolumeToTransportNow()
        _state.update { it.copy(volume = volume, isMuted = isMuted, errorMessage = null) }
    }

    private fun persistVolumePrefs() {
        prefs.edit()
            .putFloat(AppPrefsKeys.MUSIC_VOLUME, volume.coerceIn(0f, 1f))
            .putBoolean(AppPrefsKeys.MUSIC_MUTED, isMuted)
            .putFloat(AppPrefsKeys.MUSIC_LAST_NONZERO_VOLUME, lastNonZeroVolume.coerceIn(0f, 1f))
            .apply()
    }

    private suspend fun applyVolumeToTransportNow() {
        val effective = if (isMuted) 0f else volume
        transport.setVolume(effective.coerceIn(0f, 1f))
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
