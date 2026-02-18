package com.lsl.kotlin_agent_app.agent.tools.stock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal object FinnhubClientTestHooks {
    @Volatile private var transportOverride: FinnhubTransport? = null

    fun install(transport: FinnhubTransport) {
        transportOverride = transport
    }

    fun clear() {
        transportOverride = null
    }

    internal fun getTransportOrNull(): FinnhubTransport? = transportOverride
}

internal data class FinnhubHttpResponse(
    val statusCode: Int,
    val bodyText: String,
    val headers: Map<String, String>,
)

internal interface FinnhubTransport {
    suspend fun get(
        url: HttpUrl,
        headers: Map<String, String>,
    ): FinnhubHttpResponse
}

internal class OkHttpFinnhubTransport(
    private val client: OkHttpClient,
) : FinnhubTransport {
    override suspend fun get(
        url: HttpUrl,
        headers: Map<String, String>,
    ): FinnhubHttpResponse {
        val req =
            Request.Builder()
                .url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .get()
                .build()
        return withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val hs =
                    resp.headers.names().associateWith { name ->
                        resp.headers[name].orEmpty()
                    }
                FinnhubHttpResponse(statusCode = resp.code, bodyText = body, headers = hs)
            }
        }
    }
}

internal class FinnhubHttpException(
    val statusCode: Int,
    message: String,
    val bodyText: String,
    val headers: Map<String, String>,
) : RuntimeException(message)

internal class FinnhubNetworkException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)

internal class FinnhubClient(
    private val okHttp: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun getJson(
        baseUrl: String,
        path: String,
        query: Map<String, String>,
        token: String,
    ): Pair<JsonElement, FinnhubHttpResponse> {
        val base = baseUrl.trim().toHttpUrl()
        val builder = base.newBuilder()
        val segs = path.trim().trimStart('/').trimEnd('/').split('/').filter { it.isNotBlank() }
        for (s in segs) builder.addPathSegment(s)
        for ((k, v) in query) builder.addQueryParameter(k, v)
        val url = builder.build()

        val headers = linkedMapOf("X-Finnhub-Token" to token)
        val transport = FinnhubClientTestHooks.getTransportOrNull() ?: OkHttpFinnhubTransport(okHttp)
        val resp =
            try {
                transport.get(url = url, headers = headers)
            } catch (t: Throwable) {
                throw FinnhubNetworkException("network error", t)
            }
        if (resp.statusCode == 429) {
            throw FinnhubHttpException(statusCode = 429, message = "rate limited", bodyText = resp.bodyText, headers = resp.headers)
        }
        if (resp.statusCode < 200 || resp.statusCode >= 300) {
            throw FinnhubHttpException(statusCode = resp.statusCode, message = "http ${resp.statusCode}", bodyText = resp.bodyText, headers = resp.headers)
        }
        val el =
            try {
                json.parseToJsonElement(resp.bodyText)
            } catch (t: Throwable) {
                throw FinnhubNetworkException("invalid json", t)
            }
        return el to resp
    }
}
