package com.lsl.kotlin_agent_app.ui.simultaneous_interpretation

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build

internal interface LiveTranslateAudioPlayer {
    fun writePcm(bytes: ByteArray)

    fun close()
}

internal class AudioTrackLiveTranslateAudioPlayer(
    private val sampleRateHz: Int = LiveTranslateDefaults.OUTPUT_SAMPLE_RATE_HZ,
) : LiveTranslateAudioPlayer {
    private val bytesPerFrame = 2
    private val audioTrack: AudioTrack
    private var started: Boolean = false

    init {
        val minBufferSize =
            AudioTrack.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(sampleRateHz / 5 * bytesPerFrame)
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
                ).setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBufferSize)
                .build()
    }

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
            throw IllegalStateException("实时译音播放失败: $written")
        }
    }

    override fun close() {
        runCatching {
            if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack.stop()
            }
        }
        runCatching { audioTrack.flush() }
        runCatching { audioTrack.release() }
    }
}