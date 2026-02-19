package com.lsl.kotlin_agent_app.radios

import com.lsl.kotlin_agent_app.agent.AgentsDirEntryType
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import java.util.Locale
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

internal data class RadioSyncOutcome(
    val ok: Boolean,
    val message: String? = null,
)

internal data class RadioCountriesIndex(
    val generatedAtSec: Long,
    val countries: List<CountryEntry>,
) {
    data class CountryEntry(
        val dir: String,
        val name: String,
        val code: String? = null,
        val stationCount: Int? = null,
    )
}

internal data class RadioCacheMeta(
    val fetchedAtSec: Long,
)

internal class RadioRepository(
    private val ws: AgentsWorkspace,
    private val api: RadioBrowserApi = RadioBrowserClient(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    private val countriesTtlMs: Long = 72L * 3600L * 1000L,
    private val stationsTtlMs: Long = 72L * 3600L * 1000L,
    private val stationsLimit: Int = 200,
) {
    private val prettyJson: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true }

    fun ensureRoot() {
        ws.mkdir(RADIOS_DIR)
        ws.mkdir(FAVORITES_DIR)
    }

    fun readCountriesIndexOrNull(): RadioCountriesIndex? {
        val raw =
            try {
                if (!ws.exists(COUNTRIES_INDEX_PATH)) return null
                ws.readTextFile(COUNTRIES_INDEX_PATH, maxBytes = 2L * 1024L * 1024L)
            } catch (_: Throwable) {
                return null
            }

        val obj =
            try {
                json.parseToJsonElement(raw).jsonObject
            } catch (_: Throwable) {
                return null
            }

        val schema = obj.str("schema")
        if (schema != COUNTRIES_INDEX_SCHEMA) return null

        val generatedAt = obj.long("generatedAtSec") ?: return null
        val arr: JsonArray =
            try {
                obj["countries"]?.jsonArray ?: return null
            } catch (_: Throwable) {
                return null
            }
        val countries =
            arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val dir = o.str("dir")?.trim()?.ifBlank { null } ?: return@mapNotNull null
                val name = o.str("name")?.trim()?.ifBlank { null } ?: return@mapNotNull null
                RadioCountriesIndex.CountryEntry(
                    dir = dir,
                    name = name,
                    code = o.str("code"),
                    stationCount = o.int("stationCount"),
                )
            }

        return RadioCountriesIndex(generatedAtSec = generatedAt, countries = countries)
    }

    fun lookupCountryByDir(dirName: String): RadioCountriesIndex.CountryEntry? {
        val idx = readCountriesIndexOrNull() ?: return null
        return idx.countries.firstOrNull { it.dir == dirName.trim() }
    }

    suspend fun syncCountries(force: Boolean): RadioSyncOutcome {
        ensureRoot()

        val cached = readCountriesIndexOrNull()
        val meta = readMetaOrNull(COUNTRIES_META_PATH)
        val expired = meta == null || isExpired(meta, countriesTtlMs)
        if (!force && cached != null && !expired) {
            ensureCountryDirsExist(cached)
            writeRootStatus(ok = true, note = "使用缓存（未过期）")
            return RadioSyncOutcome(ok = true)
        }

        return try {
            val raw = api.listCountries()
            val mapped =
                raw.mapNotNull { c ->
                    val name = c.name?.trim()?.ifBlank { null } ?: return@mapNotNull null
                    val code = c.iso3166_1?.trim()?.uppercase(Locale.ROOT)?.ifBlank { null }
                    val dir0 = RadioPathNaming.countryDirName(countryName = name, iso3166_1 = code)
                    RadioCountriesIndex.CountryEntry(
                        dir = dir0,
                        name = name,
                        code = code,
                        stationCount = c.stationCount,
                    )
                }.sortedBy { it.name.lowercase(Locale.ROOT) }

            val seen = HashSet<String>()
            val countries = ArrayList<RadioCountriesIndex.CountryEntry>(mapped.size)
            for (c in mapped) {
                var dir = c.dir
                var n = 1
                while (dir in seen) {
                    dir = "${c.dir}_$n"
                    n += 1
                    if (n >= 1000) break
                }
                seen.add(dir)
                countries.add(if (dir == c.dir) c else c.copy(dir = dir))
            }

            val index = RadioCountriesIndex(generatedAtSec = nowSec(), countries = countries)
            ws.writeTextFile(COUNTRIES_INDEX_PATH, encodeCountriesIndex(index) + "\n")
            writeMeta(COUNTRIES_META_PATH)
            ensureCountryDirsExist(index)
            writeRootStatus(ok = true, note = if (force) "已刷新" else "已更新")
            RadioSyncOutcome(ok = true)
        } catch (t: Throwable) {
            ensureCountryDirsExist(cached)
            val msg = "加载国家/地区列表失败：${t.message ?: "unknown"}（可点刷新重试）"
            writeRootStatus(ok = false, note = msg)
            RadioSyncOutcome(ok = false, message = msg)
        }
    }

    suspend fun syncStationsForCountryDir(
        countryDirName: String,
        force: Boolean,
    ): RadioSyncOutcome {
        ensureRoot()
        if (countryDirName.trim() == FAVORITES_NAME) return RadioSyncOutcome(ok = true)

        val entry = lookupCountryByDir(countryDirName)
        if (entry == null) {
            val msg = "未知国家目录：$countryDirName（请返回 radios/ 根目录刷新一次）"
            writeCountryStatus(countryDirName, ok = false, note = msg)
            return RadioSyncOutcome(ok = false, message = msg)
        }

        val metaPath = "$RADIOS_DIR/${entry.dir}/.stations.meta.json"
        val meta = readMetaOrNull(metaPath)
        val expired = meta == null || isExpired(meta, stationsTtlMs)

        val hasAnyRadio =
            runCatching {
                ws.listDir("$RADIOS_DIR/${entry.dir}").any { it.type == AgentsDirEntryType.File && it.name.lowercase(Locale.ROOT).endsWith(".radio") }
            }.getOrDefault(false)

        if (!force && hasAnyRadio && !expired) {
            writeCountryStatus(entry.dir, ok = true, note = "使用缓存（未过期）")
            return RadioSyncOutcome(ok = true)
        }

        return try {
            val stations = api.listStationsByCountry(entry.name, limit = stationsLimit)
            val nowSec = nowSec()
            val indexEntries = ArrayList<JsonElement>(stations.size.coerceAtLeast(0))
            for (s in stations) {
                val uuid = s.stationUuid?.trim()?.ifBlank { null } ?: continue
                val name = s.name?.trim()?.ifBlank { null } ?: continue
                val url = (s.urlResolved ?: s.url)?.trim()?.ifBlank { null } ?: continue
                val file =
                    RadioStationFileV1(
                        schema = RadioStationFileV1.SCHEMA_V1,
                        id = "radio-browser:$uuid",
                        name = name,
                        streamUrl = url,
                        homepage = s.homepage?.trim()?.ifBlank { null },
                        faviconUrl = s.favicon?.trim()?.ifBlank { null },
                        country = s.country?.trim()?.ifBlank { null },
                        state = s.state?.trim()?.ifBlank { null },
                        language = s.language?.trim()?.ifBlank { null },
                        tags = parseTags(s.tags),
                        codec = s.codec?.trim()?.ifBlank { null },
                        bitrateKbps = s.bitrate?.takeIf { it > 0 },
                        votes = s.votes?.takeIf { it >= 0 },
                        source =
                            RadioStationFileV1.Source(
                                provider = "radio-browser",
                                url = "radio-browser",
                                fetchedAtSec = nowSec,
                            ),
                    )
                val fileName = RadioPathNaming.stationFileName(stationName = name, stationUuid = uuid)
                val targetPath = "$RADIOS_DIR/${entry.dir}/$fileName"
                ws.writeTextFile(targetPath, json.encodeToString(JsonObject.serializer(), file.toJsonObject()) + "\n")

                val tags = file.tags
                indexEntries.add(
                    buildJsonObject {
                        put("name", JsonPrimitive(file.name))
                        put("path", JsonPrimitive("workspace/radios/${entry.dir}/$fileName"))
                        put("tags", JsonArray(tags.map { JsonPrimitive(it) }))
                        file.language?.let { put("language", JsonPrimitive(it)) }
                        file.votes?.let { put("votes", JsonPrimitive(it)) }
                    },
                )
            }
            val stationsIndexObj =
                buildJsonObject {
                    put("schema", JsonPrimitive(STATIONS_INDEX_SCHEMA))
                    put("dir", JsonPrimitive(entry.dir))
                    put("generatedAtSec", JsonPrimitive(nowSec))
                    put("stations", JsonArray(indexEntries))
                }
            ws.writeTextFile(
                "$RADIOS_DIR/${entry.dir}/.stations.index.json",
                prettyJson.encodeToString(JsonObject.serializer(), stationsIndexObj) + "\n",
            )
            writeMeta(metaPath)
            writeCountryStatus(entry.dir, ok = true, note = if (force) "已刷新" else "已更新")
            RadioSyncOutcome(ok = true)
        } catch (t: Throwable) {
            val msg = "加载电台列表失败：${t.message ?: "unknown"}（可点刷新重试）"
            writeCountryStatus(entry.dir, ok = false, note = msg)
            RadioSyncOutcome(ok = false, message = msg)
        }
    }

    private fun encodeCountriesIndex(index: RadioCountriesIndex): String {
        val obj =
            buildJsonObject {
                put("schema", JsonPrimitive(COUNTRIES_INDEX_SCHEMA))
                put("generatedAtSec", JsonPrimitive(index.generatedAtSec))
                put(
                    "countries",
                    buildJsonArray {
                        for (c in index.countries) {
                            add(
                                buildJsonObject {
                                    put("dir", JsonPrimitive(c.dir))
                                    put("name", JsonPrimitive(c.name))
                                    c.code?.let { put("code", JsonPrimitive(it)) }
                                    c.stationCount?.let { put("stationCount", JsonPrimitive(it)) }
                                }
                            )
                        }
                    },
                )
            }
        return prettyJson.encodeToString(JsonObject.serializer(), obj)
    }

    private fun ensureCountryDirsExist(index: RadioCountriesIndex?) {
        if (index == null) return
        for (c in index.countries) {
            try {
                ws.mkdir("$RADIOS_DIR/${c.dir}")
            } catch (_: Throwable) {
            }
        }
    }

    private fun parseTags(tagsCsv: String?): List<String> {
        val raw = tagsCsv?.trim()?.ifBlank { null } ?: return emptyList()
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(20)
    }

    private fun nowSec(): Long = (nowMs() / 1000L).coerceAtLeast(0L)

    private fun isExpired(meta: RadioCacheMeta, ttlMs: Long): Boolean {
        val ageMs = nowMs() - (meta.fetchedAtSec.coerceAtLeast(0L) * 1000L)
        return ageMs > ttlMs.coerceAtLeast(0L)
    }

    private fun readMetaOrNull(path: String): RadioCacheMeta? {
        val raw =
            try {
                if (!ws.exists(path)) return null
                ws.readTextFile(path, maxBytes = 64 * 1024)
            } catch (_: Throwable) {
                return null
            }
        val obj: JsonObject =
            try {
                json.parseToJsonElement(raw).jsonObject
            } catch (_: Throwable) {
                return null
            }
        if (obj.str("schema") != META_SCHEMA) return null
        val fetched = obj.long("fetchedAtSec") ?: return null
        return RadioCacheMeta(fetchedAtSec = fetched)
    }

    private fun writeMeta(path: String) {
        val obj =
            buildJsonObject {
                put("schema", JsonPrimitive(META_SCHEMA))
                put("fetchedAtSec", JsonPrimitive(nowSec()))
            }
        ws.writeTextFile(path, prettyJson.encodeToString(JsonObject.serializer(), obj) + "\n")
    }

    private fun writeRootStatus(
        ok: Boolean,
        note: String,
    ) {
        val raw =
            buildString {
                appendLine("# radios 状态")
                appendLine()
                appendLine("- ok: ${if (ok) "true" else "false"}")
                appendLine("- at: ${nowSec()}")
                appendLine("- note: $note")
                appendLine()
                appendLine("提示：在 radios/ 下点“刷新”可强制重新拉取国家与电台列表。")
            }
        ws.writeTextFile("$RADIOS_DIR/_STATUS.md", raw)
    }

    private fun writeCountryStatus(
        dirName: String,
        ok: Boolean,
        note: String,
    ) {
        val raw =
            buildString {
                appendLine("# 电台目录状态")
                appendLine()
                appendLine("- dir: $dirName")
                appendLine("- ok: ${if (ok) "true" else "false"}")
                appendLine("- at: ${nowSec()}")
                appendLine("- note: $note")
                appendLine()
                appendLine("提示：点“刷新”可强制重试拉取该国家目录。")
            }
        ws.writeTextFile("$RADIOS_DIR/$dirName/_STATUS.md", raw)
    }

    private fun JsonObject.str(key: String): String? {
        val raw = runCatching { this[key]?.jsonPrimitive?.content }.getOrNull() ?: return null
        return raw.trim().ifBlank { null }
    }

    private fun JsonObject.int(key: String): Int? {
        val raw = runCatching { this[key]?.jsonPrimitive?.content }.getOrNull() ?: return null
        return raw.trim().toIntOrNull()
    }

    private fun JsonObject.long(key: String): Long? {
        val raw = runCatching { this[key]?.jsonPrimitive?.content }.getOrNull() ?: return null
        return raw.trim().toLongOrNull()
    }

    companion object {
        const val RADIOS_DIR = ".agents/workspace/radios"
        const val FAVORITES_NAME = "favorites"
        const val FAVORITES_DIR = "$RADIOS_DIR/$FAVORITES_NAME"

        private const val STATIONS_INDEX_SCHEMA = "kotlin-agent-app/radios-stations-index@v1"
        private const val COUNTRIES_META_PATH = "$RADIOS_DIR/.countries.meta.json"
        private const val COUNTRIES_INDEX_PATH = "$RADIOS_DIR/.countries.index.json"
        private const val COUNTRIES_INDEX_SCHEMA = "kotlin-agent-app/radios-countries-index@v1"
        private const val META_SCHEMA = "kotlin-agent-app/radios-cache-meta@v1"
    }
}
