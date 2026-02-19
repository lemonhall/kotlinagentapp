package com.lsl.kotlin_agent_app.radios

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

internal enum class StreamResolutionClassification {
    Direct,
    Pls,
    M3u,
    Hls,
    Asx,
    Xspf,

    UnsupportedScheme,
    InvalidUrl,
    HttpError,
    RedirectLimitExceeded,
    ResponseTooLarge,
    NetworkError,
}

internal data class ResolvedStream(
    val finalUrl: String,
    val candidates: List<String>,
    val resolutionTrace: List<String>,
    val classification: StreamResolutionClassification,
)

internal interface StreamUrlResolverTransport {
    suspend fun get(url: HttpUrl): StreamUrlResolverHttpResponse
}

internal data class StreamUrlResolverHttpResponse(
    val statusCode: Int,
    val finalUrl: HttpUrl,
    val contentType: String?,
    val bodyText: String,
    val redirectCount: Int,
)

internal object StreamUrlResolverTestHooks {
    @Volatile private var transportOverride: StreamUrlResolverTransport? = null

    fun install(transport: StreamUrlResolverTransport) {
        transportOverride = transport
    }

    fun clear() {
        transportOverride = null
    }

    internal fun getTransportOrNull(): StreamUrlResolverTransport? = transportOverride
}

internal class OkHttpStreamUrlResolverTransport(
    private val client: OkHttpClient,
    private val maxBodyBytes: Long,
) : StreamUrlResolverTransport {
    override suspend fun get(url: HttpUrl): StreamUrlResolverHttpResponse {
        val req =
            Request.Builder()
                .url(url)
                .get()
                // Best-effort: try to avoid downloading whole streams when resolving playlists.
                .header("Range", "bytes=0-${(maxBodyBytes - 1).coerceAtLeast(0L)}")
                .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(req).execute().use { resp ->
                    val finalUrl = resp.request.url
                    val redirectCount = countPriorResponses(resp)
                    val ct = resp.header("Content-Type")?.trim()?.ifBlank { null }
                    val bodyBytes = readBodyWithLimit(resp.body?.byteStream(), maxBodyBytes)
                    val charset = resp.body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                    StreamUrlResolverHttpResponse(
                        statusCode = resp.code,
                        finalUrl = finalUrl,
                        contentType = ct,
                        bodyText = bodyBytes.toString(charset),
                        redirectCount = redirectCount,
                    )
                }
            } catch (t: StreamUrlResolverNetworkException) {
                throw t
            } catch (t: Throwable) {
                throw StreamUrlResolverNetworkException("network error", t)
            }
        }
    }

    private fun countPriorResponses(resp: okhttp3.Response): Int {
        var cur = resp.priorResponse
        var n = 0
        while (cur != null && n < 100) {
            n += 1
            cur = cur.priorResponse
        }
        return n
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
            if (total > cap) throw StreamUrlResolverNetworkException("response too large (>${cap} bytes)")
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

internal class StreamUrlResolverNetworkException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class StreamUrlResolver(
    private val transport: StreamUrlResolverTransport = defaultTransport(),
    private val maxRedirects: Int = 5,
) {
    suspend fun resolve(streamUrlRaw: String): ResolvedStream {
        val raw = streamUrlRaw.trim()
        if (raw.isEmpty()) {
            return ResolvedStream(
                finalUrl = raw,
                candidates = emptyList(),
                resolutionTrace = listOf("empty_url"),
                classification = StreamResolutionClassification.InvalidUrl,
            )
        }

        val url = raw.toHttpUrlOrNull()
        if (url == null) {
            return ResolvedStream(
                finalUrl = raw,
                candidates = emptyList(),
                resolutionTrace = listOf("invalid_url"),
                classification = StreamResolutionClassification.InvalidUrl,
            )
        }
        val scheme = url.scheme.lowercase()
        if (scheme != "http" && scheme != "https") {
            return ResolvedStream(
                finalUrl = raw,
                candidates = emptyList(),
                resolutionTrace = listOf("unsupported_scheme:$scheme"),
                classification = StreamResolutionClassification.UnsupportedScheme,
            )
        }

        if (!looksLikePlaylistUrl(url)) {
            return ResolvedStream(
                finalUrl = url.toString(),
                candidates = emptyList(),
                resolutionTrace = listOf("direct_no_fetch"),
                classification = StreamResolutionClassification.Direct,
            )
        }

        val effectiveTransport = StreamUrlResolverTestHooks.getTransportOrNull() ?: transport
        val resp =
            try {
                effectiveTransport.get(url)
            } catch (t: StreamUrlResolverNetworkException) {
                return ResolvedStream(
                    finalUrl = url.toString(),
                    candidates = emptyList(),
                    resolutionTrace = listOf("network_error:${t.message ?: "error"}"),
                    classification = StreamResolutionClassification.NetworkError,
                )
            } catch (t: Throwable) {
                return ResolvedStream(
                    finalUrl = url.toString(),
                    candidates = emptyList(),
                    resolutionTrace = listOf("network_error:${t.message ?: "error"}"),
                    classification = StreamResolutionClassification.NetworkError,
                )
            }

        val trace = mutableListOf<String>()
        trace.add("fetched:status=${resp.statusCode}")
        if (!resp.contentType.isNullOrBlank()) trace.add("content_type:${resp.contentType}")
        if (resp.redirectCount > 0) trace.add("redirects:${resp.redirectCount}")

        if (resp.redirectCount > maxRedirects) {
            trace.add("redirect_limit_exceeded:max=$maxRedirects")
            return ResolvedStream(
                finalUrl = resp.finalUrl.toString(),
                candidates = emptyList(),
                resolutionTrace = trace,
                classification = StreamResolutionClassification.RedirectLimitExceeded,
            )
        }

        if (resp.statusCode < 200 || resp.statusCode >= 300) {
            trace.add("http_error:${resp.statusCode}")
            return ResolvedStream(
                finalUrl = resp.finalUrl.toString(),
                candidates = emptyList(),
                resolutionTrace = trace,
                classification = StreamResolutionClassification.HttpError,
            )
        }

        val byType = classifyByContentTypeOrUrl(resp.finalUrl, resp.contentType)
        return when (byType) {
            StreamResolutionClassification.Pls -> {
                val candidates = parsePlsCandidates(resp.bodyText, resp.finalUrl)
                trace.add("pls_candidates:${candidates.size}")
                ResolvedStream(
                    finalUrl = resp.finalUrl.toString(),
                    candidates = candidates,
                    resolutionTrace = trace,
                    classification = StreamResolutionClassification.Pls,
                )
            }

            StreamResolutionClassification.M3u,
            StreamResolutionClassification.Hls,
            -> {
                val parsed = parseM3uCandidates(resp.bodyText, resp.finalUrl)
                val cls = if (parsed.isHls) StreamResolutionClassification.Hls else StreamResolutionClassification.M3u
                trace.add(if (parsed.isHls) "m3u:hls=true" else "m3u:hls=false")
                trace.add("m3u_candidates:${parsed.candidates.size}")
                ResolvedStream(
                    finalUrl = resp.finalUrl.toString(),
                    candidates = parsed.candidates,
                    resolutionTrace = trace,
                    classification = cls,
                )
            }

            StreamResolutionClassification.Asx -> {
                val candidates = parseAsxCandidates(resp.bodyText, resp.finalUrl)
                trace.add("asx_candidates:${candidates.size}")
                ResolvedStream(
                    finalUrl = resp.finalUrl.toString(),
                    candidates = candidates,
                    resolutionTrace = trace,
                    classification = StreamResolutionClassification.Asx,
                )
            }

            StreamResolutionClassification.Xspf -> {
                val candidates = parseXspfCandidates(resp.bodyText, resp.finalUrl)
                trace.add("xspf_candidates:${candidates.size}")
                ResolvedStream(
                    finalUrl = resp.finalUrl.toString(),
                    candidates = candidates,
                    resolutionTrace = trace,
                    classification = StreamResolutionClassification.Xspf,
                )
            }

            else -> {
                ResolvedStream(
                    finalUrl = resp.finalUrl.toString(),
                    candidates = emptyList(),
                    resolutionTrace = trace + "direct_fallback",
                    classification = StreamResolutionClassification.Direct,
                )
            }
        }
    }

    internal data class ParsedM3u(
        val isHls: Boolean,
        val candidates: List<String>,
    )

    internal companion object {
        private const val DEFAULT_MAX_BODY_BYTES: Long = 256L * 1024L

        private fun defaultTransport(): StreamUrlResolverTransport {
            val okHttp =
                OkHttpClient.Builder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .callTimeout(4, TimeUnit.SECONDS)
                    .connectTimeout(4, TimeUnit.SECONDS)
                    .readTimeout(4, TimeUnit.SECONDS)
                    .build()
            return OkHttpStreamUrlResolverTransport(
                client = okHttp,
                maxBodyBytes = DEFAULT_MAX_BODY_BYTES,
            )
        }

        private fun looksLikePlaylistUrl(url: HttpUrl): Boolean {
            val seg = url.pathSegments.lastOrNull()?.lowercase().orEmpty()
            return seg.endsWith(".pls") ||
                seg.endsWith(".m3u") ||
                seg.endsWith(".m3u8") ||
                seg.endsWith(".asx") ||
                seg.endsWith(".xspf")
        }

        private fun classifyByContentTypeOrUrl(
            url: HttpUrl,
            contentType: String?,
        ): StreamResolutionClassification {
            val ct = contentType?.lowercase()?.substringBefore(';')?.trim().orEmpty()
            val seg = url.pathSegments.lastOrNull()?.lowercase().orEmpty()

            if (seg.endsWith(".pls")) return StreamResolutionClassification.Pls
            if (seg.endsWith(".asx")) return StreamResolutionClassification.Asx
            if (seg.endsWith(".xspf")) return StreamResolutionClassification.Xspf
            if (seg.endsWith(".m3u8")) return StreamResolutionClassification.Hls
            if (seg.endsWith(".m3u")) return StreamResolutionClassification.M3u

            // Best-effort: content types occasionally lie/omit; keep this conservative.
            return when (ct) {
                "audio/x-scpls", "application/pls+xml", "application/x-scpls" -> StreamResolutionClassification.Pls
                "application/xspf+xml" -> StreamResolutionClassification.Xspf
                "video/x-ms-asf", "application/vnd.ms-asf" -> StreamResolutionClassification.Asx
                "application/vnd.apple.mpegurl", "application/x-mpegurl" -> StreamResolutionClassification.Hls
                "audio/mpegurl", "audio/x-mpegurl" -> StreamResolutionClassification.M3u
                else -> StreamResolutionClassification.Direct
            }
        }

        internal fun parsePlsCandidates(
            body: String,
            baseUrl: HttpUrl,
        ): List<String> {
            val out = ArrayList<String>()
            val seen = LinkedHashSet<String>()

            val lines = body.split('\n')
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val idx = line.indexOf('=')
                if (idx <= 0) continue

                val key = line.substring(0, idx).trim().lowercase()
                if (!key.startsWith("file")) continue

                val value = line.substring(idx + 1).trim().trim('"').trim()
                val resolved = resolveHttpUrlOrNull(value, baseUrl) ?: continue
                val s = resolved.toString()
                if (seen.add(s)) out.add(s)
                if (out.size >= 10) break
            }

            return out
        }

        internal fun parseM3uCandidates(
            body: String,
            baseUrl: HttpUrl,
        ): ParsedM3u {
            val trimmed = body.trimStart()
            val isHls =
                trimmed.startsWith("#EXTM3U", ignoreCase = true) &&
                    trimmed.contains("#EXT-X-", ignoreCase = true)
            if (isHls) {
                // HLS playlist: let Media3 handle it (after adding media3-exoplayer-hls).
                return ParsedM3u(isHls = true, candidates = emptyList())
            }

            val out = ArrayList<String>()
            val seen = LinkedHashSet<String>()
            val lines = body.split('\n')
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#")) continue
                val resolved = resolveHttpUrlOrNull(line, baseUrl) ?: continue
                val s = resolved.toString()
                if (seen.add(s)) out.add(s)
                if (out.size >= 10) break
            }
            return ParsedM3u(isHls = false, candidates = out)
        }

        internal fun parseAsxCandidates(
            body: String,
            baseUrl: HttpUrl,
        ): List<String> {
            val out = ArrayList<String>()
            val seen = LinkedHashSet<String>()

            val regex =
                Regex("(?i)href\\s*=\\s*\\\"([^\\\"]+)\\\"")
            for (m in regex.findAll(body)) {
                val raw = m.groupValues.getOrNull(1)?.trim().orEmpty()
                val resolved = resolveHttpUrlOrNull(raw, baseUrl) ?: continue
                val s = resolved.toString()
                if (seen.add(s)) out.add(s)
                if (out.size >= 10) break
            }

            return out
        }

        internal fun parseXspfCandidates(
            body: String,
            baseUrl: HttpUrl,
        ): List<String> {
            val out = ArrayList<String>()
            val seen = LinkedHashSet<String>()

            val regex =
                Regex("(?i)<location>\\s*([^<]+)\\s*</location>")
            for (m in regex.findAll(body)) {
                val raw = m.groupValues.getOrNull(1)?.trim().orEmpty()
                val resolved = resolveHttpUrlOrNull(raw, baseUrl) ?: continue
                val s = resolved.toString()
                if (seen.add(s)) out.add(s)
                if (out.size >= 10) break
            }

            return out
        }

        private fun resolveHttpUrlOrNull(
            raw: String,
            baseUrl: HttpUrl,
        ): HttpUrl? {
            val s = raw.trim().trim('"').trim()
            if (s.isEmpty()) return null
            val direct = s.toHttpUrlOrNull()
            if (direct != null) {
                if (direct.scheme.lowercase() !in setOf("http", "https")) return null
                return direct
            }
            val rel = baseUrl.resolve(s) ?: return null
            if (rel.scheme.lowercase() !in setOf("http", "https")) return null
            return rel
        }
    }
}
