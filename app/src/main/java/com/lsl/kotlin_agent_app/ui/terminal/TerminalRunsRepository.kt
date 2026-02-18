package com.lsl.kotlin_agent_app.ui.terminal

import android.content.Context
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TerminalRunsRepository(
    context: Context,
    private val maxItems: Int = 200,
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun listRecentRuns(): List<TerminalRunResult> {
        val dir = runsDir()
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val files =
            dir.listFiles { f -> f.isFile && f.name.endsWith(".json", ignoreCase = true) }
                .orEmpty()
                .sortedByDescending { it.lastModified() }
                .take(maxItems)

        val out = ArrayList<TerminalRunResult>(files.size)
        for (f in files) {
            val parsed = parseAuditFileOrNull(f) ?: continue
            out.add(parsed)
        }
        return out.sortedByDescending { it.summary.timestampMs }
    }

    fun runsDir(): File = File(appContext.filesDir, ".agents/artifacts/terminal_exec/runs")

    private fun parseAuditFileOrNull(file: File): TerminalRunResult? {
        val raw =
            try {
                file.readText(Charsets.UTF_8)
            } catch (_: Throwable) {
                return null
            }
        val el =
            try {
                json.parseToJsonElement(raw)
            } catch (_: Throwable) {
                return null
            }
        val obj = el as? JsonObject ?: return null

        fun content(el: Any?): String? = (el as? JsonPrimitive)?.contentOrNullSafe()
        fun str(key: String): String = content(obj[key]).orEmpty()
        fun long(key: String): Long = content(obj[key])?.toLongOrNull() ?: 0L
        fun int(key: String): Int = content(obj[key])?.toIntOrNull() ?: 0
        fun optStr(key: String): String? = content(obj[key])

        val runId = str("run_id").ifBlank { file.name.removeSuffix(".json") }
        val timestamp = long("timestamp_ms").takeIf { it > 0 } ?: file.lastModified()
        val command = str("command")
        val exitCode = int("exit_code")
        val durationMs = long("duration_ms")
        val stdout = str("stdout")
        val stderr = str("stderr")
        val errorCode = optStr("error_code")?.trim()?.ifBlank { null }
        val errorMessage = optStr("error_message")?.trim()?.ifBlank { null }

        val artifacts =
            (obj["artifacts"] as? JsonArray)?.jsonArray?.mapNotNull { a ->
                val ao = a as? JsonObject ?: return@mapNotNull null
                val path = content(ao["path"])?.trim().orEmpty()
                val mime = content(ao["mime"])?.trim().orEmpty()
                val desc = content(ao["description"])?.trim().orEmpty()
                if (path.isBlank()) return@mapNotNull null
                TerminalRunArtifact(path = path, mime = mime, description = desc)
            }.orEmpty()

        val summary =
            TerminalRunSummary(
                runId = runId,
                timestampMs = timestamp,
                command = command,
                exitCode = exitCode,
                durationMs = durationMs,
                stdout = stdout,
                stderr = stderr,
                errorCode = errorCode,
                errorMessage = errorMessage,
            )
        return TerminalRunResult(summary = summary, artifacts = artifacts)
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        try {
            content
        } catch (_: Throwable) {
            null
        }
}
