package com.lsl.kotlin_agent_app.ui.dashboard

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lsl.kotlin_agent_app.agent.AgentsDirEntry
import com.lsl.kotlin_agent_app.agent.AgentsDirEntryType
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.config.LlmConfigRepository
import com.lsl.kotlin_agent_app.config.SharedPreferencesLlmConfigRepository
import com.lsl.kotlin_agent_app.media.Mp3MetadataReader
import com.lsl.kotlin_agent_app.radio_recordings.RadioRecordingsPaths
import com.lsl.kotlin_agent_app.radio_recordings.RecordingMetaV1
import com.lsl.kotlin_agent_app.radio_transcript.TranscriptTasksIndexV1
import com.lsl.kotlin_agent_app.radios.RadioRepository
import com.lsl.kotlin_agent_app.radios.RadioStationFileV1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.providers.OpenAIResponsesHttpProvider
import me.lemonhall.openagentic.sdk.providers.ResponsesRequest
import me.lemonhall.openagentic.sdk.runtime.ProviderStreamEvent
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

class FilesViewModel(
    private val workspace: AgentsWorkspace,
    private val configRepository: LlmConfigRepository,
) : ViewModel() {

    private val _state = MutableLiveData(FilesUiState())
    val state: LiveData<FilesUiState> = _state

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private var sessionTitleJob: Job? = null
    private val radioRepo = RadioRepository(workspace)

    fun refresh(force: Boolean = false) {
        val prev = _state.value ?: FilesUiState()
        _state.value = prev.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    workspace.ensureInitialized()
                }
                val cwd = (_state.value ?: prev).cwd
                val radioMessage =
                    withContext(Dispatchers.IO) {
                        maybeSyncRadios(cwd = cwd, force = force)
                    }
                val rawEntries = withContext(Dispatchers.IO) { workspace.listDir(cwd) }
                val (entries, missingSessionIds) =
                    withContext(Dispatchers.IO) {
                        decorateEntries(cwd = cwd, entries = rawEntries)
                    }
                _state.value =
                    (_state.value ?: prev).copy(
                        entries = entries,
                        isLoading = false,
                        errorMessage = radioMessage?.trim()?.ifBlank { null },
                    )

                maybeStartSessionTitleJob(cwd = cwd, sessionIds = missingSessionIds)
            } catch (t: Throwable) {
                _state.value = (_state.value ?: prev).copy(isLoading = false, errorMessage = t.message ?: "Unknown error")
            }
        }
    }

    fun goInto(entry: AgentsDirEntry) {
        if (entry.type != AgentsDirEntryType.Dir) return
        val prev = _state.value ?: FilesUiState()
        val nextCwd = workspace.joinPath(prev.cwd, entry.name)
        _state.value = prev.copy(cwd = nextCwd)
        if (nextCwd != ".agents/sessions") {
            sessionTitleJob?.cancel()
            sessionTitleJob = null
        }
        refresh()
    }

    fun goUp() {
        val prev = _state.value ?: FilesUiState()
        val parent = workspace.parentDir(prev.cwd) ?: return
        _state.value = prev.copy(cwd = parent)
        if (parent != ".agents/sessions") {
            sessionTitleJob?.cancel()
            sessionTitleJob = null
        }
        refresh()
    }

    fun goRoot() {
        val prev = _state.value ?: FilesUiState()
        _state.value = prev.copy(cwd = ".agents")
        sessionTitleJob?.cancel()
        sessionTitleJob = null
        refresh()
    }

    fun goTo(path: String) {
        val prev = _state.value ?: FilesUiState()
        _state.value = prev.copy(cwd = path.trim().ifBlank { ".agents" })
        if ((_state.value ?: prev).cwd != ".agents/sessions") {
            sessionTitleJob?.cancel()
            sessionTitleJob = null
        }
        refresh()
    }

    fun openFile(entry: AgentsDirEntry) {
        if (entry.type != AgentsDirEntryType.File) return
        val prev = _state.value ?: FilesUiState()
        val filePath = workspace.joinPath(prev.cwd, entry.name)
        _state.value = prev.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { workspace.readTextFile(filePath) }
                val now = _state.value ?: prev
                _state.value = now.copy(isLoading = false, openFilePath = filePath, openFileText = text)
            } catch (t: Throwable) {
                val now = _state.value ?: prev
                _state.value = now.copy(isLoading = false, errorMessage = t.message ?: "Open failed")
            }
        }
    }

    fun closeEditor() {
        val prev = _state.value ?: FilesUiState()
        _state.value = prev.copy(openFilePath = null, openFileText = null)
    }

    fun saveEditor(text: String) {
        val prev = _state.value ?: FilesUiState()
        val path = prev.openFilePath ?: return
        _state.value = prev.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { workspace.writeTextFile(path, text) }
                val now = _state.value ?: prev
                _state.value = now.copy(isLoading = false, openFileText = text)
                refresh()
            } catch (t: Throwable) {
                val now = _state.value ?: prev
                _state.value = now.copy(isLoading = false, errorMessage = t.message ?: "Save failed")
            }
        }
    }

    fun createFile(name: String) {
        val prev = _state.value ?: FilesUiState()
        val filePath = workspace.joinPath(prev.cwd, name)
        _state.value = prev.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { workspace.writeTextFile(filePath, "") }
                refresh()
            } catch (t: Throwable) {
                val now = _state.value ?: prev
                _state.value = now.copy(isLoading = false, errorMessage = t.message ?: "Create file failed")
            }
        }
    }

    fun createFolder(name: String) {
        val prev = _state.value ?: FilesUiState()
        val folderPath = workspace.joinPath(prev.cwd, name)
        _state.value = prev.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { workspace.mkdir(folderPath) }
                refresh()
            } catch (t: Throwable) {
                val now = _state.value ?: prev
                _state.value = now.copy(isLoading = false, errorMessage = t.message ?: "Create folder failed")
            }
        }
    }

    fun deleteEntry(entry: AgentsDirEntry, recursive: Boolean) {
        val prev = _state.value ?: FilesUiState()
        val path = workspace.joinPath(prev.cwd, entry.name)
        _state.value = prev.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { workspace.deletePath(path, recursive = recursive) }
                val now = _state.value ?: prev
                if (now.clipboardCutPath == path) {
                    _state.postValue(now.copy(clipboardCutPath = null, clipboardCutIsDir = false))
                }
                refresh()
            } catch (t: Throwable) {
                val now = _state.value ?: prev
                _state.value = now.copy(isLoading = false, errorMessage = t.message ?: "Delete failed")
            }
        }
    }

    fun deletePath(
        path: String,
        recursive: Boolean,
    ) {
        val prev = _state.value ?: FilesUiState()
        val p = path.trim().ifBlank { return }
        _state.value = prev.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { workspace.deletePath(p, recursive = recursive) }
                val now = _state.value ?: prev
                if (now.clipboardCutPath == p) {
                    _state.postValue(now.copy(clipboardCutPath = null, clipboardCutIsDir = false))
                }
                refresh()
            } catch (t: Throwable) {
                val now = _state.value ?: prev
                _state.value = now.copy(isLoading = false, errorMessage = t.message ?: "Delete failed")
            }
        }
    }

    fun cutEntry(entry: AgentsDirEntry) {
        val prev = _state.value ?: FilesUiState()
        val path = workspace.joinPath(prev.cwd, entry.name)
        _state.value =
            prev.copy(
                clipboardCutPath = path,
                clipboardCutIsDir = (entry.type == AgentsDirEntryType.Dir),
                errorMessage = null,
            )
    }

    fun clearClipboard() {
        val prev = _state.value ?: FilesUiState()
        _state.value = prev.copy(clipboardCutPath = null, clipboardCutIsDir = false)
    }

    fun pasteCutIntoCwd() {
        val prev = _state.value ?: FilesUiState()
        val src = prev.clipboardCutPath ?: run {
            _state.value = prev.copy(errorMessage = "剪切板为空")
            return
        }
        val cwd = prev.cwd
        _state.value = prev.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    workspace.ensureInitialized()
                    val baseName = src.replace('\\', '/').trimEnd('/').substringAfterLast('/')
                    if (baseName.isBlank()) error("Invalid source name")

                    var destName = baseName
                    var destPath = workspace.joinPath(cwd, destName)
                    if (destPath == src) return@withContext

                    var n = 0
                    while (workspace.exists(destPath)) {
                        n++
                        if (n >= 1000) error("同名文件/目录过多")
                        val (stem, ext) = splitName(destName = baseName)
                        destName = "${stem}_$n$ext"
                        destPath = workspace.joinPath(cwd, destName)
                    }

                    workspace.movePath(from = src, to = destPath, overwrite = false)
                }
                val now = _state.value ?: prev
                _state.value = now.copy(clipboardCutPath = null, clipboardCutIsDir = false, isLoading = false)
                refresh()
            } catch (t: Throwable) {
                val now = _state.value ?: prev
                _state.value = now.copy(isLoading = false, errorMessage = t.message ?: "Paste failed")
            }
        }
    }

    private fun splitName(destName: String): Pair<String, String> {
        val base = destName.trim()
        val dot = base.lastIndexOf('.').takeIf { it > 0 && it < base.length - 1 }
        val stem = dot?.let { base.substring(0, it) } ?: base
        val ext = dot?.let { base.substring(it) } ?: ""
        return stem to ext
    }

    fun clearSessions() {
        val prev = _state.value ?: FilesUiState()
        _state.value = prev.copy(isLoading = true, errorMessage = null)
        sessionTitleJob?.cancel()
        sessionTitleJob = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    workspace.ensureInitialized()
                    val sessionsDir = ".agents/sessions"
                    val entries = workspace.listDir(sessionsDir)
                    for (e in entries) {
                        if (e.type != AgentsDirEntryType.Dir) continue
                        val path = workspace.joinPath(sessionsDir, e.name)
                        workspace.deletePath(path, recursive = true)
                    }
                }
                refresh()
            } catch (t: Throwable) {
                val now = _state.value ?: prev
                _state.value = now.copy(isLoading = false, errorMessage = t.message ?: "Clear sessions failed")
            }
        }
    }

    private fun decorateEntries(
        cwd: String,
        entries: List<AgentsDirEntry>,
    ): Pair<List<AgentsDirEntry>, List<String>> {
        if (cwd != ".agents/sessions") {
            return decorateWorkspaceEntries(cwd = cwd, entries = entries) to emptyList()
        }

        data class Decorated(
            val entry: AgentsDirEntry,
            val kindRank: Int,
            val lastMs: Long,
            val sessionId: String,
            val wantsAutoTitle: Boolean,
        )

        val sessions = mutableListOf<Decorated>()
        val others = mutableListOf<AgentsDirEntry>()
        val missingTitles = mutableListOf<String>()

        val sidRx = Regex("^[a-f0-9]{32}$", RegexOption.IGNORE_CASE)
        for (e in entries) {
            if (e.type == AgentsDirEntryType.Dir && sidRx.matches(e.name)) {
                val sid = e.name
                val title = readSessionTitle(sid)
                val lastMs = sessionLastActivityMs(sid) ?: sessionCreatedAtMs(sid) ?: 0L
                val identity = readSessionIdentity(sid)
                val kind = identity?.kind?.trim()?.lowercase(Locale.ROOT).orEmpty()
                val isTask = kind == "task"
                val kindRank = if (isTask) 1 else 0
                val kindLabel =
                    if (!isTask) {
                        "主会话"
                    } else {
                        val agent = identity?.agent?.trim()?.ifBlank { null }
                        if (agent != null) "子会话($agent)" else "子会话"
                    }
                val parentLabel =
                    if (isTask) {
                        identity?.parentSessionId?.trim()?.takeIf { it.length >= 8 }?.let { " · parent=${it.take(8)}" }.orEmpty()
                    } else {
                        ""
                    }
                val subtitle = (formatTimestamp(lastMs).ifBlank { "" } + " · " + kindLabel + parentLabel).trim().trim('·').trim()
                val displayName = title?.ifBlank { null } ?: sid.take(8)
                val wantsAutoTitle = !isTask
                val emoji = if (isTask) "🧩" else "💬"
                sessions.add(
                    Decorated(
                        e.copy(displayName = displayName, subtitle = subtitle, sortKey = lastMs, iconEmoji = emoji),
                        kindRank = kindRank,
                        lastMs = lastMs,
                        sessionId = sid,
                        wantsAutoTitle = wantsAutoTitle,
                    ),
                )
                if (wantsAutoTitle && title.isNullOrBlank()) missingTitles.add(sid)
            } else {
                others.add(e)
            }
        }

        val sortedSessions =
            sessions
                .sortedWith(compareBy<Decorated> { it.kindRank }.thenByDescending { it.lastMs })
                .map { it.entry }
        val sortedOthers =
            others.sortedWith(compareBy<AgentsDirEntry>({ it.type != AgentsDirEntryType.Dir }, { it.name.lowercase() }))

        return (sortedSessions + sortedOthers) to missingTitles
    }

    private fun decorateWorkspaceEntries(
        cwd: String,
        entries: List<AgentsDirEntry>,
    ): List<AgentsDirEntry> {
        var cur = entries
        cur = decorateMusicEntries(cwd = cwd, entries = cur)
        cur = decorateRadioEntries(cwd = cwd, entries = cur)
        cur = decorateRadioRecordingEntries(cwd = cwd, entries = cur)
        cur = decorateTopLevelDirEmojis(cwd = cwd, entries = cur)
        return cur
    }

    private fun decorateTopLevelDirEmojis(
        cwd: String,
        entries: List<AgentsDirEntry>,
    ): List<AgentsDirEntry> {
        val normalized = cwd.replace('\\', '/').trim().trimEnd('/')
        val mapping: Map<String, String>? =
            when (normalized) {
                ".agents" ->
                    mapOf(
                        "workspace" to "🗂️",
                        "sessions" to "💬",
                        "skills" to "🧩",
                        "nas_smb" to "🛜",
                    )
                ".agents/workspace" ->
                    mapOf(
                        "inbox" to "📥",
                        "musics" to "🎵",
                        "radios" to "📻",
                        "radio_recordings" to "📻",
                        "recordings" to "🎙",
                    )
                RadioRepository.RADIOS_DIR ->
                    mapOf(
                        RadioRepository.FAVORITES_NAME to "⭐",
                    )
                else -> null
            }
        if (mapping == null) return entries

        return entries.map { e ->
            if (e.type != AgentsDirEntryType.Dir) return@map e
            if (!e.iconEmoji.isNullOrBlank()) return@map e
            val emoji = mapping[e.name.trim()] ?: "📁"
            e.copy(iconEmoji = emoji)
        }
    }

    private fun decorateRadioRecordingEntries(
        cwd: String,
        entries: List<AgentsDirEntry>,
    ): List<AgentsDirEntry> {
        val normalized = cwd.replace('\\', '/').trim().trimEnd('/')
        val inWorkspace = normalized == ".agents/workspace"
        val inRadioRoot = normalized == RadioRecordingsPaths.ROOT_DIR
        val micRoot = ".agents/workspace/recordings"
        val inMicRoot = normalized == micRoot
        if (!inWorkspace && !inRadioRoot && !inMicRoot) return entries

        if (inWorkspace) {
            return entries.map { e ->
                if (e.type == AgentsDirEntryType.Dir && e.name == "radio_recordings") {
                    e.copy(
                        displayName = "radio_recordings（录制）",
                        subtitle = "录制会话与 10min 切片产物",
                        iconEmoji = "📻",
                    )
                } else if (e.type == AgentsDirEntryType.Dir && e.name == "recordings") {
                    e.copy(
                        displayName = "recordings（录音）",
                        subtitle = "麦克风录音会话目录",
                        iconEmoji = "🎙",
                    )
                } else {
                    e
                }
            }
        }

        val rootDir = if (inRadioRoot) RadioRecordingsPaths.ROOT_DIR else micRoot

        val filtered =
            entries.filterNot { e ->
                val n = e.name.trim()
                n.startsWith(".") && n.lowercase(Locale.ROOT).endsWith(".json")
            }

        val statusFirst =
            filtered.sortedWith(
                compareBy<AgentsDirEntry> { it.name != "_STATUS.md" }
                    .thenBy { it.type != AgentsDirEntryType.Dir }
                    .thenBy { it.name.lowercase(Locale.ROOT) },
            )

        return statusFirst.map { e ->
            if (e.type != AgentsDirEntryType.Dir) return@map e
            val sid = e.name.trim()
            if (sid.isBlank()) return@map e
            val metaPath = "${rootDir}/$sid/_meta.json"
            val raw =
                try {
                    if (!workspace.exists(metaPath)) return@map e
                    workspace.readTextFile(metaPath, maxBytes = 256 * 1024)
                } catch (_: Throwable) {
                    return@map e
                }
            val meta =
                try {
                    RecordingMetaV1.parse(raw)
                } catch (_: Throwable) {
                    return@map e
                }

            val pipeLabel: String? =
                meta.pipeline?.let { p ->
                    val total = meta.chunks.size.coerceAtLeast(0)
                    val tx =
                        when (p.transcriptState.trim()) {
                            "running" -> "📝转录 ${p.transcribedChunks}/${total}"
                            "completed" -> "📝转录 ✅"
                            "failed" -> "📝转录 ❌"
                            else -> "📝转录 pending"
                        }
                    val tgt = p.targetLanguage?.trim()?.ifBlank { null }
                    val tl =
                        if (tgt == null) {
                            null
                        } else {
                            when (p.translationState.trim()) {
                                "running" -> "🌐翻译 ${p.translatedChunks}/${total} → $tgt"
                                "completed" -> "🌐翻译 ✅ → $tgt"
                                "failed" -> {
                                    val code = p.lastError?.code?.trim()?.ifBlank { null }
                                    if (code != null) "🌐翻译 ❌ ($code)" else "🌐翻译 ❌"
                                }
                                else -> "🌐翻译 pending → $tgt"
                            }
                        }

                    listOfNotNull(tx, tl).joinToString(" · ").trim().ifBlank { null }
                }

            val startLabel = formatRecordingStartLabel(meta.createdAt)
            val baseTitle =
                meta.title?.trim()?.ifBlank { null }
                    ?: meta.station?.name?.trim()?.ifBlank { null }
            val displayName =
                if (inMicRoot) {
                    (baseTitle ?: e.displayName ?: e.name).trim()
                } else {
                    ((baseTitle ?: "") + (startLabel?.let { "  $it" } ?: "")).trim().ifBlank { e.displayName ?: e.name }
                }

            val subtitle =
                if (inMicRoot) {
                    val sessionDir = "${rootDir}/$sid"
                    val bytes =
                        runCatching {
                            workspace.listDir(sessionDir)
                                .filter { it.type == AgentsDirEntryType.File && it.name.lowercase(Locale.ROOT).endsWith(".ogg") }
                                .sumOf { ent -> workspace.toFile("${sessionDir}/${ent.name}").length().coerceAtLeast(0L) }
                        }.getOrNull()
                    val dur = formatDurationHms(meta.durationMs)
                    val size = formatBytes(bytes)
                    val base = listOfNotNull(dur, size, startLabel).joinToString(" · ").trim().ifBlank { null }
                    val pipe = pipeLabel?.trim()?.ifBlank { null }
                    listOfNotNull(base, pipe).joinToString(" · ").trim().ifBlank { null }
                } else {
                    pipeLabel
                        ?: run {
                            val idxPath = "${rootDir}/$sid/transcripts/_tasks.index.json"
                            val idxRaw =
                                try {
                                    if (!workspace.exists(idxPath)) return@run null
                                    workspace.readTextFile(idxPath, maxBytes = 256 * 1024)
                                } catch (_: Throwable) {
                                    return@run null
                                }
                            val idx =
                                try {
                                    TranscriptTasksIndexV1.parse(idxRaw)
                                } catch (_: Throwable) {
                                    return@run null
                                }
                            val running = idx.tasks.firstOrNull { it.state == "pending" || it.state == "running" }
                            when {
                                running != null -> "📝转录 ${running.transcribedChunks}/${running.totalChunks}"
                                idx.tasks.any { it.state == "completed" } -> "📝转录 ✅"
                                else -> null
                            }
                        }
                        ?: "🎙️仅录制"
                }

            e.copy(displayName = displayName, subtitle = subtitle)
        }
    }

    private fun decorateMusicEntries(
        cwd: String,
        entries: List<AgentsDirEntry>,
    ): List<AgentsDirEntry> {
        val normalized = cwd.replace('\\', '/').trim().trimEnd('/')
        val inMusics =
            normalized == ".agents/workspace/musics" || normalized.startsWith(".agents/workspace/musics/")
        val inWorkspace = normalized == ".agents/workspace"
        if (!inMusics && !inWorkspace) return entries

        val reader = Mp3MetadataReader()

        fun formatDuration(ms: Long?): String {
            val v = ms?.takeIf { it > 0L } ?: return ""
            val totalSec = (v / 1000L).toInt().coerceAtLeast(0)
            val m = totalSec / 60
            val s = totalSec % 60
            return "%d:%02d".format(m, s)
        }

        return entries.map { e ->
            if (inWorkspace && e.type == AgentsDirEntryType.Dir && e.name == "musics") {
                return@map e.copy(
                    displayName = "musics（音乐库）",
                    subtitle = "仅该目录启用 mp3 播放与 metadata",
                )
            }

            if (!inMusics) return@map e
            if (e.type != AgentsDirEntryType.File) return@map e
            if (!e.name.lowercase(Locale.ROOT).endsWith(".mp3")) return@map e

            val path = workspace.joinPath(cwd, e.name)
            val file = workspace.toFile(path)
            val md =
                try {
                    reader.readBestEffort(file)
                } catch (_: Throwable) {
                    null
                }
            if (md == null) return@map e

            val dur = formatDuration(md.durationMs)
            val subtitle =
                listOfNotNull(md.artist?.trim()?.ifBlank { null }, dur.ifBlank { null })
                    .joinToString(" · ")
                    .trim()
                    .ifBlank { null }

            e.copy(
                displayName = md.title,
                subtitle = subtitle,
            )
        }
    }

    private fun decorateRadioEntries(
        cwd: String,
        entries: List<AgentsDirEntry>,
    ): List<AgentsDirEntry> {
        val normalized = cwd.replace('\\', '/').trim().trimEnd('/')
        val inWorkspace = normalized == ".agents/workspace"
        val inRadios = normalized == RadioRepository.RADIOS_DIR || normalized.startsWith(RadioRepository.RADIOS_DIR + "/")
        if (!inWorkspace && !inRadios) return entries

        fun iso3166ToFlagEmoji(code: String?): String? {
            val cc = code?.trim()?.uppercase(Locale.ROOT)?.ifBlank { null } ?: return null
            if (cc.length != 2) return null
            val a = cc[0]
            val b = cc[1]
            if (a !in 'A'..'Z' || b !in 'A'..'Z') return null
            val base = 0x1F1E6
            return buildString(4) {
                appendCodePoint(base + (a.code - 'A'.code))
                appendCodePoint(base + (b.code - 'A'.code))
            }
        }

        if (inWorkspace) {
            return entries.map { e ->
                if (e.type == AgentsDirEntryType.Dir && e.name == "radios") {
                    e.copy(
                        displayName = "radios（电台）",
                        subtitle = "懒加载国家/地区目录与 .radio 直播流",
                    )
                } else {
                    e
                }
            }
        }

        val filtered =
            entries.filterNot { e ->
                val n = e.name.trim()
                n.startsWith(".") && n.lowercase(Locale.ROOT).endsWith(".json")
            }

        val segs = normalized.split('/').filter { it.isNotBlank() }
        val isRadiosRoot = normalized == RadioRepository.RADIOS_DIR

        if (isRadiosRoot) {
            val idx = radioRepo.readCountriesIndexOrNull()
            val mapByDir = idx?.countries?.associateBy { it.dir }.orEmpty()
            return filtered.map { e ->
                if (e.type != AgentsDirEntryType.Dir) return@map e
                if (e.name == RadioRepository.FAVORITES_NAME) {
                    return@map e.copy(displayName = "favorites（收藏）", subtitle = "纯文件系统收藏入口")
                }
                val c = mapByDir[e.name]
                val subtitle =
                    c?.stationCount?.let { "stationCount=$it" }
                        ?: c?.code?.let { "code=$it" }
                e.copy(
                    displayName = c?.name ?: e.name,
                    subtitle = subtitle,
                    iconEmoji = iso3166ToFlagEmoji(c?.code),
                )
            }
        }

        val isDirectChild = segs.size == 4 && normalized.startsWith(RadioRepository.RADIOS_DIR + "/")
        if (!isDirectChild) return filtered

        val isFavorites = normalized == RadioRepository.FAVORITES_DIR
        val statusFirst =
            filtered.sortedWith(compareBy<AgentsDirEntry> { it.name != "_STATUS.md" })

        val radios = mutableListOf<AgentsDirEntry>()
        val others = mutableListOf<AgentsDirEntry>()

        for (e in statusFirst) {
            if (e.type == AgentsDirEntryType.File && e.name.lowercase(Locale.ROOT).endsWith(".radio")) {
                val path = workspace.joinPath(cwd, e.name)
                val raw =
                    try {
                        workspace.readTextFile(path, maxBytes = 256 * 1024)
                    } catch (_: Throwable) {
                        null
                    }
                val st =
                    try {
                        raw?.let { RadioStationFileV1.parse(it) }
                    } catch (_: Throwable) {
                        null
                    }
                if (st == null) {
                    radios.add(e)
                    continue
                }
                val subtitleParts = mutableListOf<String>()
                st.votes?.let { subtitleParts.add("votes=$it") }
                st.codec?.trim()?.ifBlank { null }?.let { subtitleParts.add(it) }
                st.bitrateKbps?.let { subtitleParts.add("${it}kbps") }
                val subtitle = subtitleParts.joinToString(" · ").ifBlank { null }
                val sortKey = (st.votes ?: 0).toLong()
                radios.add(e.copy(displayName = st.name, subtitle = subtitle, sortKey = sortKey))
            } else {
                others.add(e)
            }
        }

        val sortedRadios =
            radios.sortedWith(
                compareByDescending<AgentsDirEntry> { it.sortKey ?: 0L }
                    .thenBy { (it.displayName ?: it.name).lowercase(Locale.ROOT) }
            )

        return if (isFavorites) {
            (others.filter { it.type == AgentsDirEntryType.Dir } +
                others.filter { it.type != AgentsDirEntryType.Dir && !it.name.lowercase(Locale.ROOT).endsWith(".radio") } +
                sortedRadios)
        } else {
            (others.filter { it.type == AgentsDirEntryType.Dir } +
                others.filter { it.type != AgentsDirEntryType.Dir && !it.name.lowercase(Locale.ROOT).endsWith(".radio") } +
                sortedRadios)
        }
    }

    private suspend fun maybeSyncRadios(
        cwd: String,
        force: Boolean,
    ): String? {
        val normalized = cwd.replace('\\', '/').trim().trimEnd('/')
        if (normalized == RadioRepository.RADIOS_DIR) {
            val r = radioRepo.syncCountries(force = force)
            return r.message
        }
        if (normalized == RadioRepository.FAVORITES_DIR) return null
        if (normalized.startsWith(RadioRepository.RADIOS_DIR + "/")) {
            val rel = normalized.removePrefix(RadioRepository.RADIOS_DIR + "/")
            val dir = rel.substringBefore('/')
            if (dir.isBlank() || dir == RadioRepository.FAVORITES_NAME) return null
            val countries = radioRepo.syncCountries(force = false)
            if (!countries.ok && !countries.message.isNullOrBlank()) return countries.message
            val r = radioRepo.syncStationsForCountryDir(countryDirName = dir, force = force)
            return r.message
        }
        return null
    }


    private data class SessionIdentity(
        val kind: String?,
        val agent: String?,
        val parentSessionId: String?,
    )

    private fun readSessionIdentity(sessionId: String): SessionIdentity? {
        val metaPath = ".agents/sessions/$sessionId/meta.json"
        val raw =
            try {
                if (!workspace.exists(metaPath)) return null
                workspace.readTextFile(metaPath, maxBytes = 64 * 1024)
            } catch (_: Throwable) {
                return null
            }
        val metaObj =
            try {
                json.parseToJsonElement(raw).jsonObject
            } catch (_: Throwable) {
                return null
            }
        val md = metaObj["metadata"] as? JsonObject ?: return null
        fun str(key: String): String? = md[key]?.jsonPrimitive?.content?.trim()?.ifBlank { null }
        return SessionIdentity(
            kind = str("kind"),
            agent = str("agent"),
            parentSessionId = str("parent_session_id"),
        )
    }

    private fun sessionLastActivityMs(sessionId: String): Long? {
        val eventsPath = ".agents/sessions/$sessionId/events.jsonl"
        return workspace.lastModified(eventsPath)
    }

    private fun sessionCreatedAtMs(sessionId: String): Long? {
        val metaPath = ".agents/sessions/$sessionId/meta.json"
        val raw =
            try {
                if (!workspace.exists(metaPath)) return null
                workspace.readTextFile(metaPath, maxBytes = 64 * 1024)
            } catch (_: Throwable) {
                return null
            }
        val createdAtSec =
            try {
                val obj = json.parseToJsonElement(raw).jsonObject
                obj["created_at"]?.jsonPrimitive?.content?.toDoubleOrNull()
            } catch (_: Throwable) {
                null
            }
        val ms =
            if (createdAtSec != null) (createdAtSec * 1000.0).toLong() else null
        return ms?.takeIf { it > 0L } ?: workspace.lastModified(metaPath)
    }

    private fun formatTimestamp(ms: Long): String {
        if (ms <= 0L) return ""
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return fmt.format(Date(ms))
    }

    private fun formatRecordingStartLabel(iso: String): String? {
        val raw = iso.trim().ifBlank { return null }
        return try {
            val odt = OffsetDateTime.parse(raw)
            val local = odt.atZoneSameInstant(ZoneId.systemDefault())
            val now = ZonedDateTime.now(ZoneId.systemDefault())
            val pattern = if (local.year == now.year) "MM-dd HH:mm" else "yyyy-MM-dd HH:mm"
            local.format(DateTimeFormatter.ofPattern(pattern))
        } catch (_: Throwable) {
            null
        }
    }

    private fun formatDurationHms(durationMs: Long?): String? {
        val ms = durationMs?.takeIf { it > 0L } ?: return null
        val total = (ms / 1000L).toInt().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    private fun formatBytes(bytes: Long?): String? {
        val b = bytes?.takeIf { it >= 0L } ?: return null
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            b >= gb -> String.format(Locale.US, "%.1fGB", b / gb)
            b >= mb -> String.format(Locale.US, "%.0fMB", b / mb)
            b >= kb -> String.format(Locale.US, "%.0fKB", b / kb)
            else -> "${b}B"
        }
    }

    private fun readSessionTitle(sessionId: String): String? {
        val path = ".agents/sessions/$sessionId/title.json"
        val raw =
            try {
                if (!workspace.exists(path)) return null
                workspace.readTextFile(path, maxBytes = 32 * 1024)
            } catch (_: Throwable) {
                return null
            }
        return try {
            val obj = json.parseToJsonElement(raw).jsonObject
            obj["title"]?.jsonPrimitive?.content?.trim()?.ifBlank { null }
        } catch (_: Throwable) {
            null
        }
    }

    private fun maybeStartSessionTitleJob(
        cwd: String,
        sessionIds: List<String>,
    ) {
        if (cwd != ".agents/sessions") return
        if (sessionIds.isEmpty()) return
        if (sessionTitleJob?.isActive == true) return

        sessionTitleJob =
            viewModelScope.launch {
                // 慢慢悠悠：一次只处理少量，避免刷爆网络/额度。
                val current = _state.value
                val uiOrder =
                    current?.entries
                        ?.asSequence()
                        ?.filter { it.type == AgentsDirEntryType.Dir && sessionIds.contains(it.name) }
                        ?.map { it.name }
                        ?.toList()
                        .orEmpty()
                val queue = (uiOrder + sessionIds).distinct().take(8)
                for (sid in queue) {
                    val currentCwd = _state.value?.cwd ?: ".agents"
                    if (currentCwd != ".agents/sessions") break
                    try {
                        val title = withContext(Dispatchers.IO) { generateAndPersistSessionTitle(sid) }
                        if (!title.isNullOrBlank()) {
                            val now = _state.value ?: FilesUiState()
                            val updated =
                                now.entries.map { e ->
                                    if (e.type == AgentsDirEntryType.Dir && e.name == sid) e.copy(displayName = title.trim()) else e
                                }
                            _state.postValue(now.copy(entries = updated))
                        }
                    } catch (_: Throwable) {
                        // ignore per-session failures
                    }
                    delay(900)
                }
            }
    }

    private suspend fun generateAndPersistSessionTitle(sessionId: String): String? {
        val existing = readSessionTitle(sessionId)
        if (!existing.isNullOrBlank()) return existing

        val eventsPath = ".agents/sessions/$sessionId/events.jsonl"
        val events =
            try {
                if (!workspace.exists(eventsPath)) ""
                else workspace.readTextFileHead(eventsPath, maxBytes = 16 * 1024)
            } catch (_: Throwable) {
                ""
            }
        val firstUser = extractFirstUserMessage(events, maxLines = 10)
        val heuristic = firstUser?.trim()?.ifBlank { null }?.let { shrinkForTitle(it) }

        val cfg =
            try {
                configRepository.get()
            } catch (_: Throwable) {
                null
            }
        val baseUrl = cfg?.baseUrl?.trim().orEmpty()
        val apiKey = cfg?.apiKey?.trim().orEmpty()
        val model = cfg?.model?.trim().orEmpty()

        val title =
            if (baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()) {
                val provider = OpenAIResponsesHttpProvider(baseUrl = baseUrl)
                val sys =
                    """
                    你是会话标题生成器。根据给定的“首条用户消息”，生成一个中文标题。
                    约束：
                    - 只输出标题本身（不要解释）
                    - 1 行、不要换行、不要加引号
                    - 必须极短：最多 9 个汉字
                    - 不要包含空格、标点、英文、数字
                    """.trimIndent()
                val user =
                    buildString {
                        append("首条用户消息：\n")
                        append(firstUser?.trim().orEmpty().ifBlank { "（缺失）" })
                    }

                val input =
                    listOf(
                        buildJsonObject {
                            put("role", JsonPrimitive("system"))
                            put("content", JsonPrimitive(sys))
                        },
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonPrimitive(user))
                        },
                    )

                val modelTitle =
                    try {
                        var completedText: String? = null
                        val req =
                            ResponsesRequest(
                                model = model,
                                input = input,
                                tools = emptyList(),
                                apiKey = apiKey,
                                previousResponseId = null,
                                store = false,
                            )
                        provider.stream(req).collect { sev ->
                            when (sev) {
                                is ProviderStreamEvent.TextDelta -> Unit
                                is ProviderStreamEvent.Completed -> completedText = sev.output.assistantText
                                is ProviderStreamEvent.Failed -> error("session title stream failed: ${sev.message}")
                            }
                        }
                        completedText
                            ?.trim()
                            ?.lineSequence()
                            ?.firstOrNull()
                            ?.trim()
                            ?.let { stripSurroundingQuotes(it) }
                            ?.let { shrinkForTitle(it) }
                            ?.ifBlank { null }
                    } catch (_: Throwable) {
                        null
                    }
                modelTitle ?: heuristic
            } else {
                heuristic
            }

        val finalTitle = title?.trim()?.ifBlank { null } ?: sessionId.take(8)
        persistSessionTitle(sessionId, finalTitle, model = model.ifBlank { null })
        return finalTitle
    }

    private fun extractFirstUserMessage(
        eventsJsonl: String,
        maxLines: Int,
    ): String? {
        if (eventsJsonl.isBlank()) return null
        var firstUser: String? = null
        val limit = maxLines.coerceAtLeast(0)
        val lines = eventsJsonl.lineSequence().filter { it.isNotBlank() }.let { seq -> if (limit > 0) seq.take(limit) else seq }
        for (line in lines) {
            val obj: JsonObject =
                try {
                    json.decodeFromString(JsonObject.serializer(), line)
                } catch (_: Throwable) {
                    continue
                }
            val type = obj["type"]?.jsonPrimitive?.content?.trim().orEmpty()
            when (type) {
                "user.message" -> {
                    if (firstUser == null) {
                        firstUser = obj["text"]?.jsonPrimitive?.content
                    }
                }
                "user.question" -> {
                    if (firstUser == null) {
                        firstUser = obj["prompt"]?.jsonPrimitive?.content
                    }
                }
                else -> Unit
            }
            if (firstUser != null) break
        }
        return firstUser
    }

    private fun stripSurroundingQuotes(text: String): String {
        val s = text.trim()
        if (s.length < 2) return s
        val pairs =
            listOf(
                '\"' to '\"',
                '“' to '”',
                '‘' to '’',
                '\'' to '\'',
            )
        for ((l, r) in pairs) {
            if (s.first() == l && s.last() == r) return s.substring(1, s.length - 1).trim()
        }
        return s
    }

    private fun shrinkForTitle(text: String): String {
        val oneLine = text.replace("\r\n", "\n").lineSequence().firstOrNull().orEmpty().trim()
        val noSpace = oneLine.replace(Regex("\\s+"), "").trim()
        val hanOnly = noSpace.replace(Regex("[^\\p{IsHan}]"), "")
        val base =
            if (hanOnly.isNotBlank()) {
                hanOnly
            } else {
                // Fallback for non-Chinese inputs: keep a compact alnum slug.
                noSpace.replace(Regex("[^\\p{Alnum}]"), "")
            }
        val limited = base.take(9)
        return limited.trim().ifBlank { "" }
    }

    private fun persistSessionTitle(
        sessionId: String,
        title: String,
        model: String?,
    ) {
        val path = ".agents/sessions/$sessionId/title.json"
        val obj =
            buildJsonObject {
                put("title", JsonPrimitive(title))
                put("generated_at", JsonPrimitive((System.currentTimeMillis() / 1000.0)))
                if (!model.isNullOrBlank()) put("model", JsonPrimitive(model))
            }
        val raw = json.encodeToString(JsonObject.serializer(), obj) + "\n"
        try {
            workspace.writeTextFile(path, raw)
        } catch (_: Throwable) {
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(FilesViewModel::class.java)) {
                val prefs: SharedPreferences =
                    context.getSharedPreferences("kotlin-agent-app", Context.MODE_PRIVATE)
                val repo = SharedPreferencesLlmConfigRepository(prefs)
                return FilesViewModel(AgentsWorkspace(context), repo) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
