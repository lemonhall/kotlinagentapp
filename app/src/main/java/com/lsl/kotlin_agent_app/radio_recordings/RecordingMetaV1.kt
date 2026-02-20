package com.lsl.kotlin_agent_app.radio_recordings

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class RecordingMetaV1(
    val schema: String,
    val sessionId: String,
    val station: Station,
    val chunkDurationMin: Int,
    val outputFormat: String,
    val state: String,
    val createdAt: String,
    val updatedAt: String,
    val chunks: List<Chunk>,
    val error: ErrorInfo? = null,
    val transcriptRequest: JsonObject? = null,
) {
    internal data class Station(
        val stationId: String,
        val name: String,
        val radioFilePath: String,
        val streamUrl: String,
    )

    internal data class Chunk(
        val file: String,
        val index: Int,
        val startAt: String? = null,
        val endAt: String? = null,
        val durationSec: Int? = null,
        val sizeBytes: Long? = null,
    )

    internal data class ErrorInfo(
        val code: String,
        val message: String? = null,
    )

    fun toJsonObject(): JsonObject {
        return buildJsonObject {
            put("schema", JsonPrimitive(schema))
            put("sessionId", JsonPrimitive(sessionId))
            put(
                "station",
                buildJsonObject {
                    put("stationId", JsonPrimitive(station.stationId))
                    put("name", JsonPrimitive(station.name))
                    put("radioFilePath", JsonPrimitive(station.radioFilePath))
                    put("streamUrl", JsonPrimitive(station.streamUrl))
                },
            )
            put("chunkDurationMin", JsonPrimitive(chunkDurationMin))
            put("outputFormat", JsonPrimitive(outputFormat))
            put("state", JsonPrimitive(state))
            put("createdAt", JsonPrimitive(createdAt))
            put("updatedAt", JsonPrimitive(updatedAt))
            put(
                "chunks",
                buildJsonArray {
                    for (c in chunks) {
                        add(
                            buildJsonObject {
                                put("file", JsonPrimitive(c.file))
                                put("index", JsonPrimitive(c.index))
                                c.startAt?.let { put("startAt", JsonPrimitive(it)) }
                                c.endAt?.let { put("endAt", JsonPrimitive(it)) }
                                c.durationSec?.let { put("durationSec", JsonPrimitive(it)) }
                                c.sizeBytes?.let { put("sizeBytes", JsonPrimitive(it)) }
                            },
                        )
                    }
                },
            )
            if (error == null) {
                put("error", JsonNull)
            } else {
                put(
                    "error",
                    buildJsonObject {
                        put("code", JsonPrimitive(error.code))
                        put("message", error.message?.let { JsonPrimitive(it) } ?: JsonNull)
                    },
                )
            }
            put("transcriptRequest", transcriptRequest ?: JsonNull)
        }
    }

    companion object {
        const val SCHEMA_V1 = "kotlin-agent-app/radio-recording-meta@v1"

        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun nowIso(): String {
            return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }

        fun parse(raw: String): RecordingMetaV1 {
            val el: JsonElement =
                try {
                    json.parseToJsonElement(raw)
                } catch (t: Throwable) {
                    throw IllegalArgumentException("invalid _meta.json (expected json)", t)
                }
            val o: JsonObject =
                try {
                    el.jsonObject
                } catch (t: Throwable) {
                    throw IllegalArgumentException("invalid _meta.json (expected object)", t)
                }

            fun str(key: String): String? {
                val v = runCatching { o[key]?.jsonPrimitive?.content }.getOrNull() ?: return null
                return v.trim().ifBlank { null }
            }

            val schema = str("schema").orEmpty()
            if (schema != SCHEMA_V1) error("unsupported schema: $schema")

            val sessionId = str("sessionId").orEmpty()
            val stationObj = o["station"] as? JsonObject ?: error("missing station")
            fun sstr(key: String): String {
                val v = runCatching { stationObj[key]?.jsonPrimitive?.content }.getOrNull()?.trim().orEmpty()
                if (v.isBlank()) error("missing station.$key")
                return v
            }

            val chunkDurationMin = str("chunkDurationMin")?.toIntOrNull() ?: 10
            val outputFormat = str("outputFormat").orEmpty()
            val state = str("state").orEmpty()
            val createdAt = str("createdAt").orEmpty()
            val updatedAt = str("updatedAt").orEmpty()

            val chunksArr: JsonArray = (o["chunks"] as? JsonArray) ?: JsonArray(emptyList())
            val chunks =
                chunksArr.mapNotNull { ce ->
                    val co = ce as? JsonObject ?: return@mapNotNull null
                    val file = runCatching { co["file"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.ifBlank { null } ?: return@mapNotNull null
                    val idx = runCatching { co["index"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.toIntOrNull() ?: return@mapNotNull null
                    Chunk(
                        file = file,
                        index = idx,
                        startAt = runCatching { co["startAt"]?.jsonPrimitive?.content }.getOrNull(),
                        endAt = runCatching { co["endAt"]?.jsonPrimitive?.content }.getOrNull(),
                        durationSec = runCatching { co["durationSec"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.toIntOrNull(),
                        sizeBytes = runCatching { co["sizeBytes"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.toLongOrNull(),
                    )
                }

            val errorObj = o["error"] as? JsonObject
            val error =
                errorObj?.let { eo ->
                    val code = runCatching { eo["code"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.ifBlank { null } ?: return@let null
                    ErrorInfo(code = code, message = runCatching { eo["message"]?.jsonPrimitive?.content }.getOrNull())
                }

            val transcriptRequest = (o["transcriptRequest"] as? JsonObject)

            return RecordingMetaV1(
                schema = schema,
                sessionId = sessionId,
                station =
                    Station(
                        stationId = sstr("stationId"),
                        name = sstr("name"),
                        radioFilePath = sstr("radioFilePath"),
                        streamUrl = sstr("streamUrl"),
                    ),
                chunkDurationMin = chunkDurationMin,
                outputFormat = outputFormat,
                state = state,
                createdAt = createdAt,
                updatedAt = updatedAt,
                chunks = chunks,
                error = error,
                transcriptRequest = transcriptRequest,
            )
        }
    }
}

