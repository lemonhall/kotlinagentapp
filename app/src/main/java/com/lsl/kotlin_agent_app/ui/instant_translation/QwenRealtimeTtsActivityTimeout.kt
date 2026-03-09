package com.lsl.kotlin_agent_app.ui.instant_translation

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

internal class QwenRealtimeTtsActivityTimeout(
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val pollIntervalMs: Long = 100L,
) {
    private val startedAtMs: Long = nowMs()
    @Volatile private var lastActivityAtMs: Long = startedAtMs

    fun markActivity() {
        lastActivityAtMs = nowMs()
    }

    suspend fun awaitDone(
        done: CompletableDeferred<Unit>,
        idleTimeoutMs: Long,
        maxTotalMs: Long,
        timeoutMessage: String = "Qwen realtime TTS timed out",
    ) {
        while (true) {
            if (done.isCompleted) {
                done.await()
                return
            }

            val now = nowMs()
            if (now - lastActivityAtMs > idleTimeoutMs) {
                throw IllegalStateException(timeoutMessage)
            }
            if (maxTotalMs > 0L && now - startedAtMs > maxTotalMs) {
                throw IllegalStateException(timeoutMessage)
            }
            delay(pollIntervalMs)
        }
    }
}
