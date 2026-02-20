package com.lsl.kotlin_agent_app.radio_transcript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class TranscriptTasksIndexV1(
    val generatedAtSec: Long,
    val tasks: List<TaskEntry>,
) {
    internal data class TaskEntry(
        val taskId: String,
        val sessionId: String,
        val state: String,
        val sourceLanguage: String?,
        val totalChunks: Int,
        val transcribedChunks: Int,
        val failedChunks: Int,
        val createdAt: String,
        val updatedAt: String,
    )

    fun toJsonObject(): JsonObject {
        return buildJsonObject {
            put("schema", JsonPrimitive(SCHEMA_V1))
            put("generatedAtSec", JsonPrimitive(generatedAtSec))
            put(
                "tasks",
                buildJsonArray {
                    for (t in tasks) {
                        add(
                            buildJsonObject {
                                put("taskId", JsonPrimitive(t.taskId))
                                put("sessionId", JsonPrimitive(t.sessionId))
                                put("state", JsonPrimitive(t.state))
                                t.sourceLanguage?.let { put("sourceLanguage", JsonPrimitive(it)) }
                                put("totalChunks", JsonPrimitive(t.totalChunks))
                                put("transcribedChunks", JsonPrimitive(t.transcribedChunks))
                                put("failedChunks", JsonPrimitive(t.failedChunks))
                                put("createdAt", JsonPrimitive(t.createdAt))
                                put("updatedAt", JsonPrimitive(t.updatedAt))
                            },
                        )
                    }
                },
            )
        }
    }

    companion object {
        const val SCHEMA_V1 = "kotlin-agent-app/radio-transcript-tasks-index@v1"

        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun parse(raw: String): TranscriptTasksIndexV1 {
            val el: JsonElement =
                try {
                    json.parseToJsonElement(raw)
                } catch (t: Throwable) {
                    throw IllegalArgumentException("invalid _tasks.index.json (expected json)", t)
                }
            val o: JsonObject =
                try {
                    el.jsonObject
                } catch (t: Throwable) {
                    throw IllegalArgumentException("invalid _tasks.index.json (expected object)", t)
                }

            fun str(key: String): String? {
                val v = runCatching { o[key]?.jsonPrimitive?.content }.getOrNull() ?: return null
                return v.trim().ifBlank { null }
            }

            val schema = str("schema").orEmpty()
            if (schema != SCHEMA_V1) error("unsupported schema: $schema")

            val generatedAt = str("generatedAtSec")?.toLongOrNull() ?: 0L
            val arr: JsonArray = o["tasks"]?.jsonArray ?: JsonArray(emptyList())
            val tasks =
                arr.mapNotNull { te ->
                    val to = te as? JsonObject ?: return@mapNotNull null
                    fun sstr(k: String): String? = runCatching { to[k]?.jsonPrimitive?.content }.getOrNull()?.trim()?.ifBlank { null }
                    fun sint(k: String): Int? = runCatching { to[k]?.jsonPrimitive?.content }.getOrNull()?.trim()?.toIntOrNull()
                    val taskId = sstr("taskId") ?: return@mapNotNull null
                    val sessionId = sstr("sessionId") ?: return@mapNotNull null
                    val state = sstr("state") ?: return@mapNotNull null
                    val sourceLanguage = sstr("sourceLanguage")
                    val totalChunks = sint("totalChunks") ?: 0
                    val transcribedChunks = sint("transcribedChunks") ?: 0
                    val failedChunks = sint("failedChunks") ?: 0
                    val createdAt = sstr("createdAt") ?: ""
                    val updatedAt = sstr("updatedAt") ?: ""
                    TaskEntry(
                        taskId = taskId,
                        sessionId = sessionId,
                        state = state,
                        sourceLanguage = sourceLanguage,
                        totalChunks = totalChunks,
                        transcribedChunks = transcribedChunks,
                        failedChunks = failedChunks,
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                    )
                }

            return TranscriptTasksIndexV1(generatedAtSec = generatedAt, tasks = tasks)
        }
    }
}

