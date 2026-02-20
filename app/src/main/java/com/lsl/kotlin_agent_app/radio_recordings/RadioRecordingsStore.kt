package com.lsl.kotlin_agent_app.radio_recordings

import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class RadioRecordingsStore(
    private val ws: AgentsWorkspace,
    private val rootDir: String = RadioRecordingsPaths.ROOT_DIR,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val prettyJson: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true },
) {
    // Backward-compatible constructor for code compiled before `rootDir` was introduced.
    constructor(
        ws: AgentsWorkspace,
        nowMs: () -> Long = System::currentTimeMillis,
        prettyJson: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true },
    ) : this(
        ws = ws,
        rootDir = RadioRecordingsPaths.ROOT_DIR,
        nowMs = nowMs,
        prettyJson = prettyJson,
    )

    private val rootDirNormalized = rootDir.replace('\\', '/').trim().trimEnd('/').ifBlank { RadioRecordingsPaths.ROOT_DIR }
    private val rootName = rootDirNormalized.substringAfterLast('/').ifBlank { "recordings" }

    private fun sessionDir(sessionId: String): String = "${rootDirNormalized}/${sessionId.trim()}"

    private fun sessionMetaJson(sessionId: String): String = "${sessionDir(sessionId)}/_meta.json"

    private fun sessionStatusMd(sessionId: String): String = "${sessionDir(sessionId)}/_STATUS.md"

    private fun rootStatusMd(): String = "${rootDirNormalized}/_STATUS.md"

    private fun rootIndexJson(): String = "${rootDirNormalized}/.recordings.index.json"

    fun ensureRoot() {
        ws.mkdir(rootDirNormalized)
        if (!ws.exists(rootStatusMd())) {
            ws.writeTextFile(rootStatusMd(), renderRootStatus(ok = true, note = "ready"))
        }
        if (!ws.exists(rootIndexJson())) {
            val idx = RecordingsIndexV1(generatedAtSec = nowSec(), sessions = emptyList())
            ws.writeTextFile(rootIndexJson(), prettyJson.encodeToString(JsonObject.serializer(), idx.toJsonObject()) + "\n")
        }
    }

    fun allocateSessionId(prefix: String = "rec"): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val ts = fmt.format(Date(nowMs()))
        val rand = randomToken(6)
        return "${prefix}_${ts}_$rand"
    }

    fun readIndexOrNull(): RecordingsIndexV1? {
        val raw =
            try {
                if (!ws.exists(rootIndexJson())) return null
                ws.readTextFile(rootIndexJson(), maxBytes = 2L * 1024L * 1024L)
            } catch (_: Throwable) {
                return null
            }
        return runCatching { RecordingsIndexV1.parse(raw) }.getOrNull()
    }

    fun writeIndex(index: RecordingsIndexV1) {
        val raw = prettyJson.encodeToString(JsonObject.serializer(), index.toJsonObject()) + "\n"
        ws.writeTextFileAtomic(rootIndexJson(), raw)
    }

    fun writeSessionMeta(sessionId: String, meta: RecordingMetaV1) {
        val raw = prettyJson.encodeToString(JsonObject.serializer(), meta.toJsonObject()) + "\n"
        ws.writeTextFileAtomic(sessionMetaJson(sessionId), raw)
    }

    fun writeSessionStatus(sessionId: String, ok: Boolean, note: String) {
        ws.writeTextFile(sessionStatusMd(sessionId), renderSessionStatus(sessionId = sessionId, ok = ok, note = note))
    }

    fun updateRootStatus(ok: Boolean, note: String) {
        ws.writeTextFile(rootStatusMd(), renderRootStatus(ok = ok, note = note))
    }

    fun nowSec(): Long = (nowMs() / 1000L).coerceAtLeast(0L)

    fun appendChunk(sessionId: String, chunkIndex: Int) {
        val idx = chunkIndex.coerceAtLeast(1)
        val metaPath = sessionMetaJson(sessionId)
        if (!ws.exists(metaPath)) error("missing _meta.json: $sessionId")

        val raw = ws.readTextFile(metaPath, maxBytes = 2L * 1024L * 1024L)
        val prev = RecordingMetaV1.parse(raw)

        // If session already finalized, don't resurrect it.
        val prevState = prev.state.trim()
        if (prevState == "completed" || prevState == "cancelled" || prevState == "failed") return

        val fileName = "chunk_${idx.toString().padStart(3, '0')}.ogg"
        val existing = prev.chunks.any { it.index == idx || it.file.trim() == fileName }
        if (existing) return

        val nextChunks = prev.chunks.toMutableList()
        nextChunks.add(
            RecordingMetaV1.Chunk(
                file = fileName,
                index = idx,
            ),
        )

        val next =
            prev.copy(
                state = "recording",
                updatedAt = RecordingMetaV1.nowIso(),
                chunks = nextChunks,
            )
        writeSessionMeta(sessionId, next)
        writeSessionStatus(sessionId, ok = true, note = "recording")

        val rootIdx = readIndexOrNull()
        if (rootIdx != null) {
            val updated =
                rootIdx.sessions.map { s ->
                    if (s.sessionId == sessionId) s.copy(state = "recording", chunksCount = nextChunks.size) else s
                }
            writeIndex(rootIdx.copy(generatedAtSec = nowSec(), sessions = updated))
        }
    }

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

    private fun renderRootStatus(ok: Boolean, note: String): String {
        return buildString {
            appendLine("# ${rootName} 状态")
            appendLine()
            appendLine("- ok: ${if (ok) "true" else "false"}")
            appendLine("- at: ${nowSec()}")
            appendLine("- note: ${note.trim().ifBlank { "—" }}")
            appendLine()
            appendLine("提示：录制产物按会话目录落盘，点击会话目录可查看 chunk 与 _meta.json。")
        }
    }

    private fun renderSessionStatus(sessionId: String, ok: Boolean, note: String): String {
        return buildString {
            appendLine("# 录制会话状态")
            appendLine()
            appendLine("- session_id: $sessionId")
            appendLine("- ok: ${if (ok) "true" else "false"}")
            appendLine("- at: ${nowSec()}")
            appendLine("- note: ${note.trim().ifBlank { "—" }}")
        }
    }

    companion object {
        private val RNG = SecureRandom()
    }
}
