package com.lsl.kotlin_agent_app.ui.instant_translation

import com.lsl.kotlin_agent_app.config.LlmConfigRepository
import com.lsl.kotlin_agent_app.radio_transcript.TranscriptSegment
import com.lsl.kotlin_agent_app.translation.OpenAgenticTranslationClient
import com.lsl.kotlin_agent_app.translation.TranslationProviderFactory

interface InstantTranslator {
    suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): String
}

internal class OpenAgenticInstantTranslator(
    private val llmConfigRepository: LlmConfigRepository,
) : InstantTranslator {
    override suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): String {
        val sourceText = text.trim().ifBlank { throw IllegalArgumentException("text is blank") }
        val activeProvider =
            llmConfigRepository.get().activeProvider
                ?: error("\u8bf7\u5148\u5728 Settings \u4e2d\u914d\u7f6e\u5e76\u9009\u4e2d\u4e00\u4e2a LLM provider")

        val client =
            OpenAgenticTranslationClient(
                baseUrl = activeProvider.baseUrl,
                apiKey = activeProvider.apiKey,
                model = activeProvider.selectedModel,
                provider = TranslationProviderFactory.create(activeProvider),
            )

        val result =
            client.translateBatch(
                segments = listOf(TranscriptSegment(id = 0, startMs = 0L, endMs = 0L, text = sourceText)),
                context = emptyList(),
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
            )

        return result.firstOrNull()?.translatedText?.trim()?.ifBlank { null }
            ?: error("\u7ffb\u8bd1\u7ed3\u679c\u4e3a\u7a7a")
    }
}