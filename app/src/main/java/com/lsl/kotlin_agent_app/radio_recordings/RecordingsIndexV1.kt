package com.lsl.kotlin_agent_app.radio_recordings

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

internal data class RecordingsIndexV1(
    val generatedAtSec: Long,
    val sessions: List<SessionEntry>,
) {
    internal data class SessionEntry(
        val sessionId: String,
        val dir: String,
        val stationName: String,
        val state: String,
        val startAt: String,
        val chunksCount: Int,
    )

    fun toJsonObject(): JsonObject {
        return buildJsonObject {
            put("schema", JsonPrimitive(SCHEMA_V1))
            put("generatedAtSec", JsonPrimitive(generatedAtSec))
            put(
                "sessions",
                buildJsonArray {
                    for (s in sessions) {
                        add(
                            buildJsonObject {
                                put("sessionId", JsonPrimitive(s.sessionId))
                                put("dir", JsonPrimitive(s.dir))
                                put("stationName", JsonPrimitive(s.stationName))
                                put("state", JsonPrimitive(s.state))
                                put("startAt", JsonPrimitive(s.startAt))
                                put("chunksCount", JsonPrimitive(s.chunksCount))
                            },
                        )
                    }
                },
            )
        }
    }

    companion object {
        const val SCHEMA_V1 = "kotlin-agent-app/radio-recordings-index@v1"

        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun parse(raw: String): RecordingsIndexV1 {
            val el: JsonElement =
                try {
                    json.parseToJsonElement(raw)
                } catch (t: Throwable) {
                    throw IllegalArgumentException("invalid .recordings.index.json (expected json)", t)
                }
            val o: JsonObject =
                try {
                    el.jsonObject
                } catch (t: Throwable) {
                    throw IllegalArgumentException("invalid .recordings.index.json (expected object)", t)
                }

            fun str(key: String): String? {
                val v = runCatching { o[key]?.jsonPrimitive?.content }.getOrNull() ?: return null
                return v.trim().ifBlank { null }
            }

            val schema = str("schema").orEmpty()
            if (schema != SCHEMA_V1) error("unsupported schema: $schema")

            val generatedAt = str("generatedAtSec")?.toLongOrNull() ?: 0L
            val arr: JsonArray = o["sessions"]?.jsonArray ?: JsonArray(emptyList())
            val sessions =
                arr.mapNotNull { se ->
                    val so = se as? JsonObject ?: return@mapNotNull null
                    fun sstr(k: String): String? = runCatching { so[k]?.jsonPrimitive?.content }.getOrNull()?.trim()?.ifBlank { null }
                    fun sint(k: String): Int? = runCatching { so[k]?.jsonPrimitive?.content }.getOrNull()?.trim()?.toIntOrNull()
                    val sessionId = sstr("sessionId") ?: return@mapNotNull null
                    val dir = sstr("dir") ?: return@mapNotNull null
                    val stationName = sstr("stationName") ?: return@mapNotNull null
                    val state = sstr("state") ?: return@mapNotNull null
                    val startAt = sstr("startAt") ?: return@mapNotNull null
                    val chunksCount = sint("chunksCount") ?: 0
                    SessionEntry(
                        sessionId = sessionId,
                        dir = dir,
                        stationName = stationName,
                        state = state,
                        startAt = startAt,
                        chunksCount = chunksCount,
                    )
                }

            return RecordingsIndexV1(generatedAtSec = generatedAt, sessions = sessions)
        }
    }
}

