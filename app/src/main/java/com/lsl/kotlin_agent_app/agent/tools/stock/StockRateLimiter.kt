package com.lsl.kotlin_agent_app.agent.tools.stock

import java.util.ArrayDeque

internal fun interface StockClock {
    fun nowMs(): Long
}

internal data class StockRateLimitDecision(
    val allowed: Boolean,
    val retryAfterMs: Long?,
)

internal class StockRateLimiter(
    private val clock: StockClock = StockClock { System.currentTimeMillis() },
    private val maxRequestsPerWindow: Int = 55,
    private val windowMs: Long = 60_000L,
) {
    private val timestamps: ArrayDeque<Long> = ArrayDeque()

    @Synchronized
    fun tryAcquire(): StockRateLimitDecision {
        val now = clock.nowMs()
        while (timestamps.isNotEmpty() && now - (timestamps.peekFirst() ?: now) >= windowMs) {
            timestamps.removeFirst()
        }
        if (timestamps.size < maxRequestsPerWindow) {
            timestamps.addLast(now)
            return StockRateLimitDecision(allowed = true, retryAfterMs = null)
        }
        val oldest = timestamps.peekFirst() ?: now
        val retryAfter = (windowMs - (now - oldest)).coerceAtLeast(1L)
        return StockRateLimitDecision(allowed = false, retryAfterMs = retryAfter)
    }
}
