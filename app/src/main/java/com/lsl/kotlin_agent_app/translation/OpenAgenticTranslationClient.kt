package com.lsl.kotlin_agent_app.translation

import com.lsl.kotlin_agent_app.radio_transcript.TranscriptSegment
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.providers.OpenAIResponsesHttpProvider
import me.lemonhall.openagentic.sdk.providers.ProviderHttpException
import me.lemonhall.openagentic.sdk.providers.ProviderInvalidResponseException
import me.lemonhall.openagentic.sdk.providers.ProviderRateLimitException
import me.lemonhall.openagentic.sdk.providers.ProviderTimeoutException
import me.lemonhall.openagentic.sdk.providers.ResponsesRequest
import me.lemonhall.openagentic.sdk.runtime.ProviderStreamEvent

internal class OpenAgenticTranslationClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val maxBatchChars: Int = 8_000,
) : TranslationClient {
    private val provider =
        OpenAIResponsesHttpProvider(
            baseUrl = baseUrl.trim().ifBlank { "https://api.openai.com/v1" },
            defaultStore = false,
        )

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override suspend fun translateBatch(
        segments: List<TranscriptSegment>,
        context: List<TranslatedSegment>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<TranslatedSegment> {
        if (segments.isEmpty()) return emptyList()
        val src = sourceLanguage.trim().ifBlank { "auto" }
        val tgt = targetLanguage.trim().ifBlank { throw IllegalArgumentException("targetLanguage is blank") }
        if (src.equals(tgt, ignoreCase = true)) throw IllegalArgumentException("source and target language must differ")

        val batches = TranslationBatchSplitter.splitByApproxChars(segments, maxChars = maxBatchChars)
        val out = ArrayList<TranslatedSegment>(segments.size)
        var ctx = context
        for (b in batches) {
            val translated = translateOneBatch(segments = b, context = ctx, sourceLanguage = src, targetLanguage = tgt)
            out.addAll(translated)
            ctx = (ctx + translated).takeLast(24)
        }
        return out
    }

    private suspend fun translateOneBatch(
        segments: List<TranscriptSegment>,
        context: List<TranslatedSegment>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<TranslatedSegment> {
        val input = TranslationPromptBuilder.buildResponsesInput(segments, context, sourceLanguage, targetLanguage)
        val req =
            ResponsesRequest(
                model = model.trim().ifBlank { throw IllegalArgumentException("model is blank") },
                input = input,
                apiKey = apiKey.trim().ifBlank { throw IllegalArgumentException("apiKey is blank") },
                tools = emptyList(),
                store = false,
            )

        val completed =
            try {
                var c: me.lemonhall.openagentic.sdk.providers.ModelOutput? = null
                provider.stream(req).collect { ev ->
                    when (ev) {
                        is ProviderStreamEvent.TextDelta -> Unit
                        is ProviderStreamEvent.Completed -> c = ev.output
                        is ProviderStreamEvent.Failed -> throw ProviderInvalidResponseException("provider stream failed: ${ev.message}", raw = ev.raw?.toString())
                    }
                }
                c ?: throw ProviderInvalidResponseException("provider stream ended without Completed event")
            } catch (t: ProviderTimeoutException) {
                throw LlmNetworkError(t.message ?: "llm timeout", t)
            } catch (t: ProviderRateLimitException) {
                throw LlmRemoteError(t.message ?: "llm rate limited", t)
            } catch (t: ProviderHttpException) {
                throw LlmRemoteError(t.message ?: "llm http error", t)
            } catch (t: ProviderInvalidResponseException) {
                throw LlmParseError(t.message ?: "llm invalid response", t)
            } catch (t: Throwable) {
                throw LlmNetworkError(t.message ?: "llm request failed", t)
            }

        val text = completed.assistantText?.trim().orEmpty()
        if (text.isBlank()) throw LlmParseError("llm returned empty assistantText")
        val arr = parseJsonArrayFromText(text)
        val byId = LinkedHashMap<Int, String>()
        for (item in arr) {
            val o = item as? JsonObject ?: continue
            val id = runCatching { o["id"]?.jsonPrimitive?.content?.trim()?.toInt() }.getOrNull() ?: continue
            val tt = runCatching { o["translatedText"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.ifBlank { null } ?: continue
            byId[id] = tt
        }
        if (byId.isEmpty()) throw LlmParseError("llm returned no translated items")

        val out = ArrayList<TranslatedSegment>(segments.size)
        for (s in segments) {
            val tt = byId[s.id] ?: throw LlmParseError("missing translatedText for id=${s.id}")
            out.add(
                TranslatedSegment(
                    id = s.id,
                    startMs = s.startMs,
                    endMs = s.endMs,
                    sourceText = s.text,
                    translatedText = tt,
                    emotion = s.emotion,
                ),
            )
        }
        return out
    }

    private fun parseJsonArrayFromText(text: String): JsonArray {
        var t = text.trim()
        if (t.startsWith("```")) {
            val fenceEnd = t.indexOf('\n')
            val lastFence = t.lastIndexOf("```")
            if (fenceEnd > 0 && lastFence > fenceEnd) {
                t = t.substring(fenceEnd + 1, lastFence).trim()
            }
        }
        fun tryParse(s: String): JsonArray? {
            return try {
                val el = json.parseToJsonElement(s)
                when (el) {
                    is JsonArray -> el
                    else -> null
                }
            } catch (_: Throwable) {
                null
            }
        }

        tryParse(t)?.let { return it }

        val start = t.indexOf('[')
        val end = t.lastIndexOf(']')
        if (start >= 0 && end > start) {
            val sub = t.substring(start, end + 1)
            tryParse(sub)?.let { return it }
        }

        throw LlmParseError("assistantText is not a JSON array")
    }
}
