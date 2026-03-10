package com.lsl.kotlin_agent_app.ui.simultaneous_interpretation

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal interface LiveTranslateAudioInputSource {
    fun start(
        onStarted: () -> Unit,
        onAudioFrame: (ByteArray) -> Unit,
        onError: (Throwable) -> Unit,
    )

    fun stop()
}

internal class MicrophoneLiveTranslateAudioInputSource(
    private val audioCaptureMode: LiveTranslateAudioCaptureMode = LiveTranslateAudioCaptureMode.SENSITIVE,
    private val sampleRateHz: Int = LiveTranslateDefaults.INPUT_SAMPLE_RATE_HZ,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LiveTranslateAudioInputSource {
    private var scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var readJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private val stopped = AtomicBoolean(false)

    override fun start(
        onStarted: () -> Unit,
        onAudioFrame: (ByteArray) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (readJob != null) return
        stopped.set(false)
        scope = CoroutineScope(SupervisorJob() + ioDispatcher)

        val bufferSize =
            maxOf(
                AudioRecord.getMinBufferSize(
                    sampleRateHz,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ),
                sampleRateHz / 5 * 2,
            )
        if (bufferSize <= 0) {
            onError(IllegalStateException("麦克风不可用"))
            return
        }

        val record =
            createAudioRecord(bufferSize) ?: run {
                onError(IllegalStateException("无法初始化麦克风"))
                return
            }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError(IllegalStateException("无法初始化麦克风"))
            return
        }

        audioRecord = record
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            runCatching { record.release() }
            audioRecord = null
            onError(IllegalStateException("无法启动麦克风录音"))
            return
        }

        onStarted()

        readJob =
            scope.launch {
                val buffer = ByteArray(bufferSize)
                try {
                    while (!stopped.get()) {
                        val read = record.read(buffer, 0, buffer.size)
                        when {
                            read > 0 -> onAudioFrame(buffer.copyOf(read))
                            read == AudioRecord.ERROR_BAD_VALUE || read == AudioRecord.ERROR_INVALID_OPERATION -> {
                                throw IllegalStateException("麦克风读取失败: $read")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    if (!stopped.get()) {
                        onError(t)
                    }
                }
            }
    }

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
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
        scope.cancel()
    }

    private fun createAudioRecord(bufferSize: Int): AudioRecord? {
        val preferredSources =
            when (audioCaptureMode) {
                LiveTranslateAudioCaptureMode.SENSITIVE -> listOf(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC,
                )

                LiveTranslateAudioCaptureMode.CLEAR ->
                    buildList {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            add(MediaRecorder.AudioSource.UNPROCESSED)
                        }
                        add(MediaRecorder.AudioSource.MIC)
                    }
            }

        for (source in preferredSources) {
            val record =
                runCatching {
                    AudioRecord(
                        source,
                        sampleRateHz,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize,
                    )
                }.getOrNull()
            if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                return record
            }
            runCatching { record?.release() }
        }
        return null
    }
}
