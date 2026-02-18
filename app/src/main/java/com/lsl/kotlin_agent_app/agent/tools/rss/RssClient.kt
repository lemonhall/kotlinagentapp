package com.lsl.kotlin_agent_app.agent.tools.rss

import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal object RssClientTestHooks {
    @Volatile private var transportOverride: RssTransport? = null

    fun install(transport: RssTransport) {
        transportOverride = transport
    }

    fun clear() {
        transportOverride = null
    }

    internal fun getTransportOrNull(): RssTransport? = transportOverride
}

internal data class RssHttpResponse(
    val statusCode: Int,
    val bodyText: String,
    val headers: Map<String, String>,
)

internal interface RssTransport {
    suspend fun get(
        url: HttpUrl,
        headers: Map<String, String>,
    ): RssHttpResponse
}

internal class RssNetworkException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class OkHttpRssTransport(
    private val client: OkHttpClient,
    private val maxBodyBytes: Long = 2L * 1024L * 1024L,
) : RssTransport {
    override suspend fun get(
        url: HttpUrl,
        headers: Map<String, String>,
    ): RssHttpResponse {
        val req =
            Request.Builder()
                .url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .get()
                .build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(req).execute().use { resp ->
                    val hs =
                        resp.headers.names().associateWith { name ->
                            resp.headers[name].orEmpty()
                        }

                    val bodyBytes =
                        try {
                            readBodyWithLimit(input = resp.body?.byteStream(), maxBytes = maxBodyBytes)
                        } catch (t: Throwable) {
                            throw RssNetworkException("failed to read body", t)
                        }

                    val charset = resp.body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                    val bodyText = bodyBytes.toString(charset)
                    RssHttpResponse(statusCode = resp.code, bodyText = bodyText, headers = hs)
                }
            } catch (t: RssNetworkException) {
                throw t
            } catch (t: Throwable) {
                throw RssNetworkException("network error", t)
            }
        }
    }

    private fun readBodyWithLimit(
        input: InputStream?,
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
            if (total > cap) {
                throw RssNetworkException("response too large (>${cap} bytes)")
            }
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

internal class RssClient(
    okHttp: OkHttpClient = defaultOkHttp(),
) {
    private val okHttp: OkHttpClient = okHttp

    suspend fun get(
        url: HttpUrl,
        etag: String?,
        lastModified: String?,
    ): RssHttpResponse {
        val headers = linkedMapOf<String, String>()
        if (!etag.isNullOrBlank()) headers["If-None-Match"] = etag
        if (!lastModified.isNullOrBlank()) headers["If-Modified-Since"] = lastModified
        val transport = RssClientTestHooks.getTransportOrNull() ?: OkHttpRssTransport(okHttp)
        return transport.get(url = url, headers = headers)
    }

    companion object {
        private fun defaultOkHttp(): OkHttpClient {
            return OkHttpClient.Builder()
                .callTimeout(15, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}

internal fun parseRetryAfterMsOrNull(headers: Map<String, String>): Long? {
    val v =
        headers["Retry-After"]
            ?: headers["retry-after"]
            ?: headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value
    val seconds = v?.trim()?.toLongOrNull() ?: return null
    return (seconds * 1000L).coerceAtLeast(0L)
}

