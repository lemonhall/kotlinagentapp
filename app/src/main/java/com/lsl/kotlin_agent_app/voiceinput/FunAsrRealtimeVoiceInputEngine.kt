package com.lsl.kotlin_agent_app.voiceinput

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.alibaba.dashscope.audio.asr.recognition.Recognition
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult
import com.alibaba.dashscope.common.ResultCallback
import com.alibaba.dashscope.utils.Constants
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class FunAsrRealtimeVoiceInputEngine(
    context: Context,
    private val config: VoiceInputConfig,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : VoiceInputEngine {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val stopped = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    private var recognizer: Recognition? = null
    private var audioRecord: AudioRecord? = null
    private var readJob: Job? = null
    private var listener: VoiceInputListener? = null

    override fun start(listener: VoiceInputListener) {
        if (config.apiKey.isBlank()) error("missing DASHSCOPE_API_KEY")
        this.listener = listener
        Constants.baseWebsocketApiUrl = config.websocketApiUrl

        val param =
            RecognitionParam.builder()
                .model(config.model)
                .apiKey(config.apiKey)
                .format(config.audioFormat)
                .sampleRate(config.sampleRateHz)
                .build()

        val recognition = Recognition()
        recognizer = recognition

        recognition.call(
            param,
            object : ResultCallback<RecognitionResult>() {
                override fun onEvent(result: RecognitionResult?) {
                    val text = result?.sentence?.text?.trim().orEmpty()
                    if (text.isBlank()) return
                    if (result?.isSentenceEnd == true) {
                        listener.onFinalTranscript(text)
                    } else {
                        listener.onPartialTranscript(text)
                    }
                }

                override fun onComplete() {
                    notifyStopped()
                }

                override fun onError(e: Exception?) {
                    val error = e ?: IllegalStateException("voice input failed")
                    listener.onError(error)
                    releaseResources(closeRecognizer = false)
                }
            },
        )

        val bufferSize =
            maxOf(
                AudioRecord.getMinBufferSize(
                    config.sampleRateHz,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ),
                config.sampleRateHz / 5 * 2,
            )

        if (bufferSize <= 0) {
            releaseResources(closeRecognizer = true)
            error("microphone is unavailable")
        }

        val record =
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                config.sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            releaseResources(closeRecognizer = true)
            error("unable to initialize microphone")
        }

        audioRecord = record
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            releaseResources(closeRecognizer = true)
            error("unable to start microphone recording")
        }

        listener.onListening()

        readJob =
            scope.launch {
                val buffer = ByteArray(bufferSize)
                try {
                    while (!stopped.get()) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            val audioBuffer = ByteBuffer.wrap(buffer.copyOf(read))
                            recognition.sendAudioFrame(audioBuffer)
                        } else if (read == AudioRecord.ERROR_BAD_VALUE || read == AudioRecord.ERROR_INVALID_OPERATION) {
                            error("microphone read failed: $read")
                        }
                    }
                } catch (t: Throwable) {
                    if (!stopped.get()) {
                        listener.onError(t)
                        releaseResources(closeRecognizer = true)
                    }
                }
            }
    }

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        releaseResources(closeRecognizer = true)
        notifyStopped()
    }

    private fun releaseResources(closeRecognizer: Boolean) {
        readJob?.cancel()
        readJob = null

        audioRecord?.let { record ->
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            }
            runCatching { record.release() }
        }
        audioRecord = null

        if (closeRecognizer) {
            recognizer?.let { recognition ->
                runCatching { recognition.stop() }
                runCatching { recognition.duplexApi?.close(1000, "bye") }
            }
            recognizer = null
        }

        scope.cancel()
    }

    private fun notifyStopped() {
        if (!finished.compareAndSet(false, true)) return
        listener?.onStopped()
    }
}
