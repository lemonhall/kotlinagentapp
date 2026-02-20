package com.lsl.kotlin_agent_app.ui.bilingual_player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.radio_bilingual.player.BilingualSessionLoader
import com.lsl.kotlin_agent_app.radio_bilingual.player.SessionPlayerController
import com.lsl.kotlin_agent_app.radio_bilingual.player.SessionTimeline
import com.lsl.kotlin_agent_app.radio_bilingual.player.SubtitleSyncEngine
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class BilingualPlayerViewModel(
    private val workspace: AgentsWorkspace,
    private val loader: BilingualSessionLoader,
    private val player: SessionPlayerController,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    enum class DisplayMode {
        Source,
        Translation,
        Bilingual,
    }

    enum class SubtitleFontSize {
        Small,
        Medium,
        Large,
    }

    data class UiState(
        val sessionId: String? = null,
        val chunks: List<File> = emptyList(),
        val chunkCount: Int = 0,
        val currentChunkIndex: Int = 0,
        val currentChunkDisplayIndex: Int = 1,
        val isPlaying: Boolean = false,
        val currentPositionMs: Long = 0L,
        val totalPositionMs: Long = 0L,
        val totalDurationMs: Long = 0L,
        val playbackSpeed: Float = 1.0f,
        val segments: List<SubtitleSyncEngine.SubtitleSegment> = emptyList(),
        val currentSegmentIndex: Int = -1,
        val displayMode: DisplayMode = DisplayMode.Bilingual,
        val subtitleFontSize: SubtitleFontSize = SubtitleFontSize.Medium,
        val autoScrollEnabled: Boolean = true,
        val lastErrorCode: String? = null,
        val lastErrorMessage: String? = null,
        val hadSubtitleLoadError: Boolean = false,
    )

    private var timeline: SessionTimeline? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            player.state.collect { ps ->
                val tl = timeline
                val totalPos =
                    if (tl == null) {
                        0L
                    } else {
                        tl.toTotalPositionMs(ps.currentChunkIndex, ps.currentPositionMs)
                    }
                val segs = _state.value.segments
                val curSeg =
                    SubtitleSyncEngine.findCurrentSegmentIndex(
                        segments = segs,
                        totalPositionMs = totalPos,
                        toleranceMs = 200L,
                    )
                _state.update { st ->
                    val cc = st.chunkCount.coerceAtLeast(0)
                    val curChunk = ps.currentChunkIndex.coerceIn(0, (cc - 1).coerceAtLeast(0))
                    st.copy(
                        isPlaying = ps.isPlaying,
                        currentChunkIndex = curChunk,
                        currentChunkDisplayIndex = (curChunk + 1).coerceAtLeast(1),
                        currentPositionMs = ps.currentPositionMs.coerceAtLeast(0L),
                        totalPositionMs = totalPos,
                        playbackSpeed = ps.playbackSpeed,
                        currentSegmentIndex = curSeg,
                        lastErrorMessage = ps.lastErrorMessage,
                    )
                }
            }
        }
    }

    fun loadSession(sessionId: String) {
        val sid = sessionId.trim()
        if (sid.isBlank()) return
        viewModelScope.launch {
            try {
                val loaded =
                    withContext(ioDispatcher) {
                        loader.load(sid)
                    }
                timeline = loaded.timeline
                player.loadSession(loaded.chunks.map { it.file })
                _state.update { st ->
                    st.copy(
                        sessionId = loaded.sessionId,
                        chunks = loaded.chunks.map { it.file },
                        chunkCount = loaded.chunks.size,
                        totalDurationMs = loaded.timeline.totalDurationMs,
                        segments = loaded.segments,
                        hadSubtitleLoadError = loaded.hadSubtitleLoadError,
                        lastErrorCode = null,
                        lastErrorMessage = null,
                    )
                }
            } catch (t: Throwable) {
                val (code, msg) =
                    when (t) {
                        is BilingualSessionLoader.LoadError.SessionNotFound -> "SessionNotFound" to t.message
                        is BilingualSessionLoader.LoadError.SessionNoChunks -> "SessionNoChunks" to t.message
                        else -> "Unknown" to (t.message ?: "unknown")
                    }
                _state.update { st ->
                    st.copy(lastErrorCode = code, lastErrorMessage = msg)
                }
            }
        }
    }

    fun togglePlayPause() = player.togglePlayPause()

    fun seekToTotalPositionMs(totalPositionMs: Long) {
        val tl = timeline ?: return
        val cp = tl.locate(totalPositionMs)
        player.seekToChunk(cp.chunkIndex, cp.positionInChunkMs)
        player.play()
    }

    fun seekToSegment(index: Int) {
        val seg = _state.value.segments.getOrNull(index) ?: return
        seekToTotalPositionMs(seg.totalStartMs)
    }

    fun seekToPrevSegment() {
        val idx = _state.value.currentSegmentIndex
        if (idx <= 0) return
        seekToSegment(idx - 1)
    }

    fun seekToNextSegment() {
        val idx = _state.value.currentSegmentIndex
        val last = _state.value.segments.lastIndex
        if (idx < 0 || idx >= last) return
        seekToSegment(idx + 1)
    }

    fun cycleSpeed() {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val cur = _state.value.playbackSpeed
        val idx = speeds.indexOfFirst { kotlin.math.abs(it - cur) < 0.001f }.let { if (it < 0) 2 else it }
        val next = speeds[(idx + 1) % speeds.size]
        player.setSpeed(next)
        _state.update { it.copy(playbackSpeed = next) }
    }

    fun setDisplayMode(mode: DisplayMode) {
        _state.update { it.copy(displayMode = mode) }
    }

    fun setSubtitleFontSize(size: SubtitleFontSize) {
        _state.update { it.copy(subtitleFontSize = size) }
    }

    fun setAutoScrollEnabled(enabled: Boolean) {
        _state.update { it.copy(autoScrollEnabled = enabled) }
    }

    override fun onCleared() {
        runCatching { player.close() }
        super.onCleared()
    }

    class Factory(
        private val workspace: AgentsWorkspace,
        private val loader: BilingualSessionLoader,
        private val player: SessionPlayerController,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(BilingualPlayerViewModel::class.java)) {
                return BilingualPlayerViewModel(workspace = workspace, loader = loader, player = player) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

