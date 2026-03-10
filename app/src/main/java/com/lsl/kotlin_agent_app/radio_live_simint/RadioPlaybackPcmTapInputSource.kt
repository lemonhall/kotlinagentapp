package com.lsl.kotlin_agent_app.radio_live_simint

import android.content.Context
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lsl.kotlin_agent_app.BuildConfig
import com.lsl.kotlin_agent_app.ui.simultaneous_interpretation.LiveTranslateAudioInputSource
import com.lsl.kotlin_agent_app.ui.simultaneous_interpretation.LiveTranslateDefaults
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal class RadioPlaybackPcmTapInputSource(
    context: Context,
    private val streamUrl: String,
    private val outputSampleRateHz: Int = LiveTranslateDefaults.INPUT_SAMPLE_RATE_HZ,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LiveTranslateAudioInputSource {
    private val appContext = context.applicationContext
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    @Volatile private var player: ExoPlayer? = null
    private var scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var workerJob: Job? = null

    @Volatile private var inputSampleRateHz: Int = 0
    @Volatile private var inputChannelCount: Int = 0
    @Volatile private var inputEncoding: Int = 0

    private val queue = ArrayBlockingQueue<ByteArray>(256)

    override fun start(
        onStarted: () -> Unit,
        onAudioFrame: (ByteArray) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (!started.compareAndSet(false, true)) return
        stopped.set(false)
        scope = CoroutineScope(SupervisorJob() + ioDispatcher)

        workerJob =
            scope.launch {
                val framer = Pcm16kMonoFramer(frameBytes = outputSampleRateHz / 50 * 2) // 20ms @ 16kHz mono pcm16 = 640 bytes
                var resampler: StreamingPcm16MonoResampler? = null
                while (!stopped.get()) {
                    val buf = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    if (buf.isEmpty()) continue
                    val sr = inputSampleRateHz
                    val ch = inputChannelCount
                    val enc = inputEncoding
                    if (sr <= 0 || ch <= 0 || enc != C.ENCODING_PCM_16BIT) continue

                    if (resampler == null || resampler.inputSampleRateHz != sr) {
                        resampler = StreamingPcm16MonoResampler(inputSampleRateHz = sr, outputSampleRateHz = outputSampleRateHz)
                    }

                    val mono = downmixToMonoPcm16(buf, channelCount = ch)
                    val out = resampler.process(mono)
                    framer.offer(out) { frame ->
                        try {
                            onAudioFrame(frame)
                        } catch (t: Throwable) {
                            onError(t)
                        }
                    }
                }
            }

        val teeSink =
            object : TeeAudioProcessor.AudioBufferSink {
                override fun flush(
                    sampleRateHz: Int,
                    channelCount: Int,
                    encoding: Int,
                ) {
                    inputSampleRateHz = sampleRateHz
                    inputChannelCount = channelCount
                    inputEncoding = encoding
                }

                override fun handleBuffer(buffer: ByteBuffer) {
                    if (stopped.get()) return
                    if (!buffer.hasRemaining()) return
                    val dup = buffer.slice()
                    val bytes = ByteArray(dup.remaining())
                    dup.get(bytes)
                    if (!queue.offer(bytes)) {
                        // best-effort drop: avoid blocking audio thread
                        queue.poll()
                        queue.offer(bytes)
                    }
                }
            }

        val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        mainScope.launch {
            try {
                val ua =
                    "kotlin-agent-app/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.SDK_INT})"
                val http =
                    DefaultHttpDataSource.Factory()
                        .setUserAgent(ua)
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(15_000)
                        .setReadTimeoutMs(20_000)
                val dataSourceFactory = DefaultDataSource.Factory(appContext, http)
                val mediaSourceFactory =
                    DefaultMediaSourceFactory(appContext).setDataSourceFactory(dataSourceFactory)

                val tee = TeeAudioProcessor(teeSink)
                val audioSink: AudioSink =
                    DefaultAudioSink.Builder(appContext)
                        .setEnableFloatOutput(false)
                        .setAudioProcessors(arrayOf(tee))
                        .build()

                val renderersFactory =
                    object : DefaultRenderersFactory(appContext) {
                        override fun buildAudioSink(
                            context: Context,
                            enableFloatOutput: Boolean,
                            enableAudioTrackPlaybackParams: Boolean,
                        ): AudioSink {
                            return audioSink
                        }
                    }

                val exo =
                    ExoPlayer.Builder(appContext, renderersFactory)
                        .setMediaSourceFactory(mediaSourceFactory)
                        .build()
                player = exo

                exo.addListener(
                    object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            if (!stopped.get()) {
                                onError(IllegalStateException(error.message ?: "radio player error", error))
                            }
                        }
                    },
                )

                val aa =
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build()
                exo.setAudioAttributes(aa, false)
                exo.volume = 0f
                exo.setMediaItem(MediaItem.fromUri(streamUrl))
                exo.prepare()
                exo.playWhenReady = true

                onStarted()
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { player?.release() }
        player = null
        workerJob?.cancel()
        workerJob = null
        scope.cancel()
    }
}

private fun downmixToMonoPcm16(
    bytes: ByteArray,
    channelCount: Int,
): ShortArray {
    val frames = (bytes.size / 2 / channelCount).coerceAtLeast(0)
    val out = ShortArray(frames)
    var byteIndex = 0
    for (i in 0 until frames) {
        var sum = 0
        for (ch in 0 until channelCount) {
            val lo = bytes[byteIndex].toInt() and 0xFF
            val hi = bytes[byteIndex + 1].toInt()
            val s = (hi shl 8) or lo
            sum += s.toShort().toInt()
            byteIndex += 2
        }
        out[i] = (sum / channelCount).toShort()
    }
    return out
}

private class Pcm16kMonoFramer(
    private val frameBytes: Int,
) {
    private var buffer = ByteArray(frameBytes * 8)
    private var size: Int = 0

    fun offer(
        monoPcm16: ShortArray,
        onFrame: (ByteArray) -> Unit,
    ) {
        if (monoPcm16.isEmpty()) return
        val bytesNeeded = monoPcm16.size * 2
        ensureCapacity(size + bytesNeeded)
        var idx = size
        for (s in monoPcm16) {
            buffer[idx] = (s.toInt() and 0xFF).toByte()
            buffer[idx + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
            idx += 2
        }
        size += bytesNeeded

        while (size >= frameBytes) {
            val frame = buffer.copyOfRange(0, frameBytes)
            onFrame(frame)
            // shift left
            System.arraycopy(buffer, frameBytes, buffer, 0, size - frameBytes)
            size -= frameBytes
        }
    }

    private fun ensureCapacity(target: Int) {
        if (target <= buffer.size) return
        val nextSize = maxOf(target, buffer.size * 2)
        buffer = buffer.copyOf(nextSize)
    }
}

private class StreamingPcm16MonoResampler(
    val inputSampleRateHz: Int,
    private val outputSampleRateHz: Int,
) {
    private val stepFixed: Long = ((inputSampleRateHz.toLong() shl 16) / outputSampleRateHz.toLong()).coerceAtLeast(1L)
    private var nextPosFixed: Long = 0L // absolute source sample position (fixed 16.16)
    private var totalSourceSamples: Long = 0L
    private var prevTail: Short? = null

    fun process(srcMono: ShortArray): ShortArray {
        if (srcMono.isEmpty()) return ShortArray(0)
        val baseAbs = totalSourceSamples
        val endAbs = baseAbs + srcMono.size.toLong() - 1L

        fun sampleAt(absIndex: Long): Int {
            return when {
                absIndex == baseAbs - 1L -> prevTail?.toInt() ?: 0
                absIndex < baseAbs -> prevTail?.toInt() ?: 0
                absIndex > endAbs -> srcMono.last().toInt()
                else -> srcMono[(absIndex - baseAbs).toInt()].toInt()
            }
        }

        val out = ArrayList<Short>(srcMono.size * outputSampleRateHz / inputSampleRateHz + 4)
        while (true) {
            val idx = (nextPosFixed ushr 16).toLong()
            if (idx + 1L > endAbs) break
            val frac = (nextPosFixed and 0xFFFF).toInt()
            val s0 = sampleAt(idx)
            val s1 = sampleAt(idx + 1L)
            val interp = s0 + (((s1 - s0) * frac) shr 16)
            out.add(interp.toShort())
            nextPosFixed += stepFixed
        }

        totalSourceSamples += srcMono.size.toLong()
        prevTail = srcMono.last()
        return out.toShortArray()
    }
}
