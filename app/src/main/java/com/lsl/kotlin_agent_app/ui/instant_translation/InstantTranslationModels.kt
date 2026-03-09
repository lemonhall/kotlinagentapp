package com.lsl.kotlin_agent_app.ui.instant_translation

data class InstantTranslationTurn(
    val id: Long,
    val sourceText: String,
    val translatedText: String = "",
    val isPending: Boolean = true,
)

data class InstantTranslationUiState(
    val targetLanguageCode: String = "en",
    val targetLanguageLabel: String = "\u82f1\u8bed",
    val listeningPreview: String = "",
    val turns: List<InstantTranslationTurn> = emptyList(),
    val errorMessage: String? = null,
)