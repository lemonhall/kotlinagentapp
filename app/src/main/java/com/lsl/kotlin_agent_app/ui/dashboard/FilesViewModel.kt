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

    fun refresh() {
        val prev = _state.value ?: FilesUiState()
        _state.value = prev.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    workspace.ensureInitialized()
                }
                val cwd = (_state.value ?: prev).cwd
                val rawEntries = withContext(Dispatchers.IO) { workspace.listDir(cwd) }
                val (entries, missingSessionIds) =
                    withContext(Dispatchers.IO) {
                        decorateEntries(cwd = cwd, entries = rawEntries)
                    }
                _state.value = (_state.value ?: prev).copy(entries = entries, isLoading = false, errorMessage = null)

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
                refresh()
            } catch (t: Throwable) {
                val now = _state.value ?: prev
                _state.value = now.copy(isLoading = false, errorMessage = t.message ?: "Delete failed")
            }
        }
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
        if (cwd != ".agents/sessions") return entries to emptyList()

        val sessions = mutableListOf<AgentsDirEntry>()
        val others = mutableListOf<AgentsDirEntry>()
        val missingTitles = mutableListOf<String>()

        val sidRx = Regex("^[a-f0-9]{32}$", RegexOption.IGNORE_CASE)
        for (e in entries) {
            if (e.type == AgentsDirEntryType.Dir && sidRx.matches(e.name)) {
                val sid = e.name
                val title = readSessionTitle(sid)
                val lastMs = sessionLastActivityMs(sid) ?: sessionCreatedAtMs(sid) ?: 0L
                val subtitle = formatTimestamp(lastMs)
                val displayName = title?.ifBlank { null } ?: sid.take(8)
                sessions.add(e.copy(displayName = displayName, subtitle = subtitle, sortKey = lastMs))
                if (title.isNullOrBlank()) missingTitles.add(sid)
            } else {
                others.add(e)
            }
        }

        val sortedSessions =
            sessions.sortedByDescending { it.sortKey ?: 0L }
        val sortedOthers =
            others.sortedWith(compareBy<AgentsDirEntry>({ it.type != AgentsDirEntryType.Dir }, { it.name.lowercase() }))

        return (sortedSessions + sortedOthers) to missingTitles
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
