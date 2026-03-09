package com.lsl.kotlin_agent_app.ui.simultaneous_interpretation

internal data class SimultaneousInterpretationSegment(
    val id: Long,
    val sourceText: String,
    val translatedText: String,
)

internal data class SimultaneousInterpretationUiState(
    val isConnecting: Boolean = false,
    val isRunning: Boolean = false,
    val isHeadsetConnected: Boolean = true,
    val targetLanguageCode: String = "en",
    val targetLanguageLabel: String = "英语",
    val statusText: String = "未开始",
    val sessionPath: String? = null,
    val sourcePreview: String = "",
    val translatedPreview: String = "",
    val errorMessage: String? = null,
    val segments: List<SimultaneousInterpretationSegment> = emptyList(),
)