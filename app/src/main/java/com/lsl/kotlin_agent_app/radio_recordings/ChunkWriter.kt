package com.lsl.kotlin_agent_app.radio_recordings

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import androidx.media3.common.C
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.recordings.RecordingSessionRef
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

internal class ChunkWriter(
    private val ws: AgentsWorkspace,
    private val store: RadioRecordingsStore,
    private val sessionRef: RecordingSessionRef,
    private val chunkDurationMinProvider: (chunkIndex: Int) -> Int,
    private val bitrateBps: Int = 64_000,
) {
    private val queue = ArrayBlockingQueue<ByteArray>(256)
    @Volatile private var started = false
    @Volatile private var stopped = false
    @Volatile private var failed = false

    @Volatile private var sampleRateHz: Int = 0
    @Volatile private var channelCount: Int = 0
    @Volatile private var pcmEncoding: Int = 0

    private var thread: Thread? = null

    fun configurePcmFormat(
        sampleRateHz: Int,
        channelCount: Int,
        pcmEncoding: Int,
    ) {
        if (stopped) return
        this.sampleRateHz = sampleRateHz.coerceAtLeast(0)
        this.channelCount = channelCount.coerceAtLeast(0)
        this.pcmEncoding = pcmEncoding
        if (!started) startThread()
    }

    fun offerPcm(buffer: ByteBuffer) {
        if (stopped || failed) return
        if (!buffer.hasRemaining()) return
        val dup = buffer.slice()
        val bytes = ByteArray(dup.remaining())
        dup.get(bytes)
        if (!queue.offer(bytes)) {
            fail(code = "BufferOverflow", message = "pcm queue overflow")
        }
    }

    fun stop(finalState: String) {
        if (stopped) return
        stopped = true
        try {
            thread?.join(3_000)
        } catch (_: Throwable) {
        }
        try {
            val metaPath = sessionRef.metaPath
            if (ws.exists(metaPath)) {
                val raw = ws.readTextFile(metaPath, maxBytes = 2L * 1024L * 1024L)
                val prev = RecordingMetaV1.parse(raw)
                val next = prev.copy(state = finalState, updatedAt = RecordingMetaV1.nowIso())
                store.writeSessionMeta(sessionRef.sessionId, next)
                store.writeSessionStatus(sessionRef.sessionId, ok = true, note = finalState)
            }
        } catch (_: Throwable) {
        }
    }

    private fun startThread() {
        started = true
        val t =
            Thread(
                {
                    runLoop()
                },
                "ChunkWriter-${sessionRef.sessionId}",
            )
        thread = t
        t.start()
    }

    private fun runLoop() {
        if (Build.VERSION.SDK_INT < 29) {
            fail(code = "UnsupportedSdk", message = "recording requires API 29+ (Android 10)")
            return
        }
        if (sampleRateHz <= 0 || channelCount <= 0) {
            fail(code = "InvalidAudioFormat", message = "invalid pcm format: sr=$sampleRateHz ch=$channelCount")
            return
        }
        if (pcmEncoding != C.ENCODING_PCM_16BIT) {
            fail(code = "UnsupportedAudioFormat", message = "unsupported pcm encoding: $pcmEncoding (expect PCM_16BIT)")
            return
        }
        val bytesPerSample = 2 // expect PCM 16-bit
        val bytesPerFrame = channelCount * bytesPerSample
        if (bytesPerFrame <= 0) {
            fail(code = "InvalidAudioFormat", message = "invalid pcm frame size: ch=$channelCount")
            return
        }

        var samplesInChunk = 0L
        var totalSamples = 0L
        var chunkBaseSamples = 0L
        var chunkIndex = 1
        var chunkSamplesTarget = 0L

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()

        fun drainEncoder() {
            val enc = codec ?: return
            val mx = muxer ?: return
            while (true) {
                val outIdx = enc.dequeueOutputBuffer(info, 0)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = mx.addTrack(enc.outputFormat)
                        mx.start()
                        muxerStarted = true
                    }
                    outIdx >= 0 -> {
                        val outBuf = enc.getOutputBuffer(outIdx)
                        if (muxerStarted && trackIndex >= 0 && outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            try {
                                mx.writeSampleData(trackIndex, outBuf, info)
                            } catch (_: Throwable) {
                            }
                        }
                        enc.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                    }
                    else -> return
                }
            }
        }

        fun closeEncoder() {
            try {
                if (muxerStarted) muxer?.stop()
            } catch (_: Throwable) {
            }
            try {
                muxer?.release()
            } catch (_: Throwable) {
            }
            muxer = null
            muxerStarted = false
            trackIndex = -1

            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            codec = null
        }

        fun openNewChunk() {
            closeEncoder()
            chunkBaseSamples = totalSamples

            val chunkAgentsPath = sessionRef.chunkOggPath(chunkIndex)
            val outFile = ws.toFile(chunkAgentsPath)
            outFile.parentFile?.mkdirs()

            val format =
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRateHz, channelCount).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                    setInteger(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                    setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRateHz)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024)
                }

            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            codec = enc

            val mx = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            muxer = mx
            muxerStarted = false

            store.appendChunk(sessionRef.sessionId, chunkIndex)

            val durMin = chunkDurationMinProvider(chunkIndex).coerceAtLeast(1)
            chunkSamplesTarget = (sampleRateHz.toLong() * 60L * durMin.toLong()).coerceAtLeast(1L)
        }

        try {
            openNewChunk()

            while (true) {
                if (failed) break
                val buf = queue.poll(200, TimeUnit.MILLISECONDS)
                if (buf == null) {
                    if (stopped) break
                    drainEncoder()
                    continue
                }

                var offset = 0
                while (offset < buf.size) {
                    val enc = codec ?: break
                    val inIdx = enc.dequeueInputBuffer(10_000)
                    if (inIdx < 0) {
                        drainEncoder()
                        continue
                    }
                    val inBuf = enc.getInputBuffer(inIdx) ?: continue
                    inBuf.clear()
                    val toWrite = minOf(inBuf.capacity(), buf.size - offset)
                    inBuf.put(buf, offset, toWrite)
                    offset += toWrite

                    val frames = toWrite / bytesPerFrame
                    val ptsUs = ((totalSamples - chunkBaseSamples) * 1_000_000L) / sampleRateHz.toLong()
                    totalSamples += frames.toLong()
                    samplesInChunk += frames.toLong()

                    enc.queueInputBuffer(inIdx, 0, toWrite, ptsUs, 0)
                    drainEncoder()

                    if (samplesInChunk >= chunkSamplesTarget) {
                        // Rotate at boundary: finalize current chunk and start a new one.
                        drainEncoder()
                        try {
                            val eosIdx = enc.dequeueInputBuffer(10_000)
                            if (eosIdx >= 0) {
                                enc.queueInputBuffer(eosIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            }
                        } catch (_: Throwable) {
                        }
                        drainEncoder()
                        chunkIndex += 1
                        samplesInChunk = 0L
                        openNewChunk()
                    }
                }
            }

            // Finalize.
            codec?.let { enc ->
                try {
                    val eosIdx = enc.dequeueInputBuffer(10_000)
                    if (eosIdx >= 0) {
                        val ptsUs = ((totalSamples - chunkBaseSamples) * 1_000_000L) / sampleRateHz.toLong()
                        enc.queueInputBuffer(eosIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                } catch (_: Throwable) {
                }
                drainEncoder()
            }
        } catch (t: Throwable) {
            fail(code = "EncodeFailed", message = t.message ?: "encode failed")
        } finally {
            closeEncoder()
        }
    }

    private fun fail(code: String, message: String) {
        if (failed) return
        failed = true
        try {
            val metaPath = sessionRef.metaPath
            if (ws.exists(metaPath)) {
                val raw = ws.readTextFile(metaPath, maxBytes = 2L * 1024L * 1024L)
                val prev = RecordingMetaV1.parse(raw)
                val next =
                    prev.copy(
                        state = "failed",
                        updatedAt = RecordingMetaV1.nowIso(),
                        error = RecordingMetaV1.ErrorInfo(code = code, message = message),
                    )
                store.writeSessionMeta(sessionRef.sessionId, next)
                store.writeSessionStatus(sessionRef.sessionId, ok = false, note = "$code: $message")
            }
        } catch (_: Throwable) {
        }
    }
}
