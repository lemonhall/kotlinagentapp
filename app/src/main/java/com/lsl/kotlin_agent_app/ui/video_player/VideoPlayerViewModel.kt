package com.lsl.kotlin_agent_app.ui.video_player

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import com.lsl.kotlin_agent_app.agent.AgentsDirEntryType
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.smb_media.SmbMediaMime
import com.lsl.kotlin_agent_app.smb_media.SmbMediaRuntime
import com.lsl.kotlin_agent_app.smb_media.SmbMediaTicketSpec
import com.lsl.kotlin_agent_app.smb_media.SmbMediaUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLConnection

class VideoPlayerViewModel(
    private val appContext: Context,
) : ViewModel() {

    data class PlaylistItem(
        val uri: Uri,
        val mime: String,
        val displayName: String,
        val agentsPath: String?,
    )

    private val _displayName = MutableStateFlow("视频")
    val displayName: StateFlow<String> = _displayName

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex

    private val _playlistSize = MutableStateFlow(1)
    val playlistSize: StateFlow<Int> = _playlistSize

    private val _currentAgentsPath = MutableStateFlow<String?>(null)
    val currentAgentsPath: StateFlow<String?> = _currentAgentsPath

    private var loadedKey: String? = null
    private var playlist: List<PlaylistItem> = emptyList()

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

                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            val idx = p.currentMediaItemIndex.coerceAtLeast(0)
                            _currentIndex.value = idx
                            val it = playlist.getOrNull(idx)
                            if (it != null) {
                                _displayName.value = it.displayName.ifBlank { "视频" }
                                _currentAgentsPath.value = it.agentsPath
                            }
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
        agentsPath: String?,
    ) {
        val key = "${uri}::${agentsPath.orEmpty()}"
        val prev = loadedKey
        if (!prev.isNullOrBlank() && prev == key) return
        loadedKey = key

        _errorMessage.value = null
        _displayName.value = displayName.ifBlank { "视频" }
        _currentAgentsPath.value = agentsPath

        if (agentsPath.isNullOrBlank() || !agentsPath.trim().replace('\\', '/').startsWith(".agents/")) {
            val item =
                MediaItem.Builder()
                    .setUri(uri)
                    .apply { if (!mime.isNullOrBlank()) setMimeType(mime) }
                    .build()
            playlist = listOf(PlaylistItem(uri = uri, mime = mime ?: "video/*", displayName = displayName, agentsPath = agentsPath))
            _playlistSize.value = 1
            _currentIndex.value = 0
            player.setMediaItem(item)
            player.prepare()
            player.playWhenReady = true
            return
        }

        viewModelScope.launch {
            val built =
                withContext(Dispatchers.IO) {
                    buildPlaylistForAgentsPath(agentsPath = agentsPath)
                }
            if (built.items.isEmpty()) {
                val item =
                    MediaItem.Builder()
                        .setUri(uri)
                        .apply { if (!mime.isNullOrBlank()) setMimeType(mime) }
                        .build()
                playlist = listOf(PlaylistItem(uri = uri, mime = mime ?: "video/*", displayName = displayName, agentsPath = agentsPath))
                _playlistSize.value = 1
                _currentIndex.value = 0
                player.setMediaItem(item)
                player.prepare()
                player.playWhenReady = true
                return@launch
            }

            val items = built.items
            val startIndex = built.startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            playlist = items
            _playlistSize.value = items.size.coerceAtLeast(1)
            _currentIndex.value = startIndex
            val mediaItems =
                items.map { it2 ->
                    MediaItem.Builder()
                        .setUri(it2.uri)
                        .setMimeType(it2.mime)
                        .build()
                }
            player.setMediaItems(mediaItems, startIndex, /* startPositionMs = */ 0L)
            player.prepare()
            player.playWhenReady = true
        }
    }

    fun canPlayNext(): Boolean {
        val idx = _currentIndex.value
        val size = _playlistSize.value
        return size > 1 && idx >= 0 && idx < size - 1
    }

    fun playNext() {
        if (!canPlayNext()) return
        runCatching { player.seekToNextMediaItem() }
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

    private data class BuiltPlaylist(
        val items: List<PlaylistItem>,
        val startIndex: Int,
    )

    private fun buildPlaylistForAgentsPath(agentsPath: String): BuiltPlaylist {
        val workspace = AgentsWorkspace(appContext)
        val normalized = agentsPath.trim().replace('\\', '/').trimStart('/')
        val parent = workspace.parentDir(normalized) ?: ".agents"
        val baseName = normalized.substringAfterLast('/', missingDelimiterValue = "")
        if (baseName.isBlank()) return BuiltPlaylist(emptyList(), 0)
        val curExt = baseName.substringAfterLast('.', missingDelimiterValue = "").lowercase()

        val entries =
            runCatching { workspace.listDir(parent) }.getOrNull().orEmpty()
        val files =
            entries
                .asSequence()
                .filter { it.type == AgentsDirEntryType.File }
                .map { it.name }
                .toList()

        val filteredSameExt =
            if (curExt.isNotBlank()) {
                files.filter { n -> n.substringAfterLast('.', missingDelimiterValue = "").lowercase() == curExt }
            } else {
                emptyList()
            }

        val candidates =
            (filteredSameExt.takeIf { it.isNotEmpty() } ?: files.filter { n -> isVideoName(n) })
                .sortedBy { it.lowercase() }

        val items =
            candidates.mapNotNull { name ->
                val full = workspace.joinPath(parent, name)
                createPlaylistItem(full, displayName = name)
            }

        val startIndex =
            items.indexOfFirst { it.displayName == baseName || it.agentsPath == normalized }
                .takeIf { it >= 0 } ?: 0

        return BuiltPlaylist(items = items, startIndex = startIndex)
    }

    private fun createPlaylistItem(
        agentsPath: String,
        displayName: String,
    ): PlaylistItem? {
        val p = agentsPath.replace('\\', '/').trim().trimStart('/')
        val mime = SmbMediaMime.fromFileNameOrNull(displayName) ?: "video/*"

        if (isInNasSmbTree(p)) {
            val ref = com.lsl.kotlin_agent_app.smb_media.SmbMediaAgentsPath.parseNasSmbFile(p) ?: return null
            val ticket =
                SmbMediaRuntime.ticketStore(appContext).issue(
                    SmbMediaTicketSpec(
                        mountName = ref.mountName,
                        remotePath = ref.relPath,
                        mime = mime,
                        sizeBytes = -1L,
                    )
                )
            val uri = Uri.parse(SmbMediaUri.build(token = ticket.token, displayName = displayName))
            return PlaylistItem(uri = uri, mime = mime, displayName = displayName, agentsPath = p)
        }

        if (!p.startsWith(".agents/")) return null
        val file = File(appContext.filesDir, p)
        if (!file.exists() || !file.isFile) return null
        val uri =
            androidx.core.content.FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file,
            )
        val mime2 = URLConnection.guessContentTypeFromName(file.name) ?: mime
        return PlaylistItem(uri = uri, mime = mime2, displayName = displayName, agentsPath = p)
    }

    private fun isInNasSmbTree(agentsPath: String): Boolean {
        val p = agentsPath.replace('\\', '/').trim().trimStart('/').trimEnd('/')
        if (p == ".agents/nas_smb") return true
        if (!p.startsWith(".agents/nas_smb/")) return false
        if (p.startsWith(".agents/nas_smb/secrets")) return false
        return true
    }

    private fun isVideoName(name: String): Boolean {
        val n = name.trim().lowercase()
        return n.endsWith(".mp4") ||
            n.endsWith(".m4v") ||
            n.endsWith(".mkv") ||
            n.endsWith(".webm") ||
            n.endsWith(".mov") ||
            n.endsWith(".avi") ||
            n.endsWith(".3gp") ||
            n.endsWith(".ts") ||
            n.endsWith(".flv") ||
            n.endsWith(".wmv") ||
            n.endsWith(".mpg") ||
            n.endsWith(".mpeg")
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
