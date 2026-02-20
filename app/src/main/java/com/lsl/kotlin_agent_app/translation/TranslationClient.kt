package com.lsl.kotlin_agent_app.translation

import com.lsl.kotlin_agent_app.radio_transcript.TranscriptSegment

internal interface TranslationClient {
    suspend fun translateBatch(
        segments: List<TranscriptSegment>,
        context: List<TranslatedSegment>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<TranslatedSegment>
}

