package com.lsl.kotlin_agent_app.radios

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

data class RadioStationFileV1(
    val schema: String,
    val id: String,
    val name: String,
    val streamUrl: String,
    val homepage: String? = null,
    val faviconUrl: String? = null,
    val country: String? = null,
    val state: String? = null,
    val language: String? = null,
    val tags: List<String> = emptyList(),
    val codec: String? = null,
    val bitrateKbps: Int? = null,
    val votes: Int? = null,
    val source: Source? = null,
) {
    data class Source(
        val provider: String? = null,
        val url: String? = null,
        val fetchedAtSec: Long? = null,
    )

    fun validateOrThrow() {
        if (schema.trim() != SCHEMA_V1) error("unsupported schema: $schema")
        if (id.trim().isBlank()) error("missing id")
        if (name.trim().isBlank()) error("missing name")
        val u = streamUrl.trim()
        if (u.isBlank()) error("missing streamUrl")
        if (!u.startsWith("http://") && !u.startsWith("https://")) error("unsupported streamUrl: $streamUrl")
    }

    fun toJsonObject(): JsonObject {
        return buildJsonObject {
            put("schema", JsonPrimitive(schema))
            put("id", JsonPrimitive(id))
            put("name", JsonPrimitive(name))
            put("streamUrl", JsonPrimitive(streamUrl))
            homepage?.let { put("homepage", JsonPrimitive(it)) }
            faviconUrl?.let { put("faviconUrl", JsonPrimitive(it)) }
            country?.let { put("country", JsonPrimitive(it)) }
            state?.let { put("state", JsonPrimitive(it)) }
            language?.let { put("language", JsonPrimitive(it)) }
            if (tags.isNotEmpty()) {
                put(
                    "tags",
                    buildJsonArray {
                        for (t in tags) add(JsonPrimitive(t))
                    },
                )
            }
            codec?.let { put("codec", JsonPrimitive(it)) }
            bitrateKbps?.let { put("bitrateKbps", JsonPrimitive(it)) }
            votes?.let { put("votes", JsonPrimitive(it)) }
            source?.let { s ->
                put(
                    "source",
                    buildJsonObject {
                        s.provider?.let { put("provider", JsonPrimitive(it)) }
                        s.url?.let { put("url", JsonPrimitive(it)) }
                        s.fetchedAtSec?.let { put("fetchedAtSec", JsonPrimitive(it)) }
                    },
                )
            }
        }
    }

    companion object {
        const val SCHEMA_V1 = "kotlin-agent-app/radio-station@v1"

        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun parse(raw: String): RadioStationFileV1 {
            val el: JsonElement =
                try {
                    json.parseToJsonElement(raw)
                } catch (t: Throwable) {
                    throw IllegalArgumentException("invalid .radio json", t)
                }
            val o: JsonObject =
                try {
                    el.jsonObject
                } catch (t: Throwable) {
                    throw IllegalArgumentException("invalid .radio json (expected object)", t)
                }

            fun str(key: String): String? {
                val raw = runCatching { o[key]?.jsonPrimitive?.content }.getOrNull() ?: return null
                return raw.trim().ifBlank { null }
            }

            fun int(key: String): Int? {
                val raw = runCatching { o[key]?.jsonPrimitive?.content }.getOrNull() ?: return null
                return raw.trim().toIntOrNull()
            }

            val tags: List<String> =
                try {
                    val arr = o["tags"]?.jsonArray ?: emptyList()
                    arr.mapNotNull { el ->
                        runCatching { el.jsonPrimitive.content }.getOrNull()?.trim()?.ifBlank { null }
                    }
                } catch (_: Throwable) {
                    emptyList()
                }

            val sourceObj = o["source"] as? JsonObject
            val source =
                sourceObj?.let { so ->
                    Source(
                        provider = runCatching { so["provider"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.ifBlank { null },
                        url = runCatching { so["url"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.ifBlank { null },
                        fetchedAtSec = runCatching { so["fetchedAtSec"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.toLongOrNull(),
                    )
                }

            val st =
                RadioStationFileV1(
                    schema = str("schema").orEmpty(),
                    id = str("id").orEmpty(),
                    name = str("name").orEmpty(),
                    streamUrl = str("streamUrl").orEmpty(),
                    homepage = str("homepage"),
                    faviconUrl = str("faviconUrl") ?: str("favicon"),
                    country = str("country"),
                    state = str("state"),
                    language = str("language"),
                    tags = tags,
                    codec = str("codec"),
                    bitrateKbps = int("bitrateKbps") ?: int("bitrate"),
                    votes = int("votes"),
                    source = source,
                )

            try {
                st.validateOrThrow()
            } catch (t: Throwable) {
                throw IllegalArgumentException(t.message ?: "invalid .radio", t)
            }
            return st
        }
    }
}
