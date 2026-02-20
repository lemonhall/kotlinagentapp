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

internal data class TranscriptSegment(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val emotion: String? = null,
)

internal data class TranscriptChunkV1(
    val schema: String,
    val sessionId: String,
    val chunkIndex: Int,
    val detectedLanguage: String? = null,
    val segments: List<TranscriptSegment>,
) {
    fun toJsonObject(): JsonObject {
        return buildJsonObject {
            put("schema", JsonPrimitive(schema))
            put("sessionId", JsonPrimitive(sessionId))
            put("chunkIndex", JsonPrimitive(chunkIndex))
            detectedLanguage?.let { put("detectedLanguage", JsonPrimitive(it)) }
            put(
                "segments",
                buildJsonArray {
                    for (s in segments) {
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
    }

    companion object {
        const val SCHEMA_V1 = "kotlin-agent-app/radio-transcript-chunk@v1"

        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun parse(raw: String): TranscriptChunkV1 {
            val el: JsonElement =
                try {
                    json.parseToJsonElement(raw)
                } catch (t: Throwable) {
                    throw IllegalArgumentException("invalid transcript json (expected json)", t)
                }
            val o: JsonObject =
                try {
                    el.jsonObject
                } catch (t: Throwable) {
                    throw IllegalArgumentException("invalid transcript json (expected object)", t)
                }

            fun str(key: String): String? {
                val v = runCatching { o[key]?.jsonPrimitive?.content }.getOrNull() ?: return null
                return v.trim().ifBlank { null }
            }

            val schema = str("schema").orEmpty()
            if (schema != SCHEMA_V1) error("unsupported schema: $schema")

            val sessionId = str("sessionId").orEmpty()
            val chunkIndex = str("chunkIndex")?.toIntOrNull() ?: 0
            val detectedLanguage = str("detectedLanguage")

            val segsArr: JsonArray = (o["segments"] as? JsonArray) ?: JsonArray(emptyList())
            val segs =
                segsArr.mapNotNull { se ->
                    val so = se as? JsonObject ?: return@mapNotNull null
                    val id = runCatching { so["id"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.toIntOrNull() ?: return@mapNotNull null
                    val startMs = runCatching { so["startMs"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.toLongOrNull() ?: return@mapNotNull null
                    val endMs = runCatching { so["endMs"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.toLongOrNull() ?: return@mapNotNull null
                    val text = runCatching { so["text"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.ifBlank { null } ?: return@mapNotNull null
                    val emotion = runCatching { so["emotion"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.ifBlank { null }
                    TranscriptSegment(id = id, startMs = startMs, endMs = endMs, text = text, emotion = emotion)
                }

            return TranscriptChunkV1(
                schema = schema,
                sessionId = sessionId,
                chunkIndex = chunkIndex,
                detectedLanguage = detectedLanguage,
                segments = segs,
            )
        }
    }
}

