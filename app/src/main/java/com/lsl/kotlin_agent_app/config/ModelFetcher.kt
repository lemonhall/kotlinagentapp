package com.lsl.kotlin_agent_app.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object ModelFetcher {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun supportsModelFetching(type: ProviderType): Boolean = when (type) {
        ProviderType.OPENAI_RESPONSES,
        ProviderType.OPENAI_CHATCOMPLETIONS -> true
        ProviderType.ANTHROPIC_MESSAGES -> false
    }

    suspend fun fetchModels(baseUrl: String, apiKey: String): List<String> {
        val url = baseUrl.trimEnd('/') + "/models"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val response = client.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}: ${resp.message}")
                val body = resp.body?.string() ?: error("Empty response")
                val root = json.parseToJsonElement(body).jsonObject
                val data = root["data"]?.jsonArray ?: return@withContext emptyList()
                data.mapNotNull { elem ->
                    (elem as? JsonObject)?.get("id")?.jsonPrimitive?.content
                }.sorted()
            }
        }
    }
}
