package com.lsl.kotlin_agent_app.ui.instant_translation

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.content.Context
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsQueueMode
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsRuntimeProvider
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsSpeakRequest
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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

internal class AndroidInstantTranslationSpeaker(
    appContext: Context,
    private val archiveManager: InstantTranslationArchiveManager? = null,
) : InstantTranslationSpeaker {
    private val context = appContext.applicationContext
    private val runtime = TtsRuntimeProvider.get(context)
    private val exporter = AndroidInstantTranslationFileExporter(context)

    override suspend fun speak(turn: InstantTranslationTurn) {
        val trimmed = turn.translatedText.trim()
        if (trimmed.isBlank()) return
        archiveManager
            ?.prepareTtsOutputFile(turn)
            ?.let { outputFile ->
                runCatching {
                    exporter.synthesizeToFile(
                        text = trimmed,
                        languageCode = turn.targetLanguageCode,
                        outputFile = outputFile,
                    )
                }
            }
        speak(
            text = trimmed,
            languageCode = turn.targetLanguageCode,
        )
    }

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

private class AndroidInstantTranslationFileExporter(
    appContext: Context,
) {
    private val ctx = appContext.applicationContext
    private val initMutex = Mutex()
    @Volatile private var tts: TextToSpeech? = null
    private val completions = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    suspend fun synthesizeToFile(
        text: String,
        languageCode: String,
        outputFile: File,
    ) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val engine = ensureReady()
        val locale = Locale.forLanguageTag(languageCodeToLocaleTag(languageCode))
        val utteranceId = UUID.randomUUID().toString().replace("-", "")
        val completion = CompletableDeferred<Unit>()
        completions[utteranceId] = completion
        outputFile.parentFile?.mkdirs()
        withContext(Dispatchers.Main) {
            val langResult = engine.setLanguage(locale)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                completions.remove(utteranceId)
                error("TTS locale not supported: ${locale.toLanguageTag()}")
            }
            val result = engine.synthesizeToFile(trimmed, Bundle(), outputFile, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                completions.remove(utteranceId)
                error("TTS synthesizeToFile failed")
            }
        }
        try {
            withTimeout(20_000) {
                completion.await()
            }
        } finally {
            completions.remove(utteranceId)
        }
    }

    private suspend fun ensureReady(): TextToSpeech {
        tts?.let { return it }
        return initMutex.withLock {
            tts?.let { return@withLock it }
            val ready = CompletableDeferred<Int>()
            val engine =
                withContext(Dispatchers.Main) {
                    TextToSpeech(ctx) { status ->
                        ready.complete(status)
                    }
                }
            tts = engine
            val status = ready.await()
            if (status != TextToSpeech.SUCCESS) {
                tts = null
                error("TTS init failed: $status")
            }
            withContext(Dispatchers.Main) {
                engine.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            utteranceId?.let { completions.remove(it)?.complete(Unit) }
                        }

                        override fun onError(utteranceId: String?) {
                            utteranceId?.let { completions.remove(it)?.completeExceptionally(IllegalStateException("TTS file export failed")) }
                        }

                        override fun onError(
                            utteranceId: String?,
                            errorCode: Int,
                        ) {
                            utteranceId?.let {
                                completions.remove(it)?.completeExceptionally(
                                    IllegalStateException("TTS file export failed: $errorCode"),
                                )
                            }
                        }
                    },
                )
            }
            engine
        }
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
