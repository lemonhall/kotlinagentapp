package com.lsl.kotlin_agent_app.radio_transcript

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.agent.tools.mail.DotEnv
import com.lsl.kotlin_agent_app.asr.AliyunQwenAsrClient
import com.lsl.kotlin_agent_app.asr.AsrException
import com.lsl.kotlin_agent_app.asr.AsrNetworkError
import com.lsl.kotlin_agent_app.asr.AsrParseError
import com.lsl.kotlin_agent_app.asr.AsrResult
import com.lsl.kotlin_agent_app.asr.AsrRemoteError
import com.lsl.kotlin_agent_app.asr.AsrTaskTimeout
import com.lsl.kotlin_agent_app.asr.AsrUploadError
import com.lsl.kotlin_agent_app.asr.CloudAsrClient
import com.lsl.kotlin_agent_app.asr.DashScopeFileUploader
import com.lsl.kotlin_agent_app.config.EnvConfig
import com.lsl.kotlin_agent_app.radio_recordings.RadioRecordingsPaths
import com.lsl.kotlin_agent_app.radio_recordings.RecordingMetaV1
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient

internal class TranscriptCliException(
    val errorCode: String,
    message: String,
) : IllegalArgumentException(message)

internal class TranscriptTaskManager(
    appContext: Context,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val workManager: WorkManager? = runCatching { WorkManager.getInstance(appContext.applicationContext) }.getOrNull(),
    private val ws: AgentsWorkspace = AgentsWorkspace(appContext.applicationContext),
    private val store: TranscriptTaskStore = TranscriptTaskStore(ws),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val ctx = appContext.applicationContext
    private val prettyJson = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true }

    private data class AsrRuntimeConfig(
        val apiKey: String,
        val baseUrl: String,
        val model: String,
    )

    suspend fun start(
        sessionId: String,
        sourceLanguage: String?,
        force: Boolean,
    ): TranscriptTaskV1 {
        val sid = sessionId.trim()
        val rawLang = sourceLanguage?.trim()?.ifBlank { null }
        if (sid.isBlank()) throw TranscriptCliException("InvalidArgs", "missing --session")
        if (rawLang.isNullOrBlank()) throw TranscriptCliException("InvalidArgs", "missing --source_lang")
        val lang = normalizeSourceLanguage(rawLang)

        val chunks = validateSessionAndListChunks(sessionId = sid)

        // UX: fail fast before creating any task files / WorkManager jobs (but after session validation).
        requireAsrConfigured()

        store.ensureSessionRoot(sid)

        val prevIndex = store.readTasksIndexOrNull(sid) ?: TranscriptTasksIndexV1(generatedAtSec = store.nowSec(), tasks = emptyList())
        val activeSameLang =
            prevIndex.tasks.firstOrNull { normalizeSourceLanguage(it.sourceLanguage) == lang && (it.state == "pending" || it.state == "running") }
        val anySameLang =
            prevIndex.tasks
                .filter { normalizeSourceLanguage(it.sourceLanguage) == lang }
                .maxByOrNull { it.updatedAt }
        if (activeSameLang != null && !force) {
            throw TranscriptCliException("TaskAlreadyExists", "task already exists for session=$sid source_lang=${lang ?: "auto"}: ${activeSameLang.taskId}")
        }

        val reuse = if (force) (activeSameLang ?: anySameLang) else null
        val taskId = reuse?.taskId ?: store.allocateTaskId(prefix = "tx")

        val task =
            if (force && reuse != null) {
                resetTaskOutputs(sessionId = sid, taskId = reuse.taskId)
            } else {
                store.createTask(sessionId = sid, taskId = taskId, sourceLanguage = lang, totalChunks = chunks.size)
            }

        enqueueWork(sessionId = sid, taskId = task.taskId, replace = force)
        return task
    }

    fun listSessionTasks(sessionId: String): TranscriptTasksIndexV1 {
        val sid = sessionId.trim()
        if (sid.isBlank()) throw TranscriptCliException("InvalidArgs", "missing --session")
        store.ensureSessionRoot(sid)
        return store.readTasksIndexOrNull(sid) ?: TranscriptTasksIndexV1(generatedAtSec = store.nowSec(), tasks = emptyList())
    }

    fun findTaskById(taskId: String): TranscriptTaskV1 {
        val tid = taskId.trim()
        if (tid.isBlank()) throw TranscriptCliException("InvalidArgs", "missing --task")

        val root = ".agents/workspace/radio_recordings"
        val sessions = ws.listDir(root).filter { it.type.name == "Dir" }.map { it.name }
        for (sid in sessions) {
            val t = store.readTaskOrNull(sid, tid)
            if (t != null) return t
        }
        throw TranscriptCliException("TaskNotFound", "task not found: $tid")
    }

    fun cancel(taskId: String): TranscriptTaskV1 {
        val tid = taskId.trim()
        if (tid.isBlank()) throw TranscriptCliException("InvalidArgs", "missing --task")

        val root = ".agents/workspace/radio_recordings"
        val sessions = ws.listDir(root).filter { it.type.name == "Dir" }.map { it.name }
        for (sid in sessions) {
            val t = store.readTaskOrNull(sid, tid)
            if (t != null) {
                workManager?.cancelUniqueWork(uniqueWorkName(taskId = tid))
                return store.cancelTask(sessionId = sid, taskId = tid)
            }
        }
        throw TranscriptCliException("TaskNotFound", "task not found: $tid")
    }

    fun retry(
        sessionId: String,
        taskId: String,
        replace: Boolean = true,
    ): TranscriptTaskV1 {
        val sid = sessionId.trim()
        val tid = taskId.trim()
        if (sid.isBlank()) throw TranscriptCliException("InvalidArgs", "missing sessionId")
        if (tid.isBlank()) throw TranscriptCliException("InvalidArgs", "missing taskId")

        requireAsrConfigured()

        val prev = store.readTaskOrNull(sid, tid) ?: throw TranscriptCliException("TaskNotFound", "task not found: $tid")
        val now = TranscriptTaskV1.nowIso()
        val next =
            prev.copy(
                state = "pending",
                updatedAt = now,
                lastError = null,
            )
        store.writeTask(next)
        store.writeTaskStatus(sid, tid, ok = true, note = "pending (retry)")
        enqueueWork(sessionId = sid, taskId = tid, replace = replace)
        return next
    }

    suspend fun runTask(
        sessionId: String,
        taskId: String,
        cloudAsrClient: CloudAsrClient,
        shouldStop: () -> Boolean = { false },
    ) {
        val sid = sessionId.trim()
        val tid = taskId.trim()
        val chunks = validateSessionAndListChunks(sessionId = sid)
        val prev = store.readTaskOrNull(sid, tid) ?: throw TranscriptCliException("TaskNotFound", "task not found: $tid")
        val normalizedLang = normalizeSourceLanguage(prev.sourceLanguage)

        // Recompute progress from filesystem.
        val doneCount =
            chunks.count { c ->
                ws.exists(RadioTranscriptPaths.chunkTranscriptJson(sid, tid, c.index))
            }
        val baseTask =
            prev.copy(
                state = "running",
                sourceLanguage = normalizedLang,
                totalChunks = chunks.size,
                transcribedChunks = doneCount.coerceIn(0, chunks.size),
                updatedAt = TranscriptTaskV1.nowIso(),
            )
        store.writeTask(baseTask)
        store.writeTaskStatus(sid, tid, ok = true, note = "running ${baseTask.transcribedChunks}/${baseTask.totalChunks}")

        var current = baseTask
        for (c in chunks.sortedBy { it.index }) {
            if (shouldStop()) return
            val outPath = RadioTranscriptPaths.chunkTranscriptJson(sid, tid, c.index)
            if (ws.exists(outPath)) continue

            val audioPath = "${RadioRecordingsPaths.sessionDir(sid)}/${c.file}"
            val audioFile = File(ctx.filesDir, audioPath)
            if (!audioFile.exists() || !audioFile.isFile) {
                current =
                    current.copy(
                        state = "failed",
                        failedChunks = current.failedChunks + 1,
                        updatedAt = TranscriptTaskV1.nowIso(),
                        lastError = TranscriptTaskV1.ErrorInfo(code = "SessionNoChunks", message = "missing chunk file: ${c.file}"),
                    )
                store.writeTask(current)
                store.writeTaskStatus(sid, tid, ok = false, note = "failed: missing chunk")
                return
            }

            val result: AsrResult =
                try {
                    cloudAsrClient.transcribe(audioFile = audioFile, mimeType = "audio/ogg", language = normalizeSourceLanguage(current.sourceLanguage))
                } catch (t: AsrException) {
                    current =
                        current.copy(
                            state = "failed",
                            failedChunks = current.failedChunks + 1,
                            updatedAt = TranscriptTaskV1.nowIso(),
                            lastError =
                                TranscriptTaskV1.ErrorInfo(
                                    code = mapAsrErrorCode(t),
                                    message = mapAsrErrorMessage(t),
                                ),
                        )
                    store.writeTask(current)
                    store.writeTaskStatus(sid, tid, ok = false, note = "failed: ${current.lastError?.code ?: "AsrError"}")
                    return
                }

            // 1) write transcript file (atomic)
            val chunkRaw = renderChunkTranscriptJson(task = current, chunkIndex = c.index, asr = result)
            ws.writeTextFileAtomic(outPath, chunkRaw)

            // 2) then update task progress
            current =
                current.copy(
                    transcribedChunks = (current.transcribedChunks + 1).coerceAtMost(current.totalChunks),
                    updatedAt = TranscriptTaskV1.nowIso(),
                )
            store.writeTask(current)
            store.writeTaskStatus(sid, tid, ok = true, note = "running ${current.transcribedChunks}/${current.totalChunks}")
        }

        current = current.copy(state = "completed", updatedAt = TranscriptTaskV1.nowIso())
        store.writeTask(current)
        store.writeTaskStatus(sid, tid, ok = true, note = "completed ${current.transcribedChunks}/${current.totalChunks}")
    }

    fun buildDefaultAsrClient(): CloudAsrClient {
        return buildDefaultAsrClient(debugDumpDir = null)
    }

    fun buildDefaultAsrClient(debugDumpDir: File?): CloudAsrClient {
        val cfg = readAsrConfigOrThrow()
        val apiKey = cfg.apiKey
        val baseUrl = cfg.baseUrl.trimEnd('/')
        val model = cfg.model
        val uploader = DashScopeFileUploader(baseUrl = baseUrl, apiKey = apiKey, httpClient = httpClient)
        return AliyunQwenAsrClient(
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelName = model,
            uploader = uploader,
            debugDumpDir = debugDumpDir,
            httpClient = httpClient,
        )
    }

    private fun enqueueWork(
        sessionId: String,
        taskId: String,
        replace: Boolean,
    ) {
        val wm = workManager ?: return
        try {
            val req =
                OneTimeWorkRequestBuilder<TranscriptWorker>()
                    .setInputData(
                        workDataOf(
                            TranscriptWorker.KEY_SESSION_ID to sessionId,
                            TranscriptWorker.KEY_TASK_ID to taskId,
                        ),
                    )
                    .build()
            wm.enqueueUniqueWork(
                uniqueWorkName(taskId),
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                req,
            )
        } catch (_: Throwable) {
            // Best-effort: unit tests under Robolectric may not initialize WorkManager.
        }
    }

    private fun uniqueWorkName(taskId: String): String = "radio_transcript_${taskId.trim()}"

    private suspend fun validateSessionAndListChunks(sessionId: String): List<RecordingMetaV1.Chunk> {
        val sid = sessionId.trim()
        if (sid.isBlank()) throw TranscriptCliException("InvalidArgs", "missing --session")

        val metaPath = RadioRecordingsPaths.sessionMetaJson(sid)
        if (!ws.exists(metaPath)) throw TranscriptCliException("SessionNotFound", "session not found: $sid")

        val raw =
            withContext(Dispatchers.IO) {
                ws.readTextFile(metaPath, maxBytes = 2L * 1024L * 1024L)
            }
        val meta =
            try {
                RecordingMetaV1.parse(raw)
            } catch (t: Throwable) {
                throw TranscriptCliException("SessionNotFound", "invalid session meta: $sid")
            }

        val st = meta.state.trim().lowercase()
        if (st == "recording" || st == "pending") {
            throw TranscriptCliException("SessionStillRecording", "session $sid is still recording. Stop recording first.")
        }

        val chunks = meta.chunks.filter { it.file.trim().endsWith(".ogg", ignoreCase = true) }
        if (chunks.isEmpty()) throw TranscriptCliException("SessionNoChunks", "session $sid has no chunks")

        return chunks
    }

    private fun resetTaskOutputs(
        sessionId: String,
        taskId: String,
    ): TranscriptTaskV1 {
        val sid = sessionId.trim()
        val tid = taskId.trim()
        val prev = store.readTaskOrNull(sid, tid) ?: throw TranscriptCliException("TaskNotFound", "task not found: $tid")

        val taskDir = RadioTranscriptPaths.taskDir(sid, tid)
        val entries = ws.listDir(taskDir)
        for (e in entries) {
            if (e.type.name != "File") continue
            val name = e.name.trim()
            if (name.matches(Regex("^chunk_\\d{3}\\.transcript\\.json$"))) {
                val p = ws.joinPath(taskDir, name)
                ws.deletePath(p, recursive = false)
            }
        }

        val now = TranscriptTaskV1.nowIso()
        val next =
            prev.copy(
                state = "pending",
                transcribedChunks = 0,
                failedChunks = 0,
                updatedAt = now,
                lastError = null,
            )
        store.writeTask(next)
        store.writeTaskStatus(sid, tid, ok = true, note = "pending (forced)")
        return next
    }

    private fun renderChunkTranscriptJson(
        task: TranscriptTaskV1,
        chunkIndex: Int,
        asr: AsrResult,
    ): String {
        val obj: JsonObject =
            buildJsonObject {
                put("schema", JsonPrimitive("kotlin-agent-app/radio-transcript-chunk@v1"))
                put("taskId", JsonPrimitive(task.taskId))
                put("sessionId", JsonPrimitive(task.sessionId))
                put("chunkIndex", JsonPrimitive(chunkIndex))
                asr.detectedLanguage?.let { put("detectedLanguage", JsonPrimitive(it)) }
                put(
                    "segments",
                    buildJsonArray {
                        for (s in asr.segments) {
                            add(
                                buildJsonObject {
                                    put("id", JsonPrimitive(s.id))
                                    put("startMs", JsonPrimitive(s.startMs))
                                    put("endMs", JsonPrimitive(s.endMs))
                                    put("text", JsonPrimitive(s.text))
                                    s.emotion?.let { put("emotion", JsonPrimitive(it)) }
                                },
                            )
                        }
                    },
                )
            }
        return prettyJson.encodeToString(JsonObject.serializer(), obj) + "\n"
    }

    private fun mapAsrErrorCode(t: AsrException): String {
        return when (t) {
            is AsrNetworkError -> "AsrNetworkError"
            is AsrUploadError -> "AsrUploadError"
            is AsrParseError -> "AsrParseError"
            is AsrTaskTimeout -> "AsrTaskTimeout"
            is AsrRemoteError -> "AsrRemoteError"
            else -> "AsrRemoteError"
        }
    }

    private fun mapAsrErrorMessage(t: AsrException): String? {
        return when (t) {
            is AsrRemoteError -> "remote_code=${t.code}: ${t.message ?: ""}".trim().ifBlank { null }
            else -> t.message?.trim()?.ifBlank { null }
        }
    }

    private fun normalizeSourceLanguage(raw: String?): String? {
        val v = raw?.trim()?.ifBlank { null } ?: return null
        if (v.equals("auto", ignoreCase = true)) return null
        if (v.equals("null", ignoreCase = true)) return null
        return v
    }

    private fun requireAsrConfigured() {
        readAsrConfigOrThrow()
    }

    private fun readAsrConfigOrThrow(): AsrRuntimeConfig {
        ws.ensureInitialized()
        val envFile = ws.toFile(".agents/workspace/radio_recordings/.env")
        val env = DotEnv.load(envFile)

        val apiKey = env["DASHSCOPE_API_KEY"].orEmpty().trim().ifBlank { EnvConfig.dashScopeApiKey.trim() }
        if (apiKey.isBlank()) {
            throw TranscriptCliException(
                "InvalidArgs",
                "missing DASHSCOPE_API_KEY (edit workspace/radio_recordings/.env)",
            )
        }

        val baseUrl = env["DASHSCOPE_BASE_URL"].orEmpty().trim().ifBlank { EnvConfig.dashScopeBaseUrl.trim() }
        val model = env["ASR_MODEL"].orEmpty().trim().ifBlank { EnvConfig.asrModel.trim() }
        return AsrRuntimeConfig(apiKey = apiKey, baseUrl = baseUrl, model = model)
    }
}
