package com.lsl.kotlin_agent_app.radios

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal object RadioBrowserClientTestHooks {
    @Volatile private var transportOverride: RadioBrowserTransport? = null

    fun install(transport: RadioBrowserTransport) {
        transportOverride = transport
    }

    fun clear() {
        transportOverride = null
    }

    internal fun getTransportOrNull(): RadioBrowserTransport? = transportOverride
}

internal data class RadioBrowserHttpResponse(
    val statusCode: Int,
    val bodyText: String,
    val headers: Map<String, String>,
)

internal interface RadioBrowserTransport {
    suspend fun get(url: HttpUrl): RadioBrowserHttpResponse
}

internal class RadioBrowserNetworkException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class OkHttpRadioBrowserTransport(
    private val client: OkHttpClient,
    private val maxBodyBytes: Long = 8L * 1024L * 1024L,
) : RadioBrowserTransport {
    override suspend fun get(url: HttpUrl): RadioBrowserHttpResponse {
        val req = Request.Builder().url(url).get().build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(req).execute().use { resp ->
                    val hs =
                        resp.headers.names().associateWith { name ->
                            resp.headers[name].orEmpty()
                        }
                    val bodyBytes =
                        try {
                            readBodyWithLimit(resp.body?.byteStream(), maxBodyBytes)
                        } catch (t: Throwable) {
                            throw RadioBrowserNetworkException("failed to read body", t)
                        }
                    val charset = resp.body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                    RadioBrowserHttpResponse(statusCode = resp.code, bodyText = bodyBytes.toString(charset), headers = hs)
                }
            } catch (t: RadioBrowserNetworkException) {
                throw t
            } catch (t: Throwable) {
                throw RadioBrowserNetworkException("network error", t)
            }
        }
    }

    private fun readBodyWithLimit(
        input: java.io.InputStream?,
        maxBytes: Long,
    ): ByteArray {
        if (input == null) return ByteArray(0)
        val cap = maxBytes.coerceAtLeast(0L)
        if (cap == 0L) return ByteArray(0)

        val buf = ByteArray(8 * 1024)
        var total = 0L
        val out = ArrayList<ByteArray>()

        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n.toLong()
            if (total > cap) throw RadioBrowserNetworkException("response too large (>${cap} bytes)")
            out.add(buf.copyOf(n))
        }

        val all = ByteArray(total.toInt())
        var off = 0
        for (chunk in out) {
            System.arraycopy(chunk, 0, all, off, chunk.size)
            off += chunk.size
        }
        return all
    }
}

internal data class RadioBrowserCountry(
    val name: String? = null,
    val stationCount: Int? = null,
    val iso3166_1: String? = null,
)

internal data class RadioBrowserStation(
    val stationUuid: String? = null,
    val name: String? = null,
    val url: String? = null,
    val urlResolved: String? = null,
    val homepage: String? = null,
    val favicon: String? = null,
    val country: String? = null,
    val state: String? = null,
    val language: String? = null,
    val tags: String? = null,
    val codec: String? = null,
    val bitrate: Int? = null,
    val votes: Int? = null,
)

internal interface RadioBrowserApi {
    suspend fun listCountries(): List<RadioBrowserCountry>

    suspend fun listStationsByCountry(
        countryName: String,
        limit: Int,
    ): List<RadioBrowserStation>
}

internal class RadioBrowserClient(
    private val baseUrl: String = "https://de1.api.radio-browser.info",
    private val okHttp: OkHttpClient = defaultOkHttp(),
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : RadioBrowserApi {
    override suspend fun listCountries(): List<RadioBrowserCountry> {
        val url =
            HttpUrl.Builder()
                .scheme("https")
                .host(baseHost())
                .addPathSegment("json")
                .addPathSegment("countries")
                .build()

        val resp = get(url)
        if (resp.statusCode < 200 || resp.statusCode >= 300) {
            throw RadioBrowserNetworkException("http ${resp.statusCode}")
        }

        val arr = parseJsonArray(resp.bodyText)
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o.str("name")
            if (name.isNullOrBlank()) return@mapNotNull null
            RadioBrowserCountry(
                name = name,
                stationCount = o.int("stationcount"),
                iso3166_1 = o.str("iso_3166_1"),
            )
        }
    }

    override suspend fun listStationsByCountry(
        countryName: String,
        limit: Int,
    ): List<RadioBrowserStation> {
        val url =
            HttpUrl.Builder()
                .scheme("https")
                .host(baseHost())
                .addPathSegment("json")
                .addPathSegment("stations")
                .addPathSegment("bycountry")
                .addPathSegment(countryName)
                .addQueryParameter("hidebroken", "true")
                .addQueryParameter("order", "votes")
                .addQueryParameter("reverse", "true")
                .addQueryParameter("limit", limit.coerceIn(1, 500).toString())
                .build()

        val resp = get(url)
        if (resp.statusCode < 200 || resp.statusCode >= 300) {
            throw RadioBrowserNetworkException("http ${resp.statusCode}")
        }

        val arr = parseJsonArray(resp.bodyText)
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            RadioBrowserStation(
                stationUuid = o.str("stationuuid"),
                name = o.str("name"),
                url = o.str("url"),
                urlResolved = o.str("url_resolved"),
                homepage = o.str("homepage"),
                favicon = o.str("favicon"),
                country = o.str("country"),
                state = o.str("state"),
                language = o.str("language"),
                tags = o.str("tags"),
                codec = o.str("codec"),
                bitrate = o.int("bitrate"),
                votes = o.int("votes"),
            )
        }
    }

    private suspend fun get(url: HttpUrl): RadioBrowserHttpResponse {
        val transport = RadioBrowserClientTestHooks.getTransportOrNull() ?: OkHttpRadioBrowserTransport(okHttp)
        return transport.get(url)
    }

    private fun parseJsonArray(raw: String): JsonArray {
        val el: JsonElement =
            try {
                json.parseToJsonElement(raw)
            } catch (t: Throwable) {
                throw RadioBrowserNetworkException("invalid json", t)
            }
        return try {
            el.jsonArray
        } catch (t: Throwable) {
            throw RadioBrowserNetworkException("invalid json (expected array)", t)
        }
    }

    private fun JsonObject.str(key: String): String? {
        val raw = runCatching { this[key]?.jsonPrimitive?.content }.getOrNull() ?: return null
        return raw.trim().ifBlank { null }
    }

    private fun JsonObject.int(key: String): Int? {
        val raw = runCatching { this[key]?.jsonPrimitive?.content }.getOrNull() ?: return null
        return raw.trim().toIntOrNull()
    }

    private fun baseHost(): String {
        val h =
            baseUrl.trim()
                .removePrefix("https://")
                .removePrefix("http://")
                .trim()
                .trimEnd('/')
        if (h.isBlank()) return "de1.api.radio-browser.info"
        return h.substringBefore('/')
    }

    companion object {
        private fun defaultOkHttp(): OkHttpClient {
            return OkHttpClient.Builder()
                .callTimeout(20, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }
}
