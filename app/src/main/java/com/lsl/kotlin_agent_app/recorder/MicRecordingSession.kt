package com.lsl.kotlin_agent_app.recorder

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import androidx.media3.common.C
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.radio_recordings.ChunkWriter
import com.lsl.kotlin_agent_app.radio_recordings.RadioRecordingsStore
import com.lsl.kotlin_agent_app.radio_recordings.RecordingMetaV1
import com.lsl.kotlin_agent_app.recordings.RecordingRoots
import com.lsl.kotlin_agent_app.recordings.RecordingSessionRef
import kotlin.math.abs
import java.util.concurrent.atomic.AtomicBoolean

internal class MicRecordingSession(
    appContext: Context,
    private val sessionId: String,
    private val onState: (RecorderRuntimeState) -> Unit,
    private val onStopped: (sessionId: String) -> Unit,
) {
    private val ctx = appContext.applicationContext
    private val ws = AgentsWorkspace(ctx)
    private val ref = RecordingSessionRef(rootDir = RecordingRoots.MICROPHONE_ROOT_DIR, sessionId = sessionId)
    private val store = RadioRecordingsStore(ws, rootDir = RecordingRoots.MICROPHONE_ROOT_DIR)

    @Volatile private var paused = false
    @Volatile private var stopped = false
    @Volatile private var cancelledStop = false
    @Volatile private var failedStop = false

    private val notifiedStopped = AtomicBoolean(false)

    private var thread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val sampleRateHz = 48_000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT
    private val bitrateBps = 128_000

    private var startRealtimeMs: Long = 0L
    private var pausedAccumMs: Long = 0L
    private var pauseStartMs: Long? = null
    private var lastEmitMs: Long = 0L

    fun start() {
        if (thread != null) return
        if (stopped) return
        startRealtimeMs = android.os.SystemClock.elapsedRealtime()
        acquireWakeLock()
        val t =
            Thread(
                { runLoop() },
                "MicRecording-$sessionId",
            )
        thread = t
        t.start()
    }

    fun pause() {
        if (stopped) return
        if (paused) return
        paused = true
        pauseStartMs = android.os.SystemClock.elapsedRealtime()
        emitState(level01 = 0f, state = "paused")
    }

    fun resume() {
        if (stopped) return
        if (!paused) return
        paused = false
        val ps = pauseStartMs
        if (ps != null) {
            pausedAccumMs += (android.os.SystemClock.elapsedRealtime() - ps).coerceAtLeast(0L)
        }
        pauseStartMs = null
        emitState(level01 = 0f, state = "recording")
    }

    fun stop(cancelled: Boolean) {
        if (stopped) return
        stopped = true
        cancelledStop = cancelled
        paused = false
        try {
            thread?.join(1_500)
        } catch (_: Throwable) {
        }
        releaseWakeLock()
        emitState(level01 = 0f, state = finalState())
        notifyStoppedOnce()
    }

    private fun runLoop() {
        if (Build.VERSION.SDK_INT < 29) {
            failedStop = true
            markFailed(code = "UnsupportedSdk", message = "recording requires API 29+ (Android 10)")
            stopped = true
            emitState(level01 = 0f, state = finalState())
            releaseWakeLock()
            notifyStoppedOnce()
            return
        }

        ws.ensureInitialized()
        store.ensureRoot()

        val metaRaw =
            try {
                ws.readTextFile(ref.metaPath, maxBytes = 2L * 1024L * 1024L)
            } catch (t: Throwable) {
                failedStop = true
                markFailed(code = "MetaNotFound", message = t.message ?: "missing meta")
                stopped = true
                emitState(level01 = 0f, state = finalState())
                releaseWakeLock()
                notifyStoppedOnce()
                return
            }
        val meta = runCatching { RecordingMetaV1.parse(metaRaw) }.getOrNull()
        if (meta == null) {
            failedStop = true
            markFailed(code = "InvalidMeta", message = "invalid meta json")
            stopped = true
            emitState(level01 = 0f, state = finalState())
            releaseWakeLock()
            notifyStoppedOnce()
            return
        }

        val minBuf =
            AudioRecord.getMinBufferSize(sampleRateHz, channelConfig, encoding)
                .coerceAtLeast(sampleRateHz / 10) // ~100ms
        val audio =
            try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRateHz,
                    channelConfig,
                    encoding,
                    minBuf * 2,
                )
            } catch (t: Throwable) {
                failedStop = true
                markFailed(code = "AudioRecordInitFailed", message = t.message ?: "AudioRecord init failed")
                stopped = true
                emitState(level01 = 0f, state = finalState())
                releaseWakeLock()
                notifyStoppedOnce()
                return
            }

        val writer =
            ChunkWriter(
                ws = ws,
                store = store,
                sessionRef = ref,
                chunkDurationMinProvider = { idx -> if (idx == 1) 30 else 10 },
                bitrateBps = bitrateBps,
            )
        writer.configurePcmFormat(sampleRateHz = sampleRateHz, channelCount = 1, pcmEncoding = C.ENCODING_PCM_16BIT)

        val buf = ByteArray(minBuf)
        try {
            audio.startRecording()
            emitState(level01 = 0f, state = "recording")

            while (!stopped) {
                if (paused) {
                    try {
                        audio.stop()
                    } catch (_: Throwable) {
                    }
                    while (paused && !stopped) {
                        Thread.sleep(80)
                    }
                    if (stopped) break
                    runCatching { audio.startRecording() }
                    continue
                }

                val n = audio.read(buf, 0, buf.size)
                if (n <= 0) {
                    Thread.sleep(10)
                    continue
                }

                val level = estimatePeakLevel01(buf, n)
                writer.offerPcm(java.nio.ByteBuffer.wrap(buf, 0, n))
                emitState(level01 = level, state = "recording")
            }
        } catch (t: Throwable) {
            failedStop = true
            markFailed(code = "RecordFailed", message = t.message ?: "record failed")
        } finally {
            try {
                audio.stop()
            } catch (_: Throwable) {
            }
            try {
                audio.release()
            } catch (_: Throwable) {
            }

            val elapsed = elapsedMs()
            writer.stop(finalState = finalState())
            try {
                val raw = ws.readTextFile(ref.metaPath, maxBytes = 2L * 1024L * 1024L)
                val prev = RecordingMetaV1.parse(raw)
                val next =
                    prev.copy(
                        updatedAt = RecordingMetaV1.nowIso(),
                        state = finalState(),
                        durationMs = elapsed,
                        sampleRate = sampleRateHz,
                        bitrate = bitrateBps,
                    )
                store.writeSessionMeta(sessionId, next)
            } catch (_: Throwable) {
            }
            emitState(level01 = 0f, state = finalState())
            releaseWakeLock()
            notifyStoppedOnce()
        }
    }

    private fun finalState(): String {
        return when {
            failedStop -> "failed"
            cancelledStop -> "cancelled"
            else -> "completed"
        }
    }

    private fun notifyStoppedOnce() {
        if (!notifiedStopped.compareAndSet(false, true)) return
        onStopped(sessionId)
    }

    private fun elapsedMs(): Long {
        val now = android.os.SystemClock.elapsedRealtime()
        val base = (now - startRealtimeMs).coerceAtLeast(0L)
        val pauseExtra = pauseStartMs?.let { ps -> (now - ps).coerceAtLeast(0L) } ?: 0L
        return (base - pausedAccumMs - pauseExtra).coerceAtLeast(0L)
    }

    private fun emitState(level01: Float, state: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastEmitMs != 0L && (now - lastEmitMs) < 160) return
        lastEmitMs = now
        onState(
            RecorderRuntimeState(
                sessionId = sessionId,
                state = state,
                elapsedMs = elapsedMs(),
                level01 = level01.coerceIn(0f, 1f),
            ),
        )
    }

    private fun estimatePeakLevel01(buf: ByteArray, n: Int): Float {
        if (n < 2) return 0f
        var peak = 0
        var i = 0
        while (i + 1 < n) {
            val lo = buf[i].toInt() and 0xff
            val hi = buf[i + 1].toInt()
            val v = (hi shl 8) or lo
            peak = maxOf(peak, abs(v))
            i += 2
        }
        return (peak / 32768f).coerceIn(0f, 1f)
    }

    private fun acquireWakeLock() {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kotlin-agent-app:recorder:$sessionId")
            wl.setReferenceCounted(false)
            wl.acquire(6 * 60 * 60 * 1000L)
            wakeLock = wl
        } catch (_: Throwable) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    private fun markFailed(code: String, message: String) {
        try {
            if (!ws.exists(ref.metaPath)) return
            val raw = ws.readTextFile(ref.metaPath, maxBytes = 2L * 1024L * 1024L)
            val prev = RecordingMetaV1.parse(raw)
            val next =
                prev.copy(
                    state = "failed",
                    updatedAt = RecordingMetaV1.nowIso(),
                    error = RecordingMetaV1.ErrorInfo(code = code, message = message),
                )
            store.writeSessionMeta(sessionId, next)
        } catch (_: Throwable) {
        }
    }
}
