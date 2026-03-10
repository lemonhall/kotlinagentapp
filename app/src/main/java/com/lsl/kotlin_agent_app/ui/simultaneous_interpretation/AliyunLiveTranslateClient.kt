package com.lsl.kotlin_agent_app.ui.simultaneous_interpretation

import com.alibaba.dashscope.audio.omni.OmniRealtimeAudioFormat
import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam
import com.alibaba.dashscope.audio.omni.OmniRealtimeTranslationParam
import com.google.gson.JsonObject
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object LiveTranslateDefaults {
    const val MODEL = "qwen3-livetranslate-flash-realtime"
    const val WEBSOCKET_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"
    const val INPUT_TRANSCRIPTION_MODEL = "qwen3-asr-flash-realtime"
    const val VOICE = "Cherry"
    const val INPUT_SAMPLE_RATE_HZ = 16_000
    const val OUTPUT_SAMPLE_RATE_HZ = 24_000
}

internal data class LiveTranslateSessionStartConfig(
    val apiKey: String,
    val targetLanguageCode: String,
    val targetLanguageLabel: String,
    val audioCaptureMode: LiveTranslateAudioCaptureMode = LiveTranslateAudioCaptureMode.SENSITIVE,
    val model: String = LiveTranslateDefaults.MODEL,
    val websocketUrl: String = LiveTranslateDefaults.WEBSOCKET_URL,
    val voice: String = LiveTranslateDefaults.VOICE,
    val inputSampleRateHz: Int = LiveTranslateDefaults.INPUT_SAMPLE_RATE_HZ,
    val outputSampleRateHz: Int = LiveTranslateDefaults.OUTPUT_SAMPLE_RATE_HZ,
    val inputTranscriptionModel: String = LiveTranslateDefaults.INPUT_TRANSCRIPTION_MODEL,
)

internal interface AliyunLiveTranslateClientListener {
    fun onSessionCreated(sessionId: String)

    fun onSourceTranscriptCompleted(text: String)

    fun onTranslationDelta(delta: String)

    fun onAudioDelta(bytes: ByteArray)

    fun onResponseDone()

    fun onError(message: String, throwable: Throwable? = null)

    fun onClosed(code: Int, reason: String)
}

internal interface AliyunLiveTranslateClient {
    suspend fun start(
        config: LiveTranslateSessionStartConfig,
        listener: AliyunLiveTranslateClientListener,
    )

    fun appendAudioFrame(bytes: ByteArray)

    fun stop()
}

internal class LiveTranslateEventDispatcher(
    private val onSessionCreated: (String) -> Unit,
    private val onSourceTranscriptCompleted: (String) -> Unit,
    private val onTranslationDelta: (String) -> Unit,
    private val onAudioDelta: (ByteArray) -> Unit,
    private val onResponseDone: () -> Unit,
    private val onError: (String, Throwable?) -> Unit,
    private val requestResponse: () -> Unit,
) {
    private var responseActive: Boolean = false
    private var pendingResponseCount: Int = 0

    fun onEvent(message: JsonObject) {
        when (message.get("type")?.asString.orEmpty()) {
            "session.created" -> {
                val sessionId =
                    runCatching {
                        message.getAsJsonObject("session")?.get("id")?.asString.orEmpty()
                    }.getOrDefault("")
                if (sessionId.isNotBlank()) {
                    onSessionCreated(sessionId)
                }
            }

            "conversation.item.input_audio_transcription.completed" -> {
                val transcript = message.get("transcript")?.asString.orEmpty().trim()
                if (transcript.isBlank()) return
                onSourceTranscriptCompleted(transcript)
                if (responseActive) {
                    pendingResponseCount += 1
                } else {
                    requestResponse()
                    responseActive = true
                }
            }

            "response.audio_transcript.delta" -> {
                val delta = message.get("delta")?.asString.orEmpty()
                if (delta.isNotBlank()) {
                    onTranslationDelta(delta)
                }
            }

            "response.audio.delta" -> {
                val delta = message.get("delta")?.asString.orEmpty()
                if (delta.isBlank()) return
                try {
                    onAudioDelta(Base64.getDecoder().decode(delta))
                } catch (t: Throwable) {
                    onError("实时译音解码失败", t)
                }
            }

            "response.done" -> {
                onResponseDone()
                if (pendingResponseCount > 0) {
                    pendingResponseCount -= 1
                    requestResponse()
                    responseActive = true
                } else {
                    responseActive = false
                }
            }
        }
    }
}

internal class DashScopeAliyunLiveTranslateClient(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AliyunLiveTranslateClient {
    @Volatile
    private var conversation: OmniRealtimeConversation? = null

    override suspend fun start(
        config: LiveTranslateSessionStartConfig,
        listener: AliyunLiveTranslateClientListener,
    ) =
        withContext(ioDispatcher) {
            val conversationHolder = arrayOfNulls<OmniRealtimeConversation>(1)
            val dispatcher =
                LiveTranslateEventDispatcher(
                    onSessionCreated = listener::onSessionCreated,
                    onSourceTranscriptCompleted = listener::onSourceTranscriptCompleted,
                    onTranslationDelta = listener::onTranslationDelta,
                    onAudioDelta = listener::onAudioDelta,
                    onResponseDone = listener::onResponseDone,
                    onError = listener::onError,
                    requestResponse = {
                        conversationHolder[0]?.createResponse(
                            null,
                            listOf(OmniRealtimeModality.AUDIO, OmniRealtimeModality.TEXT),
                        )
                    },
                )

            val param =
                OmniRealtimeParam.builder()
                    .model(config.model)
                    .apikey(config.apiKey)
                    .url(config.websocketUrl)
                    .build()
            val realtimeConversation =
                OmniRealtimeConversation(
                    param,
                    object : OmniRealtimeCallback() {
                        override fun onEvent(message: JsonObject) {
                            dispatcher.onEvent(message)
                        }

                        override fun onClose(
                            code: Int,
                            reason: String,
                        ) {
                            listener.onClosed(code, reason)
                        }
                    },
                )
            conversationHolder[0] = realtimeConversation
            realtimeConversation.connect()
            realtimeConversation.updateSession(
                OmniRealtimeConfig.builder()
                    .modalities(listOf(OmniRealtimeModality.AUDIO, OmniRealtimeModality.TEXT))
                    .voice(config.voice)
                    .inputAudioFormat(OmniRealtimeAudioFormat.PCM_16000HZ_MONO_16BIT)
                    .outputAudioFormat(OmniRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT)
                    .enableInputAudioTranscription(true)
                    .InputAudioTranscription(config.inputTranscriptionModel)
                    .enableTurnDetection(true)
                    .translationConfig(
                        OmniRealtimeTranslationParam.builder()
                            .language(config.targetLanguageCode.trim().ifBlank { "en" })
                            .build(),
                    ).build(),
            )
            conversation = realtimeConversation
        }

    override fun appendAudioFrame(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val realtimeConversation = conversation ?: return
        realtimeConversation.appendAudio(Base64.getEncoder().encodeToString(bytes))
    }

    override fun stop() {
        val realtimeConversation = conversation ?: return
        conversation = null
        runCatching { realtimeConversation.close(1000, "bye") }
    }
}
