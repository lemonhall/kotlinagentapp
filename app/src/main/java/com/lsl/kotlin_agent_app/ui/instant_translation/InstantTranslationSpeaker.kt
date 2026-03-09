package com.lsl.kotlin_agent_app.ui.instant_translation

import android.content.Context
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsQueueMode
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsRuntimeProvider
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsSpeakRequest

interface InstantTranslationSpeaker {
    suspend fun speak(
        text: String,
        languageCode: String,
    )
}

internal class AndroidInstantTranslationSpeaker(
    appContext: Context,
) : InstantTranslationSpeaker {
    private val runtime = TtsRuntimeProvider.get(appContext.applicationContext)

    override suspend fun speak(
        text: String,
        languageCode: String,
    ) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        runtime.speak(
            req =
                TtsSpeakRequest(
                    text = trimmed,
                    localeTag = languageCodeToLocaleTag(languageCode),
                    queueMode = TtsQueueMode.Flush,
                ),
            await = false,
            timeoutMs = null,
        )
    }
}

internal fun languageCodeToLocaleTag(languageCode: String): String {
    return when (languageCode.trim().lowercase()) {
        "zh" -> "zh-CN"
        "ja" -> "ja-JP"
        "ko" -> "ko-KR"
        "en" -> "en-US"
        "fr" -> "fr-FR"
        "de" -> "de-DE"
        "es" -> "es-ES"
        "ru" -> "ru-RU"
        "it" -> "it-IT"
        "ar" -> "ar-SA"
        "pt" -> "pt-PT"
        else -> languageCode.trim().ifBlank { "en-US" }
    }
}
