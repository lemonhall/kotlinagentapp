package com.lsl.kotlin_agent_app.radio_transcript

import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.recordings.RecordingRoots
import com.lsl.kotlin_agent_app.recordings.RecordingSessionRef
import com.lsl.kotlin_agent_app.recordings.RecordingSessionResolver
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class TranscriptTaskStore(
    private val ws: AgentsWorkspace,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val prettyJson: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true },
) {
    private fun sessionRefOrFallback(sessionId: String): RecordingSessionRef {
        val sid = sessionId.trim()
        require(sid.isNotBlank()) { "missing sessionId" }
        return RecordingSessionResolver.resolve(ws, sid) ?: RecordingSessionRef(rootDir = RecordingRoots.RADIO_ROOT_DIR, sessionId = sid)
    }

    fun allocateTaskId(prefix: String = "tx"): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
        val ts = fmt.format(Date(nowMs()))
        val rand = randomToken(6)
        return "${prefix}_${ts}_$rand"
    }

    fun nowSec(): Long = (nowMs() / 1000L).coerceAtLeast(0L)

    fun ensureSessionRoot(sessionId: String) {
        val sid = sessionId.trim()
        if (sid.isBlank()) error("missing sessionId")
        val ref = sessionRefOrFallback(sid)
        ws.mkdir(ref.transcriptsDir)
        val idxPath = ref.transcriptTasksIndexPath
        if (!ws.exists(idxPath)) {
            val idx = TranscriptTasksIndexV1(generatedAtSec = nowSec(), tasks = emptyList())
            ws.writeTextFileAtomic(idxPath, toPrettyRaw(idx.toJsonObject()))
        }
    }

    fun createTask(
        sessionId: String,
        taskId: String,
        sourceLanguage: String?,
        totalChunks: Int,
    ): TranscriptTaskV1 {
        val sid = sessionId.trim()
        val tid = taskId.trim()
        if (sid.isBlank()) error("missing sessionId")
        if (tid.isBlank()) error("missing taskId")
        val ref = sessionRefOrFallback(sid)
        ensureSessionRoot(sid)
        ws.mkdir(ref.transcriptTaskDir(tid))

        val now = TranscriptTaskV1.nowIso()
        val task =
            TranscriptTaskV1(
                schema = TranscriptTaskV1.SCHEMA_V1,
                taskId = tid,
                sessionId = sid,
                state = "pending",
                sourceLanguage =
                    sourceLanguage
                        ?.trim()
                        ?.ifBlank { null }
                        ?.takeUnless { it.equals("auto", ignoreCase = true) || it.equals("null", ignoreCase = true) },
                totalChunks = totalChunks.coerceAtLeast(0),
                transcribedChunks = 0,
                failedChunks = 0,
                createdAt = now,
                updatedAt = now,
                lastError = null,
            )
        writeTask(task)
        writeTaskStatus(sid, tid, ok = true, note = "pending")

        val prev = readTasksIndexOrNull(sid) ?: TranscriptTasksIndexV1(generatedAtSec = nowSec(), tasks = emptyList())
        val entry =
            TranscriptTasksIndexV1.TaskEntry(
                taskId = task.taskId,
                sessionId = task.sessionId,
                state = task.state,
                sourceLanguage = task.sourceLanguage,
                totalChunks = task.totalChunks,
                transcribedChunks = task.transcribedChunks,
                failedChunks = task.failedChunks,
                createdAt = task.createdAt,
                updatedAt = task.updatedAt,
            )
        val nextTasks = prev.tasks.filterNot { it.taskId == tid } + entry
        writeTasksIndex(sid, prev.copy(generatedAtSec = nowSec(), tasks = nextTasks))
        return task
    }

    fun readTaskOrNull(
        sessionId: String,
        taskId: String,
    ): TranscriptTaskV1? {
        val sid = sessionId.trim()
        val tid = taskId.trim()
        if (sid.isBlank() || tid.isBlank()) return null
        val ref = sessionRefOrFallback(sid)
        val path = ref.transcriptTaskJson(tid)
        val raw =
            try {
                if (!ws.exists(path)) return null
                ws.readTextFile(path, maxBytes = 2L * 1024L * 1024L)
            } catch (_: Throwable) {
                return null
            }
        return runCatching { TranscriptTaskV1.parse(raw) }.getOrNull()
    }

    fun writeTask(task: TranscriptTaskV1) {
        val sid = task.sessionId.trim()
        val tid = task.taskId.trim()
        if (sid.isBlank() || tid.isBlank()) error("invalid task ids")
        val ref = sessionRefOrFallback(sid)
        val raw = toPrettyRaw(task.toJsonObject())
        ws.writeTextFileAtomic(ref.transcriptTaskJson(tid), raw)

        val prev = readTasksIndexOrNull(sid) ?: return
        val nextTasks =
            prev.tasks.map { e ->
                if (e.taskId == tid) {
                    e.copy(
                        state = task.state,
                        sourceLanguage = task.sourceLanguage,
                        totalChunks = task.totalChunks,
                        transcribedChunks = task.transcribedChunks,
                        failedChunks = task.failedChunks,
                        createdAt = task.createdAt,
                        updatedAt = task.updatedAt,
                    )
                } else {
                    e
                }
            }
        if (nextTasks != prev.tasks) {
            writeTasksIndex(sid, prev.copy(generatedAtSec = nowSec(), tasks = nextTasks))
        }
    }

    fun cancelTask(
        sessionId: String,
        taskId: String,
    ): TranscriptTaskV1 {
        val sid = sessionId.trim()
        val tid = taskId.trim()
        val prev = readTaskOrNull(sid, tid) ?: error("task not found: $tid")
        val now = TranscriptTaskV1.nowIso()
        val next =
            prev.copy(
                state = "cancelled",
                updatedAt = now,
            )
        writeTask(next)
        writeTaskStatus(sid, tid, ok = false, note = "cancelled")
        return next
    }

    fun readTasksIndexOrNull(sessionId: String): TranscriptTasksIndexV1? {
        val sid = sessionId.trim()
        if (sid.isBlank()) return null
        val ref = sessionRefOrFallback(sid)
        val path = ref.transcriptTasksIndexPath
        val raw =
            try {
                if (!ws.exists(path)) return null
                ws.readTextFile(path, maxBytes = 2L * 1024L * 1024L)
            } catch (_: Throwable) {
                return null
            }
        return runCatching { TranscriptTasksIndexV1.parse(raw) }.getOrNull()
    }

    fun writeTasksIndex(
        sessionId: String,
        index: TranscriptTasksIndexV1,
    ) {
        val sid = sessionId.trim()
        if (sid.isBlank()) error("missing sessionId")
        val ref = sessionRefOrFallback(sid)
        ws.writeTextFileAtomic(ref.transcriptTasksIndexPath, toPrettyRaw(index.toJsonObject()))
    }

    fun writeTaskStatus(
        sessionId: String,
        taskId: String,
        ok: Boolean,
        note: String,
    ) {
        val sid = sessionId.trim()
        val tid = taskId.trim()
        val at = nowSec()
        val ref = sessionRefOrFallback(sid)
        val content =
            buildString {
                appendLine("# 转录任务状态")
                appendLine()
                appendLine("- task_id: $tid")
                appendLine("- session_id: $sid")
                appendLine("- ok: ${if (ok) "true" else "false"}")
                appendLine("- at: $at")
                appendLine("- note: ${note.trim().ifBlank { "—" }}")
            }
        ws.writeTextFile(ref.transcriptTaskStatusMd(tid), content)
    }

    fun toPrettyRaw(obj: JsonObject): String = prettyJson.encodeToString(JsonObject.serializer(), obj) + "\n"

    private fun randomToken(len: Int): String {
        val n = len.coerceIn(1, 32)
        val bytes = ByteArray(n)
        RNG.nextBytes(bytes)
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val out = StringBuilder(n)
        for (b in bytes) {
            val idx = (b.toInt() and 0xff) % alphabet.length
            out.append(alphabet[idx])
        }
        return out.toString()
    }

    companion object {
        private val RNG = SecureRandom()
    }
}
