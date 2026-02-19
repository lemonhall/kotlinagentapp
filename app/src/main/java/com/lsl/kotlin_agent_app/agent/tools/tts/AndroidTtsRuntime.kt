package com.lsl.kotlin_agent_app.agent.tools.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AndroidTtsRuntime(
    appContext: Context,
) : TtsRuntime {
    private val ctx = appContext.applicationContext

    private val initMutex = Mutex()
    @Volatile private var tts: TextToSpeech? = null

    private val utteranceStates = ConcurrentHashMap<String, CompletableDeferred<TtsSpeakCompletion>>()
    private val focusTokens = ConcurrentHashMap<String, AudioFocusToken>()

    override suspend fun listVoices(): List<TtsVoiceSummary> {
        val engine = ensureReady()
        val voices = engine.voices?.toList().orEmpty()
        return voices
            .map { v ->
                TtsVoiceSummary(
                    name = v.name,
                    localeTag = v.locale?.toLanguageTag().orEmpty(),
                    quality = v.quality,
                    latency = v.latency,
                    requiresNetwork = v.isNetworkConnectionRequired,
                )
            }
            .sortedWith(compareBy({ it.localeTag }, { it.name }))
    }

    override suspend fun speak(
        req: TtsSpeakRequest,
        await: Boolean,
        timeoutMs: Long?,
    ): TtsSpeakResponse {
        val engine = ensureReady()

        val utteranceId = UUID.randomUUID().toString().replace("-", "")
        val completion = CompletableDeferred<TtsSpeakCompletion>()
        utteranceStates[utteranceId] = completion
        try {
            val token = requestAudioFocus()
            focusTokens[utteranceId] = token
        } catch (t: Throwable) {
            utteranceStates.remove(utteranceId)
            throw t
        }

        val locale = req.localeTag?.trim()?.takeIf { it.isNotBlank() }?.let { Locale.forLanguageTag(it) }
        withContext(Dispatchers.Main) {
            if (locale != null) {
                val r = engine.setLanguage(locale)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    throw TtsNotSupported("TTS locale not supported: ${req.localeTag}")
                }
            }
            req.rate?.let { engine.setSpeechRate(it) }
            req.pitch?.let { engine.setPitch(it) }
        }

        val queueMode =
            when (req.queueMode) {
                TtsQueueMode.Flush -> TextToSpeech.QUEUE_FLUSH
                TtsQueueMode.Add -> TextToSpeech.QUEUE_ADD
            }

        val speakResult =
            withContext(Dispatchers.Main) {
                val params = Bundle()
                engine.speak(req.text, queueMode, params, utteranceId)
            }

        if (speakResult != TextToSpeech.SUCCESS) {
            abandonAudioFocus(utteranceId)
            utteranceStates.remove(utteranceId)
            throw TtsNotSupported("TTS speak failed (engine returned $speakResult).")
        }

        if (!await) return TtsSpeakResponse(utteranceId = utteranceId, completion = TtsSpeakCompletion.Started)

        val ms = (timeoutMs ?: 20_000L).coerceAtLeast(1L)
        val done =
            try {
                withTimeout(ms) { completion.await() }
            } catch (_: TimeoutCancellationException) {
                throw TtsTimeout("TTS speak timed out after ${ms}ms.")
            } finally {
                utteranceStates.remove(utteranceId)
                abandonAudioFocus(utteranceId)
            }
        return TtsSpeakResponse(utteranceId = utteranceId, completion = done)
    }

    override suspend fun stop(): TtsStopResponse {
        val engine = ensureReady()
        withContext(Dispatchers.Main) {
            engine.stop()
        }
        for ((id, deferred) in utteranceStates.entries) {
            deferred.complete(TtsSpeakCompletion.Stopped)
            abandonAudioFocus(id)
        }
        utteranceStates.clear()
        return TtsStopResponse(stopped = true)
    }

    private suspend fun ensureReady(): TextToSpeech {
        val existing = tts
        if (existing != null) return existing

        return initMutex.withLock {
            val again = tts
            if (again != null) return@withLock again

            val init = CompletableDeferred<Int>()
            val engine =
                withContext(Dispatchers.Main) {
                    TextToSpeech(ctx) { status ->
                        if (!init.isCompleted) init.complete(status)
                    }
                }

            val status =
                try {
                    withTimeout(7_000L) { init.await() }
                } catch (t: TimeoutCancellationException) {
                    withContext(Dispatchers.Main) { engine.shutdown() }
                    throw TtsInitFailed("TTS init timed out.")
                }

            if (status != TextToSpeech.SUCCESS) {
                withContext(Dispatchers.Main) { engine.shutdown() }
                throw TtsInitFailed("TTS init failed (status=$status).")
            }

            withContext(Dispatchers.Main) {
                engine.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            // no-op
                        }

                        override fun onDone(utteranceId: String?) {
                            if (utteranceId.isNullOrBlank()) return
                            utteranceStates.remove(utteranceId)?.complete(TtsSpeakCompletion.Done)
                            abandonAudioFocus(utteranceId)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (utteranceId.isNullOrBlank()) return
                            utteranceStates.remove(utteranceId)?.complete(TtsSpeakCompletion.Error)
                            abandonAudioFocus(utteranceId)
                        }

                        override fun onError(
                            utteranceId: String?,
                            errorCode: Int,
                        ) {
                            if (utteranceId.isNullOrBlank()) return
                            utteranceStates.remove(utteranceId)?.complete(TtsSpeakCompletion.Error)
                            abandonAudioFocus(utteranceId)
                        }
                    },
                )
            }

            tts = engine
            return@withLock engine
        }
    }

    private data class AudioFocusToken(
        val audioManager: AudioManager,
        val request: AudioFocusRequest?,
    )

    private fun requestAudioFocus(): AudioFocusToken {
        val am = (ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager) ?: throw TtsNotSupported("AudioManager unavailable.")
        val attrs =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

        if (Build.VERSION.SDK_INT >= 26) {
            val req =
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener { }
                    .build()
            val r = am.requestAudioFocus(req)
            if (r != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                throw TtsAudioFocusDenied("Audio focus denied (code=$r).")
            }
            return AudioFocusToken(audioManager = am, request = req)
        }

        @Suppress("DEPRECATION")
        val r = am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        if (r != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw TtsAudioFocusDenied("Audio focus denied (code=$r).")
        }
        return AudioFocusToken(audioManager = am, request = null)
    }

    private fun abandonAudioFocus(utteranceId: String) {
        val token = focusTokens.remove(utteranceId) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val req = token.request
                if (req != null) token.audioManager.abandonAudioFocusRequest(req)
                return
            }
            @Suppress("DEPRECATION")
            token.audioManager.abandonAudioFocus(null)
        } catch (_: Throwable) {
        }
    }
}
