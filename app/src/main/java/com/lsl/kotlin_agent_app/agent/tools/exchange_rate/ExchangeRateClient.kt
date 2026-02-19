package com.lsl.kotlin_agent_app.agent.tools.exchange_rate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal object ExchangeRateClientTestHooks {
    @Volatile private var transportOverride: ExchangeRateTransport? = null

    fun install(transport: ExchangeRateTransport) {
        transportOverride = transport
    }

    fun clear() {
        transportOverride = null
    }

    internal fun getTransportOrNull(): ExchangeRateTransport? = transportOverride
}

internal data class ExchangeRateHttpResponse(
    val statusCode: Int,
    val bodyText: String,
    val headers: Map<String, String>,
)

internal interface ExchangeRateTransport {
    suspend fun get(url: HttpUrl): ExchangeRateHttpResponse
}

internal class OkHttpExchangeRateTransport(
    private val client: OkHttpClient,
) : ExchangeRateTransport {
    override suspend fun get(url: HttpUrl): ExchangeRateHttpResponse {
        val req =
            Request.Builder()
                .url(url)
                .get()
                .build()
        return withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val hs =
                    resp.headers.names().associateWith { name ->
                        resp.headers[name].orEmpty()
                    }
                ExchangeRateHttpResponse(statusCode = resp.code, bodyText = body, headers = hs)
            }
        }
    }
}

internal class ExchangeRateHttpException(
    val statusCode: Int,
    message: String,
    val bodyText: String,
) : RuntimeException(message)

internal class ExchangeRateNetworkException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)

internal class ExchangeRateRemoteErrorException(
    val errorType: String?,
    message: String,
) : RuntimeException(message)

internal class ExchangeRateClient(
    private val okHttp: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    private val baseLatestUrl: HttpUrl = "https://open.er-api.com/v6/latest/".toHttpUrl()

    suspend fun getLatest(baseCode: String): Pair<JsonObject, ExchangeRateHttpResponse> {
        val base = baseCode.trim().uppercase()
        require(base.matches(Regex("^[A-Z]{3}$"))) { "invalid base currency: $baseCode" }

        val url =
            baseLatestUrl
                .newBuilder()
                .addPathSegment(base)
                .build()

        val transport = ExchangeRateClientTestHooks.getTransportOrNull() ?: OkHttpExchangeRateTransport(okHttp)
        val resp =
            try {
                transport.get(url = url)
            } catch (t: Throwable) {
                throw ExchangeRateNetworkException("network error", t)
            }
        if (resp.statusCode < 200 || resp.statusCode >= 300) {
            throw ExchangeRateHttpException(statusCode = resp.statusCode, message = "http ${resp.statusCode}", bodyText = resp.bodyText)
        }

        val el: JsonElement =
            try {
                json.parseToJsonElement(resp.bodyText)
            } catch (t: Throwable) {
                throw ExchangeRateNetworkException("invalid json", t)
            }
        val obj =
            try {
                el.jsonObject
            } catch (t: Throwable) {
                throw ExchangeRateNetworkException("invalid json (expected object)", t)
            }

        val result = (obj["result"] as? JsonPrimitive)?.content?.trim()
        if (result != null && !result.equals("success", ignoreCase = true)) {
            val errorType = (obj["error-type"] as? JsonPrimitive)?.content?.trim()
            throw ExchangeRateRemoteErrorException(errorType = errorType, message = "remote error: ${errorType ?: "unknown"}")
        }

        return obj to resp
    }
}
