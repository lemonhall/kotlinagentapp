package com.lsl.kotlin_agent_app.radio_transcript

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class TranscriptTaskV1(
    val schema: String,
    val taskId: String,
    val sessionId: String,
    val state: String,
    val sourceLanguage: String?,
    val totalChunks: Int,
    val transcribedChunks: Int,
    val failedChunks: Int,
    val createdAt: String,
    val updatedAt: String,
    val lastError: ErrorInfo? = null,
) {
    internal data class ErrorInfo(
        val code: String,
        val message: String? = null,
    )

    fun toJsonObject(): JsonObject {
        return buildJsonObject {
            put("schema", JsonPrimitive(schema))
            put("taskId", JsonPrimitive(taskId))
            put("sessionId", JsonPrimitive(sessionId))
            put("state", JsonPrimitive(state))
            put("sourceLanguage", sourceLanguage?.let { JsonPrimitive(it) } ?: JsonNull)
            put("totalChunks", JsonPrimitive(totalChunks))
            put("transcribedChunks", JsonPrimitive(transcribedChunks))
            put("failedChunks", JsonPrimitive(failedChunks))
            put("createdAt", JsonPrimitive(createdAt))
            put("updatedAt", JsonPrimitive(updatedAt))
            if (lastError == null) {
                put("lastError", JsonNull)
            } else {
                put(
                    "lastError",
                    buildJsonObject {
                        put("code", JsonPrimitive(lastError.code))
                        put("message", lastError.message?.let { JsonPrimitive(it) } ?: JsonNull)
                    },
                )
            }
        }
    }

    companion object {
        const val SCHEMA_V1 = "kotlin-agent-app/radio-transcript-task@v1"

        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun nowIso(): String = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        fun parse(raw: String): TranscriptTaskV1 {
            val el: JsonElement =
                try {
                    json.parseToJsonElement(raw)
                } catch (t: Throwable) {
                    throw IllegalArgumentException("invalid _task.json (expected json)", t)
                }
            val o: JsonObject =
                try {
                    el.jsonObject
                } catch (t: Throwable) {
                    throw IllegalArgumentException("invalid _task.json (expected object)", t)
                }

            fun str(key: String): String? {
                val v = runCatching { o[key]?.jsonPrimitive?.content }.getOrNull() ?: return null
                return v.trim().ifBlank { null }
            }

            val schema = str("schema").orEmpty()
            if (schema != SCHEMA_V1) error("unsupported schema: $schema")

            val taskId = str("taskId").orEmpty()
            val sessionId = str("sessionId").orEmpty()
            val state = str("state").orEmpty()
            val sourceLanguage = str("sourceLanguage")
            val totalChunks = str("totalChunks")?.toIntOrNull() ?: 0
            val transcribedChunks = str("transcribedChunks")?.toIntOrNull() ?: 0
            val failedChunks = str("failedChunks")?.toIntOrNull() ?: 0
            val createdAt = str("createdAt").orEmpty()
            val updatedAt = str("updatedAt").orEmpty()

            val errorObj = o["lastError"] as? JsonObject
            val lastError =
                errorObj?.let { eo ->
                    val code = runCatching { eo["code"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.ifBlank { null } ?: return@let null
                    ErrorInfo(code = code, message = runCatching { eo["message"]?.jsonPrimitive?.content }.getOrNull())
                }

            return TranscriptTaskV1(
                schema = schema,
                taskId = taskId,
                sessionId = sessionId,
                state = state,
                sourceLanguage = sourceLanguage,
                totalChunks = totalChunks,
                transcribedChunks = transcribedChunks,
                failedChunks = failedChunks,
                createdAt = createdAt,
                updatedAt = updatedAt,
                lastError = lastError,
            )
        }
    }
}

