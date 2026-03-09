package com.lsl.kotlin_agent_app.ui.instant_translation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QwenRealtimeTtsActivityTimeoutTest {
    @Test
    fun awaitDone_doesNotTimeoutWhileAudioActivityKeepsArriving() = runTest {
        val done = CompletableDeferred<Unit>()
        val gate =
            QwenRealtimeTtsActivityTimeout(
                nowMs = { testScheduler.currentTime },
                pollIntervalMs = 100,
            )

        val waiter =
            async {
                gate.awaitDone(
                    done = done,
                    idleTimeoutMs = 5_000,
                    maxTotalMs = 60_000,
                )
            }

        launch {
            advanceTimeBy(4_000)
            gate.markActivity()
            advanceTimeBy(4_000)
            gate.markActivity()
            advanceTimeBy(4_000)
            gate.markActivity()
            advanceTimeBy(4_000)
            done.complete(Unit)
        }

        advanceTimeBy(20_000)
        waiter.await()
        assertTrue(done.isCompleted)
    }

    @Test
    fun awaitDone_timesOutAfterIdleGap() = runTest {
        val done = CompletableDeferred<Unit>()
        val gate =
            QwenRealtimeTtsActivityTimeout(
                nowMs = { testScheduler.currentTime },
                pollIntervalMs = 100,
            )

        val waiter =
            async {
                runCatching {
                    gate.awaitDone(
                        done = done,
                        idleTimeoutMs = 5_000,
                        maxTotalMs = 60_000,
                    )
                }.exceptionOrNull()
            }

        advanceTimeBy(6_000)
        val error = waiter.await()
        assertTrue(error?.message?.contains("timed out") == true)
    }
}
