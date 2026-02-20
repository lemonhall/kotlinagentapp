package com.lsl.kotlin_agent_app.radio_translation

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

internal data class TranslationSegmentV1(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val sourceText: String,
    val translatedText: String,
    val emotion: String? = null,
)

internal data class TranslationChunkV1(
    val schema: String,
    val sessionId: String,
    val chunkIndex: Int,
    val sourceLanguage: String,
    val targetLanguage: String,
    val segments: List<TranslationSegmentV1>,
) {
    fun toJsonObject(): JsonObject {
        return buildJsonObject {
            put("schema", JsonPrimitive(schema))
            put("sessionId", JsonPrimitive(sessionId))
            put("chunkIndex", JsonPrimitive(chunkIndex))
            put("sourceLanguage", JsonPrimitive(sourceLanguage))
            put("targetLanguage", JsonPrimitive(targetLanguage))
            put(
                "segments",
                buildJsonArray {
                    for (s in segments) {
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(s.id))
                                put("startMs", JsonPrimitive(s.startMs))
                                put("endMs", JsonPrimitive(s.endMs))
                                put("sourceText", JsonPrimitive(s.sourceText))
                                put("translatedText", JsonPrimitive(s.translatedText))
                                s.emotion?.let { put("emotion", JsonPrimitive(it)) }
                            },
                        )
                    }
                },
            )
        }
    }

    companion object {
        const val SCHEMA_V1 = "kotlin-agent-app/radio-translation-chunk@v1"

        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun parse(raw: String): TranslationChunkV1 {
            val el: JsonElement =
                try {
                    json.parseToJsonElement(raw)
                } catch (t: Throwable) {
                    throw IllegalArgumentException("invalid translation json (expected json)", t)
                }
            val o: JsonObject =
                try {
                    el.jsonObject
                } catch (t: Throwable) {
                    throw IllegalArgumentException("invalid translation json (expected object)", t)
                }

            fun str(key: String): String? {
                val v = runCatching { o[key]?.jsonPrimitive?.content }.getOrNull() ?: return null
                return v.trim().ifBlank { null }
            }

            val schema = str("schema").orEmpty()
            if (schema != SCHEMA_V1) error("unsupported schema: $schema")

            val sessionId = str("sessionId").orEmpty()
            val chunkIndex = str("chunkIndex")?.toIntOrNull() ?: 0
            val sourceLanguage = str("sourceLanguage").orEmpty()
            val targetLanguage = str("targetLanguage").orEmpty()

            val segsArr: JsonArray = (o["segments"] as? JsonArray) ?: JsonArray(emptyList())
            val segs =
                segsArr.mapNotNull { se ->
                    val so = se as? JsonObject ?: return@mapNotNull null
                    val id = runCatching { so["id"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.toIntOrNull() ?: return@mapNotNull null
                    val startMs = runCatching { so["startMs"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.toLongOrNull() ?: return@mapNotNull null
                    val endMs = runCatching { so["endMs"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.toLongOrNull() ?: return@mapNotNull null
                    val src = runCatching { so["sourceText"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.ifBlank { null } ?: return@mapNotNull null
                    val tgt = runCatching { so["translatedText"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.ifBlank { null } ?: return@mapNotNull null
                    val emotion = runCatching { so["emotion"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.ifBlank { null }
                    TranslationSegmentV1(id = id, startMs = startMs, endMs = endMs, sourceText = src, translatedText = tgt, emotion = emotion)
                }

            return TranslationChunkV1(
                schema = schema,
                sessionId = sessionId,
                chunkIndex = chunkIndex,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                segments = segs,
            )
        }
    }
}

