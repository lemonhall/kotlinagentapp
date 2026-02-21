package com.lsl.kotlin_agent_app.media

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.lsl.kotlin_agent_app.config.AppPrefsKeys
import com.lsl.kotlin_agent_app.listening_history.ListeningHistoryStore
import com.lsl.kotlin_agent_app.radios.RadioStationFileV1
import com.lsl.kotlin_agent_app.radios.StreamResolutionClassification
import com.lsl.kotlin_agent_app.radios.StreamUrlResolver
import com.lsl.kotlin_agent_app.smb_media.SmbMediaUri
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

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
    private val listeningHistory = ListeningHistoryStore(appContext)

    private val queueCtrl = PlaybackQueueController()
    private val streamUrlResolver = StreamUrlResolver()

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
                val snap = transport.snapshot()
                val pos = snap.positionMs.coerceAtLeast(0L)
                val dur = snap.durationMs?.takeIf { it > 0L }
                val q = queueCtrl.snapshot()
                _state.update { st ->
                    val recoveredAgentsPath = st.agentsPath?.trim()?.ifBlank { null } ?: snap.mediaId?.trim()?.ifBlank { null }
                    if (recoveredAgentsPath == null) return@update st

                    val isLiveRecovered = recoveredAgentsPath.lowercase().endsWith(".radio")
                    val nextSt = st.copy(agentsPath = recoveredAgentsPath, isLive = st.isLive || isLiveRecovered)

                    val playbackState = computePlaybackState(nextSt, snap)
                    val isPlaying = snap.isPlaying || (snap.playWhenReady && playbackState == MusicPlaybackState.Playing)

                    nextSt.copy(
                        positionMs = pos,
                        durationMs = dur ?: nextSt.durationMs,
                        isPlaying = isPlaying,
                        playbackState = playbackState,
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

    fun playNasSmbContentMp3(
        agentsPath: String,
        contentUriString: String,
        displayName: String? = null,
    ) {
        val p = normalizeAgentsPathInput(agentsPath)
        val uri = contentUriString.trim()
        val okPath = p.replace('\\', '/').trim().startsWith(".agents/nas_smb/")
        val okUri = uri.startsWith("content://${SmbMediaUri.AUTHORITY}/")
        if (!okPath || !okUri) {
            _state.update { it.copy(playbackState = MusicPlaybackState.Error, errorMessage = "仅允许播放 nas_smb/ 下的 mp3（content://）") }
            return
        }
        scope.launch { runCatching { playNasSmbContentMp3Now(agentsPath = p, uriString = uri, displayName = displayName) } }
    }

    fun playAgentsRadio(agentsPath: String) {
        val p = normalizeAgentsPathInput(agentsPath)
        if (!isInRadiosTree(p) || !p.lowercase().endsWith(".radio")) {
            _state.update { it.copy(playbackState = MusicPlaybackState.Error, errorMessage = "仅允许播放 radios/ 目录下的 .radio") }
            return
        }
        scope.launch { runCatching { playAgentsRadioNow(p) } }
    }

    fun playAgentsRecordingOgg(agentsPath: String) {
        val p = normalizeAgentsPathInput(agentsPath)
        if (!isInRadioRecordingsTree(p) || !p.lowercase().endsWith(".ogg")) {
            _state.update { it.copy(playbackState = MusicPlaybackState.Error, errorMessage = "仅允许播放 radio_recordings/ 目录下的 .ogg") }
            return
        }
        scope.launch { runCatching { playAgentsRecordingOggNow(p) } }
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

    suspend fun statusSnapshot(): MusicNowPlayingState = statusSnapshotWithTransport().nowPlaying

    suspend fun statusSnapshotWithTransport(): MusicStatusSnapshot {
        return withContext(Dispatchers.Main.immediate) {
            runCatching { transport.connect() }
            val st = _state.value
            val snap = transport.snapshot()
            val pos = snap.positionMs.coerceAtLeast(0L)
            val dur = snap.durationMs?.takeIf { it > 0L } ?: st.durationMs
            val q = queueCtrl.snapshot()
            val qi = q.index
            val qs = q.items.size.takeIf { it > 0 }
            val recoveredAgentsPath = st.agentsPath?.trim()?.ifBlank { null } ?: snap.mediaId?.trim()?.ifBlank { null }
            val isLiveRecovered = recoveredAgentsPath?.lowercase()?.endsWith(".radio") == true
            val st2 =
                if (recoveredAgentsPath == null) {
                    st
                } else {
                    st.copy(agentsPath = recoveredAgentsPath, isLive = st.isLive || isLiveRecovered)
                }
            val playbackState = computePlaybackState(st2, snap)
            val isPlaying = snap.isPlaying || (snap.playWhenReady && playbackState == MusicPlaybackState.Playing)
            val nowPlaying =
                st.copy(
                    agentsPath = st2.agentsPath,
                    isLive = st2.isLive,
                    isPlaying = isPlaying,
                    positionMs = pos,
                    durationMs = dur,
                    playbackState = playbackState,
                    queueIndex = qi,
                    queueSize = qs,
                    playbackMode = playbackMode,
                    volume = volume,
                    isMuted = isMuted,
                )
            MusicStatusSnapshot(nowPlaying = nowPlaying, transport = snap)
        }
    }

    private fun computePlaybackState(
        st: MusicNowPlayingState,
        snap: MusicTransportSnapshot,
    ): MusicPlaybackState {
        return when {
            st.playbackState == MusicPlaybackState.Error -> MusicPlaybackState.Error
            st.playbackState == MusicPlaybackState.Stopped -> MusicPlaybackState.Stopped
            st.agentsPath.isNullOrBlank() -> MusicPlaybackState.Idle
            !snap.isConnected -> st.playbackState
            snap.isPlaying -> MusicPlaybackState.Playing
            snap.playWhenReady -> MusicPlaybackState.Playing // preparing/buffering: treat as playing-in-progress
            else -> MusicPlaybackState.Paused
        }
    }

    suspend fun playAgentsMp3Now(agentsPathInput: String) {
        withContext(Dispatchers.Main.immediate) {
            val p = normalizeAgentsPathInput(agentsPathInput)
            if (!isInMusicsTree(p)) {
                _state.update { it.copy(playbackState = MusicPlaybackState.Error, errorMessage = "仅允许播放 musics/ 目录下的音频文件") }
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

            try {
                transport.play(
                    MusicPlaybackRequest(
                        agentsPath = p,
                        uri = Uri.fromFile(file).toString(),
                        metadata =
                            MusicMediaMetadata(
                                title = metadata.title,
                                artist = metadata.artist,
                                album = metadata.album,
                                durationMs = metadata.durationMs,
                            ),
                        isLive = false,
                    )
                )
            } catch (t: Throwable) {
                logErrorBestEffort(source = "music", item = buildMusicItem(p), code = "PlayFailed", message = sanitizeErrorMessage(t.message))
                throw t
            }
            applyVolumeToTransportNow()
            _state.update {
                it.copy(
                    agentsPath = p,
                    isLive = false,
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

            logEventBestEffort(source = "music", action = "play", item = buildMusicItem(p), userInitiated = true)
        }
    }

    private suspend fun playNasSmbContentMp3Now(
        agentsPath: String,
        uriString: String,
        displayName: String?,
    ) {
        withContext(Dispatchers.Main.immediate) {
            val p = normalizeAgentsPathInput(agentsPath)
            val title = displayName?.trim()?.ifBlank { null } ?: p.substringAfterLast('/', missingDelimiterValue = p)
            val warning = computeNotificationWarning()

            val q = queueCtrl.setQueue(newItems = listOf(p), current = p)
            val i = q.index?.coerceAtLeast(0) ?: 0

            try {
                transport.play(
                    MusicPlaybackRequest(
                        agentsPath = p,
                        uri = Uri.parse(uriString).toString(),
                        metadata = MusicMediaMetadata(title = title),
                        isLive = false,
                    )
                )
            } catch (t: Throwable) {
                logErrorBestEffort(source = "music", item = buildMusicItem(p), code = "PlayFailed", message = sanitizeErrorMessage(t.message))
                throw t
            }
            applyVolumeToTransportNow()
            _state.update {
                it.copy(
                    agentsPath = p,
                    isLive = false,
                    title = title,
                    artist = null,
                    album = null,
                    durationMs = null,
                    positionMs = 0L,
                    isPlaying = true,
                    playbackState = MusicPlaybackState.Playing,
                    queueIndex = i,
                    queueSize = q.items.size.takeIf { it > 0 },
                    playbackMode = playbackMode,
                    volume = volume,
                    isMuted = isMuted,
                    coverArtBytes = null,
                    lyrics = null,
                    warningMessage = warning,
                    errorMessage = null,
                )
            }

            logEventBestEffort(source = "music", action = "play", item = buildMusicItem(p), userInitiated = true)
        }
    }

    suspend fun playAgentsRadioNow(
        agentsPathInput: String,
        streamUrlOverride: String? = null,
    ) {
        withContext(Dispatchers.Main.immediate) {
            val p = normalizeAgentsPathInput(agentsPathInput)
            if (!isInRadiosTree(p) || !p.lowercase().endsWith(".radio")) {
                _state.update { it.copy(playbackState = MusicPlaybackState.Error, errorMessage = "仅允许播放 radios/ 目录下的 .radio") }
                throw IllegalArgumentException("path not allowed: $agentsPathInput")
            }

            val warning = computeNotificationWarning()
            val file = File(appContext.filesDir, p)
            val raw =
                withContext(ioDispatcher) {
                    if (!file.exists() || !file.isFile) error("not a file: $p")
                    file.readText(Charsets.UTF_8)
                }
            val station = RadioStationFileV1.parse(raw)

            val (newQueue, _) = buildRadioQueueNow(currentAgentsPath = p)
            val q = queueCtrl.setQueue(newQueue, current = p)

            val radioItem = buildRadioItem(path = p, station = station)
            val urlToPlay =
                if (!streamUrlOverride.isNullOrBlank()) {
                    streamUrlOverride.trim()
                } else {
                    val resolved =
                        withContext(ioDispatcher) {
                            runCatching { streamUrlResolver.resolve(station.streamUrl) }.getOrNull()
                        }
                    when {
                        resolved == null -> station.streamUrl
                        resolved.classification == StreamResolutionClassification.Hls -> resolved.finalUrl
                        resolved.candidates.isNotEmpty() -> resolved.candidates.first()
                        resolved.finalUrl.isNotBlank() -> resolved.finalUrl
                        else -> station.streamUrl
                    }
                }
            try {
                transport.play(
                    MusicPlaybackRequest(
                        agentsPath = p,
                        uri = urlToPlay,
                        metadata =
                            MusicMediaMetadata(
                                title = station.name,
                                artist = station.country ?: station.language,
                                album = "Radio",
                                durationMs = null,
                            ),
                        isLive = true,
                    )
                )
            } catch (t: Throwable) {
                logErrorBestEffort(source = "radio", item = radioItem, code = "PlayFailed", message = sanitizeErrorMessage(t.message))
                throw t
            }
            applyVolumeToTransportNow()
            _state.update {
                it.copy(
                    agentsPath = p,
                    isLive = true,
                    title = station.name,
                    artist = station.country ?: station.language,
                    album = "Radio",
                    durationMs = null,
                    positionMs = 0L,
                    isPlaying = true,
                    playbackState = MusicPlaybackState.Playing,
                    queueIndex = q.index,
                    queueSize = q.items.size.takeIf { it > 0 },
                    playbackMode = playbackMode,
                    volume = volume,
                    isMuted = isMuted,
                    coverArtBytes = null,
                    lyrics = null,
                    warningMessage = warning,
                    errorMessage = null,
                )
            }

            logEventBestEffort(source = "radio", action = "play", item = radioItem, userInitiated = true)
        }
    }

    suspend fun playAgentsRecordingOggNow(agentsPathInput: String) {
        withContext(Dispatchers.Main.immediate) {
            val p = normalizeAgentsPathInput(agentsPathInput)
            if (!isInRadioRecordingsTree(p) || !p.lowercase().endsWith(".ogg")) {
                _state.update { it.copy(playbackState = MusicPlaybackState.Error, errorMessage = "仅允许播放 recordings/ 或 radio_recordings/ 目录下的 .ogg") }
                throw IllegalArgumentException("path not allowed: $agentsPathInput")
            }

            val warning = computeNotificationWarning()
            val file = File(appContext.filesDir, p)
            withContext(ioDispatcher) {
                if (!file.exists() || !file.isFile) error("not a file: $p")
            }

            val sessionId =
                run {
                    val normalized = p.replace('\\', '/')
                    val rel =
                        when {
                            normalized.startsWith(".agents/workspace/radio_recordings/") -> normalized.removePrefix(".agents/workspace/radio_recordings/")
                            normalized.startsWith(".agents/workspace/recordings/") -> normalized.removePrefix(".agents/workspace/recordings/")
                            else -> normalized
                        }
                    rel.substringBefore('/').trim().ifBlank { null }
                }

            val (newQueue, _) = buildRecordingOggQueueNow(currentAgentsPath = p)
            val q = queueCtrl.setQueue(newQueue, current = p)

            try {
                transport.play(
                    MusicPlaybackRequest(
                        agentsPath = p,
                        uri = Uri.fromFile(file).toString(),
                        metadata =
                            MusicMediaMetadata(
                                title = file.name,
                                artist = "Recording",
                                album =
                                    sessionId
                                        ?: run {
                                            val normalized = p.replace('\\', '/')
                                            when {
                                                normalized.startsWith(".agents/workspace/recordings/") -> "recordings"
                                                else -> "radio_recordings"
                                            }
                                        },
                                durationMs = null,
                            ),
                        isLive = false,
                    )
                )
            } catch (t: Throwable) {
                logErrorBestEffort(source = "recording", item = buildMusicItem(p), code = "PlayFailed", message = sanitizeErrorMessage(t.message))
                throw t
            }
            applyVolumeToTransportNow()
            _state.update {
                it.copy(
                    agentsPath = p,
                    isLive = false,
                    title = file.name,
                    artist = "Recording",
                    album = sessionId ?: "radio_recordings",
                    durationMs = null,
                    positionMs = 0L,
                    isPlaying = true,
                    playbackState = MusicPlaybackState.Playing,
                    queueIndex = q.index,
                    queueSize = q.items.size.takeIf { it > 0 },
                    playbackMode = playbackMode,
                    volume = volume,
                    isMuted = isMuted,
                    coverArtBytes = null,
                    lyrics = null,
                    warningMessage = warning,
                    errorMessage = null,
                )
            }

            logEventBestEffort(source = "recording", action = "play", item = buildMusicItem(p), userInitiated = true)
        }
    }

    suspend fun pauseNow() {
        withContext(Dispatchers.Main.immediate) {
            val before = _state.value
            transport.pause()
            _state.update {
                it.copy(
                    isPlaying = false,
                    playbackState = if (it.agentsPath.isNullOrBlank()) MusicPlaybackState.Idle else MusicPlaybackState.Paused,
                    errorMessage = null,
                )
            }
            logEventBestEffortFromState(action = "pause", before = before)
        }
    }

    suspend fun resumeNow() {
        withContext(Dispatchers.Main.immediate) {
            val before = _state.value
            transport.resume()
            applyVolumeToTransportNow()
            _state.update {
                it.copy(
                    isPlaying = true,
                    playbackState = if (it.agentsPath.isNullOrBlank()) MusicPlaybackState.Idle else MusicPlaybackState.Playing,
                    errorMessage = null,
                )
            }
            logEventBestEffortFromState(action = "resume", before = before)
        }
    }

    suspend fun stopNow() {
        withContext(Dispatchers.Main.immediate) {
            val before = _state.value
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
            logEventBestEffortFromState(action = "stop", before = before)
        }
    }

    suspend fun seekToNow(positionMs: Long) {
        withContext(Dispatchers.Main.immediate) {
            val ms = positionMs.coerceAtLeast(0L)
            transport.seekTo(ms)
            _state.update { it.copy(positionMs = ms, errorMessage = null) }
        }
    }

    suspend fun nextNow() {
        withContext(Dispatchers.Main.immediate) {
            val next = queueCtrl.manualNextIndex() ?: return@withContext
            playQueueIndexNow(next)
        }
    }

    suspend fun prevNow() {
        withContext(Dispatchers.Main.immediate) {
            val prev = queueCtrl.manualPrevIndex() ?: return@withContext
            playQueueIndexNow(prev)
        }
    }

    private fun isInMusicsTree(agentsPath: String): Boolean {
        val p = agentsPath.replace('\\', '/').trim().trimStart('/')
        return p == ".agents/workspace/musics" || p.startsWith(".agents/workspace/musics/")
    }

    private fun isInRadiosTree(agentsPath: String): Boolean {
        val p = agentsPath.replace('\\', '/').trim().trimStart('/')
        return p == ".agents/workspace/radios" || p.startsWith(".agents/workspace/radios/")
    }

    private fun isInRadioRecordingsTree(agentsPath: String): Boolean {
        val p = agentsPath.replace('\\', '/').trim().trimStart('/')
        return p == ".agents/workspace/radio_recordings" ||
            p.startsWith(".agents/workspace/radio_recordings/") ||
            p == ".agents/workspace/recordings" ||
            p.startsWith(".agents/workspace/recordings/")
    }

    private fun normalizeAgentsPathInput(raw: String): String {
        val p0 = raw.replace('\\', '/').trim().trimStart('/')
        return when {
            p0.startsWith(".agents/") -> p0
            p0.startsWith("workspace/") -> ".agents/$p0"
            p0.startsWith("musics/") -> ".agents/workspace/$p0"
            p0.startsWith("radios/") -> ".agents/workspace/$p0"
            p0.startsWith("radio_recordings/") -> ".agents/workspace/$p0"
            p0.startsWith("recordings/") -> ".agents/workspace/$p0"
            else -> p0
        }
    }

    private fun buildDeterministicQueue(currentAgentsPath: String): Pair<List<String>, Int> {
        val musicsDir = File(appContext.filesDir, ".agents/workspace/musics")
        val exts = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus")
        val candidates =
            musicsDir
                .walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in exts }
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

    private suspend fun buildRadioQueueNow(currentAgentsPath: String): Pair<List<String>, Int> {
        val normalized = currentAgentsPath.replace('\\', '/').trim().trimStart('/')
        val parent = normalized.substringBeforeLast('/', missingDelimiterValue = normalized)
        if (parent == normalized) return listOf(currentAgentsPath) to 0

        val items =
            withContext(ioDispatcher) {
                val parentDir = File(appContext.filesDir, parent)
                parentDir.listFiles().orEmpty()
                    .filter { it.isFile && it.name.lowercase().endsWith(".radio") }
                    .map { f ->
                        val rel = parent + "/" + f.name
                        val votes =
                            runCatching {
                                RadioStationFileV1.parse(f.readText(Charsets.UTF_8)).votes ?: 0
                            }.getOrDefault(0)
                        rel to votes.toLong()
                    }
                    .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first.lowercase() })
                    .map { it.first }
            }

        val candidates =
            if (items.isEmpty()) listOf(currentAgentsPath) else items
        val idx = candidates.indexOfFirst { it == currentAgentsPath }.takeIf { it >= 0 } ?: 0
        return candidates to idx
    }

    private suspend fun buildRecordingOggQueueNow(currentAgentsPath: String): Pair<List<String>, Int> {
        val normalized = currentAgentsPath.replace('\\', '/').trim().trimStart('/')
        val parent = normalized.substringBeforeLast('/', missingDelimiterValue = normalized)
        if (parent == normalized) return listOf(currentAgentsPath) to 0

        val candidates =
            withContext(ioDispatcher) {
                val parentDir = File(appContext.filesDir, parent)
                parentDir.listFiles().orEmpty()
                    .filter { it.isFile && it.name.lowercase().endsWith(".ogg") }
                    .map { f -> parent + "/" + f.name }
                    .sortedBy { it.lowercase() }
            }

        if (candidates.isEmpty()) return listOf(currentAgentsPath) to 0
        val idx = candidates.indexOfFirst { it == normalized }.takeIf { it >= 0 } ?: 0
        return candidates to idx
    }

    private suspend fun playQueueIndexNow(index: Int) {
        val q = queueCtrl.snapshot().items
        if (q.isEmpty()) return
        val i = index.coerceIn(0, q.size - 1)
        val p = q[i]
        if (p.lowercase().endsWith(".radio") && isInRadiosTree(p)) {
            playAgentsRadioNow(p)
            return
        }

        val file = File(appContext.filesDir, p)
        val (metadata, extras) =
            withContext(ioDispatcher) {
                metadataReader.readBestEffort(file) to extrasReader.readBestEffort(file)
            }

        transport.play(
            MusicPlaybackRequest(
                agentsPath = p,
                uri = Uri.fromFile(file).toString(),
                metadata =
                    MusicMediaMetadata(
                        title = metadata.title,
                        artist = metadata.artist,
                        album = metadata.album,
                        durationMs = metadata.durationMs,
                    ),
                isLive = false,
            )
        )
        applyVolumeToTransportNow()
        _state.update {
            it.copy(
                agentsPath = p,
                isLive = false,
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

    private fun buildMusicItem(agentsPath: String) =
        buildJsonObject {
            put("path", JsonPrimitive(toWorkspacePath(agentsPath)))
        }

    private fun buildRadioItem(
        path: String,
        station: RadioStationFileV1,
    ) = buildJsonObject {
        put("stationId", JsonPrimitive(station.id))
        put("radioFilePath", JsonPrimitive(toWorkspacePath(path)))
        put("name", JsonPrimitive(station.name))
        put("country", station.country?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private fun logEventBestEffort(
        source: String,
        action: String,
        item: kotlinx.serialization.json.JsonObject,
        userInitiated: Boolean,
    ) {
        try {
            listeningHistory.appendEvent(source = source, action = action, item = item, userInitiated = userInitiated)
        } catch (_: Throwable) {
            // best-effort
        }
    }

    private fun logErrorBestEffort(
        source: String,
        item: kotlinx.serialization.json.JsonObject,
        code: String,
        message: String?,
    ) {
        try {
            listeningHistory.appendEvent(
                source = source,
                action = "error",
                item = item,
                userInitiated = true,
                errorCode = code,
                errorMessage = message,
            )
        } catch (_: Throwable) {
            // best-effort
        }
    }

    private fun logEventBestEffortFromState(
        action: String,
        before: MusicNowPlayingState,
    ) {
        val p = before.agentsPath?.takeIf { it.isNotBlank() } ?: return
        val isRadio = before.isLive && p.lowercase().endsWith(".radio") && isInRadiosTree(p)
        val source = if (isRadio) "radio" else "music"
        val item =
            if (isRadio) {
                buildJsonObject {
                    put("radioFilePath", JsonPrimitive(toWorkspacePath(p)))
                    put("name", before.title?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("country", before.artist?.let { JsonPrimitive(it) } ?: JsonNull)
                }
            } else {
                buildMusicItem(p)
            }
        logEventBestEffort(source = source, action = action, item = item, userInitiated = true)
    }

    private fun toWorkspacePath(agentsPath: String): String {
        val p = agentsPath.replace('\\', '/').trim().trimStart('/').trimEnd('/')
        return p.removePrefix(".agents/").trimStart('/')
    }

    private fun sanitizeErrorMessage(message: String?): String? {
        val m = message?.trim().orEmpty()
        if (m.isBlank()) return null
        val lower = m.lowercase()
        if (lower.contains("http://") || lower.contains("https://")) return null
        if (lower.contains("token=") || lower.contains("apikey") || lower.contains("api_key")) return null
        return m.take(200)
    }
}
