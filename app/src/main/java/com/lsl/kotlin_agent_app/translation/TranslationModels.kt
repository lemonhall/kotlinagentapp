package com.lsl.kotlin_agent_app.translation

internal data class TranslatedSegment(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val sourceText: String,
    val translatedText: String,
    val emotion: String? = null,
)

