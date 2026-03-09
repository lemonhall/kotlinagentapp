package com.lsl.kotlin_agent_app.ui.instant_translation

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtime
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeAudioFormat
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeCallback
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeConfig
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeParam
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

internal object QwenRealtimeTtsDefaults {
    const val MODEL = "qwen3-tts-flash-realtime"
    const val WEBSOCKET_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"
    const val VOICE = "Cherry"
    const val SAMPLE_RATE_HZ = 24_000
    const val AUDIO_TIMEOUT_MS = 20_000L
    const val CLOSE_TIMEOUT_MS = 3_000L
}

internal data class QwenRealtimeTtsRequest(
    val text: String,
    val languageCode: String,
    val apiKey: String,
    val model: String = QwenRealtimeTtsDefaults.MODEL,
    val websocketUrl: String = QwenRealtimeTtsDefaults.WEBSOCKET_URL,
    val voice: String = QwenRealtimeTtsDefaults.VOICE,
    val timeoutMs: Long = QwenRealtimeTtsDefaults.AUDIO_TIMEOUT_MS,
)

internal interface QwenRealtimeTtsClient {
    suspend fun synthesize(
        request: QwenRealtimeTtsRequest,
        onAudioChunk: (ByteArray) -> Unit,
    )
}

internal class DashScopeQwenRealtimeTtsClient(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : QwenRealtimeTtsClient {
    override suspend fun synthesize(
        request: QwenRealtimeTtsRequest,
        onAudioChunk: (ByteArray) -> Unit,
    ) =
        withContext(ioDispatcher) {
            val responseDone = CompletableDeferred<Unit>()
            val sessionClosed = CompletableDeferred<Unit>()
            val param =
                QwenTtsRealtimeParam.builder()
                    .model(request.model)
                    .url(request.websocketUrl)
                    .apikey(request.apiKey)
                    .build()
            val realtime =
                QwenTtsRealtime(
                    param,
                    object : QwenTtsRealtimeCallback() {
                        override fun onEvent(message: JsonObject) {
                            try {
                                when (message.get("type")?.asString.orEmpty()) {
                                    "response.audio.delta" -> {
                                        val audioBase64 = message.get("delta")?.asString.orEmpty()
                                        if (audioBase64.isBlank()) return
                                        onAudioChunk(Base64.getDecoder().decode(audioBase64))
                                    }

                                    "response.done" -> {
                                        if (!responseDone.isCompleted) responseDone.complete(Unit)
                                    }

                                    "session.finished" -> {
                                        if (!sessionClosed.isCompleted) sessionClosed.complete(Unit)
                                        if (!responseDone.isCompleted) responseDone.complete(Unit)
                                    }
                                }
                            } catch (t: Throwable) {
                                if (!responseDone.isCompleted) {
                                    responseDone.completeExceptionally(t)
                                }
                            }
                        }

                        override fun onClose(
                            code: Int,
                            reason: String,
                        ) {
                            if (!sessionClosed.isCompleted) sessionClosed.complete(Unit)
                            if (!responseDone.isCompleted) responseDone.complete(Unit)
                        }
                    },
                )

            try {
                realtime.connect()
                realtime.updateSession(
                    QwenTtsRealtimeConfig.builder()
                        .voice(request.voice)
                        .responseFormat(QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT)
                        .mode("commit")
                        .build(),
                )
                realtime.appendText(request.text)
                realtime.commit()
                withTimeout(request.timeoutMs) {
                    responseDone.await()
                }
            } catch (t: TimeoutCancellationException) {
                throw IllegalStateException("Qwen realtime TTS timed out", t)
            } finally {
                runCatching { realtime.finish() }
                withTimeoutOrNull(QwenRealtimeTtsDefaults.CLOSE_TIMEOUT_MS) {
                    sessionClosed.await()
                }
            }
        }
}

internal interface InstantTranslationAudioPlayer {
    fun writePcm(bytes: ByteArray)

    fun awaitPlaybackComplete()

    fun close()
}

internal class AudioTrackInstantTranslationAudioPlayer(
    private val sampleRateHz: Int = QwenRealtimeTtsDefaults.SAMPLE_RATE_HZ,
) : InstantTranslationAudioPlayer {
    private val bytesPerFrame = 2
    private val audioTrack: AudioTrack
    private var totalFramesWritten: Long = 0L
    private var started: Boolean = false

    init {
        val minBufferSize =
            AudioTrack.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(sampleRateHz / 10 * bytesPerFrame)
        audioTrack =
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                ).setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                ).setBufferSizeInBytes(minBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
    }

    @Synchronized
    override fun writePcm(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (!started) {
            audioTrack.play()
            started = true
        }
        val written =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioTrack.write(bytes, 0, bytes.size, AudioTrack.WRITE_BLOCKING)
            } else {
                @Suppress("DEPRECATION")
                audioTrack.write(bytes, 0, bytes.size)
            }
        if (written < 0) {
            throw IllegalStateException("TTS audio playback failed: $written")
        }
        totalFramesWritten += written / bytesPerFrame
    }

    override fun awaitPlaybackComplete() {
        if (!started || totalFramesWritten <= 0L) return
        val deadline = SystemClock.elapsedRealtime() + QwenRealtimeTtsDefaults.AUDIO_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (audioTrack.playbackHeadPosition.toLong() >= totalFramesWritten) break
            Thread.sleep(16L)
        }
        runCatching { audioTrack.stop() }
    }

    override fun close() {
        runCatching {
            if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack.flush()
            }
        }
        runCatching { audioTrack.release() }
    }
}

internal class QwenRealtimeInstantTranslationSpeaker(
    private val apiKeyProvider: () -> String,
    private val archiveManager: InstantTranslationArchiveManager? = null,
    private val ttsClient: QwenRealtimeTtsClient = DashScopeQwenRealtimeTtsClient(),
    private val playerFactory: () -> InstantTranslationAudioPlayer = { AudioTrackInstantTranslationAudioPlayer() },
    private val playbackHooks: InstantTranslationPlaybackHooks = InstantTranslationPlaybackHooks.None,
) : InstantTranslationSpeaker {
    override suspend fun speak(turn: InstantTranslationTurn) {
        speakInternal(
            text = turn.translatedText,
            languageCode = turn.targetLanguageCode,
            turn = turn,
        )
    }

    override suspend fun speak(
        text: String,
        languageCode: String,
    ) {
        speakInternal(text = text, languageCode = languageCode, turn = null)
    }

    private suspend fun speakInternal(
        text: String,
        languageCode: String,
        turn: InstantTranslationTurn?,
    ) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val apiKey = apiKeyProvider.invoke().trim()
        if (apiKey.isBlank()) {
            error("请先在 Settings 的 Voice Input 中填写 DASHSCOPE_API_KEY")
        }

        val archiveFiles = turn?.let { archiveManager?.prepareTtsOutputFiles(it, audioExtension = "wav") }
        val pcmBytes = ByteArrayOutputStream()
        val player = playerFactory.invoke()
        playbackHooks.beforePlayback()
        try {
            ttsClient.synthesize(
                request =
                    QwenRealtimeTtsRequest(
                        text = trimmed,
                        languageCode = languageCode.trim().ifBlank { "auto" },
                        apiKey = apiKey,
                    ),
                onAudioChunk = { chunk ->
                    if (chunk.isEmpty()) return@synthesize
                    pcmBytes.write(chunk)
                    player.writePcm(chunk)
                },
            )
            player.awaitPlaybackComplete()
            archiveFiles?.audioFile?.let { outputFile ->
                val payload = pcmBytes.toByteArray()
                if (payload.isNotEmpty()) {
                    writeMono16BitPcmWaveFile(
                        outputFile = outputFile,
                        pcmBytes = payload,
                        sampleRateHz = QwenRealtimeTtsDefaults.SAMPLE_RATE_HZ,
                    )
                }
            }
        } finally {
            player.close()
            playbackHooks.afterPlayback()
        }
    }
}

private fun writeMono16BitPcmWaveFile(
    outputFile: File,
    pcmBytes: ByteArray,
    sampleRateHz: Int,
) {
    outputFile.parentFile?.mkdirs()
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    val byteRate = sampleRateHz * 2
    val dataSize = pcmBytes.size
    header.put("RIFF".toByteArray(Charsets.US_ASCII))
    header.putInt(36 + dataSize)
    header.put("WAVE".toByteArray(Charsets.US_ASCII))
    header.put("fmt ".toByteArray(Charsets.US_ASCII))
    header.putInt(16)
    header.putShort(1)
    header.putShort(1)
    header.putInt(sampleRateHz)
    header.putInt(byteRate)
    header.putShort(2)
    header.putShort(16)
    header.put("data".toByteArray(Charsets.US_ASCII))
    header.putInt(dataSize)
    outputFile.outputStream().use { out ->
        out.write(header.array())
        out.write(pcmBytes)
    }
}
