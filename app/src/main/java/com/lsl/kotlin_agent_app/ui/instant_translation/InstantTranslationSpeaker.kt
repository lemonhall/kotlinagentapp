package com.lsl.kotlin_agent_app.ui.instant_translation

interface InstantTranslationSpeaker {
    suspend fun speak(
        text: String,
        languageCode: String,
    )

    suspend fun speak(turn: InstantTranslationTurn) {
        speak(
            text = turn.translatedText,
            languageCode = turn.targetLanguageCode,
        )
    }
}

internal interface InstantTranslationPlaybackHooks {
    fun beforePlayback() = Unit

    fun afterPlayback() = Unit

    companion object {
        val None: InstantTranslationPlaybackHooks = object : InstantTranslationPlaybackHooks {}
    }
}
