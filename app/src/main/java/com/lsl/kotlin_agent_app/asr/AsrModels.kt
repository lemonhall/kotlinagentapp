package com.lsl.kotlin_agent_app.asr

internal data class AsrResult(
    val segments: List<AsrSegment>,
    val detectedLanguage: String?,
)

internal data class AsrSegment(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val emotion: String?,
)

