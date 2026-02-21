package com.lsl.kotlin_agent_app.ui.image_viewer

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lsl.kotlin_agent_app.agent.AgentsDirEntryType
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.smb_media.SmbMediaAgentsPath
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

class ImageViewerViewModel(
    private val appContext: Context,
) : ViewModel() {

    data class ImageItem(
        val uri: Uri,
        val displayName: String,
        val mime: String,
        val agentsPath: String?,
    )

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _items = MutableStateFlow<List<ImageItem>>(emptyList())
    val items: StateFlow<List<ImageItem>> = _items

    private val _startIndex = MutableStateFlow(0)
    val startIndex: StateFlow<Int> = _startIndex

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex

    private val _currentAgentsPath = MutableStateFlow<String?>(null)
    val currentAgentsPath: StateFlow<String?> = _currentAgentsPath

    private val _currentDisplayName = MutableStateFlow("图片")
    val currentDisplayName: StateFlow<String> = _currentDisplayName

    private var loadedKey: String? = null

    fun load(
        uri: Uri,
        displayName: String,
        mime: String?,
        agentsPath: String?,
    ) {
        val key = "${uri}::${agentsPath.orEmpty()}"
        if (!loadedKey.isNullOrBlank() && loadedKey == key) return
        loadedKey = key

        _isLoading.value = true
        _errorMessage.value = null
        _items.value = emptyList()
        _startIndex.value = 0
        _currentIndex.value = 0
        _currentAgentsPath.value = agentsPath
        _currentDisplayName.value = displayName.ifBlank { "图片" }

        if (agentsPath.isNullOrBlank() || !agentsPath.trim().replace('\\', '/').startsWith(".agents/")) {
            val mime2 = mime ?: "image/*"
            val it =
                ImageItem(
                    uri = uri,
                    displayName = displayName.ifBlank { "图片" },
                    mime = mime2,
                    agentsPath = agentsPath,
                )
            _items.value = listOf(it)
            _startIndex.value = 0
            _currentIndex.value = 0
            _isLoading.value = false
            return
        }

        viewModelScope.launch {
            val built =
                withContext(Dispatchers.IO) {
                    buildImageListForAgentsPath(agentsPath = agentsPath)
                }
            if (built.items.isEmpty()) {
                val mime2 = mime ?: "image/*"
                val it =
                    ImageItem(
                        uri = uri,
                        displayName = displayName.ifBlank { "图片" },
                        mime = mime2,
                        agentsPath = agentsPath,
                    )
                _items.value = listOf(it)
                _startIndex.value = 0
                _currentIndex.value = 0
                _isLoading.value = false
                return@launch
            }
            _items.value = built.items
            _startIndex.value = built.startIndex
            setCurrentIndex(built.startIndex)
            _isLoading.value = false
        }
    }

    fun setCurrentIndex(index: Int) {
        val list = _items.value
        if (list.isEmpty()) return
        val idx = index.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        _currentIndex.value = idx
        val it = list.getOrNull(idx)
        if (it != null) {
            _currentAgentsPath.value = it.agentsPath
            _currentDisplayName.value = it.displayName.ifBlank { "图片" }
        }
    }

    private data class BuiltImages(
        val items: List<ImageItem>,
        val startIndex: Int,
    )

    private fun buildImageListForAgentsPath(agentsPath: String): BuiltImages {
        val workspace = AgentsWorkspace(appContext)
        val normalized = agentsPath.trim().replace('\\', '/').trimStart('/')
        val parent = workspace.parentDir(normalized) ?: ".agents"
        val baseName = normalized.substringAfterLast('/', missingDelimiterValue = "")
        if (baseName.isBlank()) return BuiltImages(emptyList(), 0)
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
            (filteredSameExt.takeIf { it.isNotEmpty() } ?: files.filter { n -> isImageName(n) })
                .sortedBy { it.lowercase() }

        val items =
            candidates.mapNotNull { name ->
                val full = workspace.joinPath(parent, name)
                createImageItem(full, displayName = name)
            }

        val startIndex =
            items.indexOfFirst { it.displayName == baseName || it.agentsPath == normalized }
                .takeIf { it >= 0 } ?: 0

        return BuiltImages(items = items, startIndex = startIndex)
    }

    private fun createImageItem(
        agentsPath: String,
        displayName: String,
    ): ImageItem? {
        val p = agentsPath.replace('\\', '/').trim().trimStart('/')
        val mime = SmbMediaMime.fromFileNameOrNull(displayName) ?: (URLConnection.guessContentTypeFromName(displayName) ?: "image/*")

        if (isInNasSmbTree(p)) {
            val ref = SmbMediaAgentsPath.parseNasSmbFile(p) ?: return null
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
            return ImageItem(uri = uri, displayName = displayName, mime = mime, agentsPath = p)
        }

        if (!p.startsWith(".agents/")) return null
        val file = File(appContext.filesDir, p)
        if (!file.exists() || !file.isFile) return null
        val uri =
            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file,
            )
        val mime2 = URLConnection.guessContentTypeFromName(file.name) ?: mime
        return ImageItem(uri = uri, displayName = displayName, mime = mime2, agentsPath = p)
    }

    private fun isInNasSmbTree(agentsPath: String): Boolean {
        val p = agentsPath.replace('\\', '/').trim().trimStart('/').trimEnd('/')
        if (p == ".agents/nas_smb") return true
        if (!p.startsWith(".agents/nas_smb/")) return false
        if (p.startsWith(".agents/nas_smb/secrets")) return false
        return true
    }

    private fun isImageName(name: String): Boolean {
        val n = name.trim().lowercase()
        return n.endsWith(".jpg") ||
            n.endsWith(".jpeg") ||
            n.endsWith(".png") ||
            n.endsWith(".webp") ||
            n.endsWith(".gif") ||
            n.endsWith(".bmp") ||
            n.endsWith(".heic") ||
            n.endsWith(".heif")
    }

    class Factory(private val appContext: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ImageViewerViewModel::class.java)) {
                return ImageViewerViewModel(appContext.applicationContext) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

