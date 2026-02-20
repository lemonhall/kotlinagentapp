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
    val source: String? = null,
    val title: String? = null,
    val station: Station? = null,
    val chunkDurationMin: Int,
    val outputFormat: String,
    val state: String,
    val createdAt: String,
    val updatedAt: String,
    val chunks: List<Chunk>,
    val durationMs: Long? = null,
    val sampleRate: Int? = null,
    val bitrate: Int? = null,
    val error: ErrorInfo? = null,
    val transcriptRequest: JsonObject? = null,
    val pipeline: Pipeline? = null,
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

    internal data class Pipeline(
        val targetLanguage: String? = null,
        val transcriptState: String = "pending",
        val translationState: String = "pending",
        val transcribedChunks: Int = 0,
        val translatedChunks: Int = 0,
        val failedChunks: Int = 0,
        val lastError: ErrorInfo? = null,
    )

    // Backward-compatible constructor for code compiled against pre-Recorder schema.
    constructor(
        schema: String,
        sessionId: String,
        station: Station,
        chunkDurationMin: Int,
        outputFormat: String,
        state: String,
        createdAt: String,
        updatedAt: String,
        chunks: List<Chunk>,
        error: ErrorInfo? = null,
        transcriptRequest: JsonObject? = null,
        pipeline: Pipeline? = null,
    ) : this(
        schema = schema,
        sessionId = sessionId,
        source = null,
        title = null,
        station = station,
        chunkDurationMin = chunkDurationMin,
        outputFormat = outputFormat,
        state = state,
        createdAt = createdAt,
        updatedAt = updatedAt,
        chunks = chunks,
        durationMs = null,
        sampleRate = null,
        bitrate = null,
        error = error,
        transcriptRequest = transcriptRequest,
        pipeline = pipeline,
    )

    fun toJsonObject(): JsonObject {
        return buildJsonObject {
            put("schema", JsonPrimitive(schema))
            put("sessionId", JsonPrimitive(sessionId))
            put("source", source?.let { JsonPrimitive(it) } ?: JsonNull)
            put("title", title?.let { JsonPrimitive(it) } ?: JsonNull)
            if (station == null) {
                put("station", JsonNull)
            } else {
                put(
                    "station",
                    buildJsonObject {
                        put("stationId", JsonPrimitive(station.stationId))
                        put("name", JsonPrimitive(station.name))
                        put("radioFilePath", JsonPrimitive(station.radioFilePath))
                        put("streamUrl", JsonPrimitive(station.streamUrl))
                    },
                )
            }
            put("chunkDurationMin", JsonPrimitive(chunkDurationMin))
            put("outputFormat", JsonPrimitive(outputFormat))
            put("state", JsonPrimitive(state))
            put("createdAt", JsonPrimitive(createdAt))
            put("updatedAt", JsonPrimitive(updatedAt))
            put("durationMs", durationMs?.let { JsonPrimitive(it) } ?: JsonNull)
            put("sampleRate", sampleRate?.let { JsonPrimitive(it) } ?: JsonNull)
            put("bitrate", bitrate?.let { JsonPrimitive(it) } ?: JsonNull)
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
            if (pipeline == null) {
                put("pipeline", JsonNull)
            } else {
                put(
                    "pipeline",
                    buildJsonObject {
                        put("targetLanguage", pipeline.targetLanguage?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("transcriptState", JsonPrimitive(pipeline.transcriptState))
                        put("translationState", JsonPrimitive(pipeline.translationState))
                        put("transcribedChunks", JsonPrimitive(pipeline.transcribedChunks))
                        put("translatedChunks", JsonPrimitive(pipeline.translatedChunks))
                        put("failedChunks", JsonPrimitive(pipeline.failedChunks))
                        if (pipeline.lastError == null) {
                            put("lastError", JsonNull)
                        } else {
                            put(
                                "lastError",
                                buildJsonObject {
                                    put("code", JsonPrimitive(pipeline.lastError.code))
                                    put("message", pipeline.lastError.message?.let { JsonPrimitive(it) } ?: JsonNull)
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    companion object {
        const val SCHEMA_RECORDING_V1 = "kotlin-agent-app/recording-meta@v1"
        const val SCHEMA_RADIO_V1 = "kotlin-agent-app/radio-recording-meta@v1"

        // Default for new writes.
        const val SCHEMA_V1 = SCHEMA_RECORDING_V1

        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun nowIso(): String {
            val now = OffsetDateTime.now()
            return now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
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
            val supported = setOf(SCHEMA_RECORDING_V1, SCHEMA_RADIO_V1)
            if (schema.isNotBlank() && schema !in supported) error("unsupported schema: $schema")

            val sessionId = str("sessionId").orEmpty()
            if (sessionId.isBlank()) error("missing sessionId")

            val title = str("title")

            val stationObj = o["station"] as? JsonObject
            fun parseStation(so: JsonObject): Station? {
                fun sstr(key: String): String {
                    val v = runCatching { so[key]?.jsonPrimitive?.content }.getOrNull()?.trim().orEmpty()
                    if (v.isBlank()) error("missing station.$key")
                    return v
                }
                return Station(
                    stationId = sstr("stationId"),
                    name = sstr("name"),
                    radioFilePath = sstr("radioFilePath"),
                    streamUrl = sstr("streamUrl"),
                )
            }

            val station = stationObj?.let { runCatching { parseStation(it) }.getOrNull() }
            val source =
                str("source")
                    ?: run {
                        if (station != null) "radio" else "microphone"
                    }

            val chunkDurationMin = str("chunkDurationMin")?.toIntOrNull() ?: 10
            val outputFormat = str("outputFormat").orEmpty()
            val state = str("state").orEmpty()
            val createdAt = str("createdAt").orEmpty()
            val updatedAt = (str("updatedAt") ?: createdAt).orEmpty()

            val durationMs = str("durationMs")?.toLongOrNull()
            val sampleRate = str("sampleRate")?.toIntOrNull()
            val bitrate = str("bitrate")?.toIntOrNull()

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

            val finalChunks =
                if (chunks.isNotEmpty()) {
                    chunks
                } else {
                    val total = str("totalChunks")?.toIntOrNull() ?: 0
                    if (total <= 0) emptyList()
                    else {
                        (1..total).map { idx ->
                            Chunk(file = "chunk_${idx.toString().padStart(3, '0')}.ogg", index = idx)
                        }
                    }
                }

            val errorObj = o["error"] as? JsonObject
            val error =
                errorObj?.let { eo ->
                    val code = runCatching { eo["code"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.ifBlank { null } ?: return@let null
                    ErrorInfo(code = code, message = runCatching { eo["message"]?.jsonPrimitive?.content }.getOrNull())
                }

            val transcriptRequest = (o["transcriptRequest"] as? JsonObject)

            val pipelineObj = o["pipeline"] as? JsonObject
            val pipeline =
                pipelineObj?.let { po ->
                    val targetLanguage = runCatching { po["targetLanguage"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.ifBlank { null }
                    val transcriptState = runCatching { po["transcriptState"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.ifBlank { null } ?: "pending"
                    val translationState = runCatching { po["translationState"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.ifBlank { null } ?: "pending"
                    val transcribedChunks = runCatching { po["transcribedChunks"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.toIntOrNull() ?: 0
                    val translatedChunks = runCatching { po["translatedChunks"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.toIntOrNull() ?: 0
                    val failedChunks = runCatching { po["failedChunks"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.toIntOrNull() ?: 0
                    val lastErrorObj = po["lastError"] as? JsonObject
                    val lastError =
                        if (lastErrorObj == null) {
                            null
                        } else {
                            val code = runCatching { lastErrorObj["code"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.ifBlank { null }
                            if (code == null) {
                                null
                            } else {
                                ErrorInfo(code = code, message = runCatching { lastErrorObj["message"]?.jsonPrimitive?.content }.getOrNull())
                            }
                        }
                    Pipeline(
                        targetLanguage = targetLanguage,
                        transcriptState = transcriptState,
                        translationState = translationState,
                        transcribedChunks = transcribedChunks,
                        translatedChunks = translatedChunks,
                        failedChunks = failedChunks,
                        lastError = lastError,
                    )
                }

            return RecordingMetaV1(
                schema = if (schema.isBlank()) SCHEMA_V1 else schema,
                sessionId = sessionId,
                source = source,
                title = title,
                station = station,
                chunkDurationMin = chunkDurationMin,
                outputFormat = outputFormat,
                state = state,
                createdAt = createdAt,
                updatedAt = updatedAt,
                chunks = finalChunks,
                durationMs = durationMs,
                sampleRate = sampleRate,
                bitrate = bitrate,
                error = error,
                transcriptRequest = transcriptRequest,
                pipeline = pipeline,
            )
        }
    }
}
