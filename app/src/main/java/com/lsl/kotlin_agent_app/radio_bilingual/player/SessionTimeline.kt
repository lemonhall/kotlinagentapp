package com.lsl.kotlin_agent_app.radio_bilingual.player

/**
 * A pure mapping between:
 * - total (across all chunks) position in ms
 * - per-chunk position in ms
 *
 * Durations are expected to be >= 0. Unknown duration should be represented as 0.
 */
internal class SessionTimeline(
    chunkDurationsMs: List<Long>,
) {
    val chunkDurationsMs: List<Long> = chunkDurationsMs.map { it.coerceAtLeast(0L) }

    val chunkOffsetsMs: List<Long> =
        buildList {
            var acc = 0L
            for (d in this@SessionTimeline.chunkDurationsMs) {
                add(acc)
                acc += d
            }
        }

    val totalDurationMs: Long = chunkDurationsMs.sum().coerceAtLeast(0L)

    data class ChunkPosition(
        val chunkIndex: Int,
        val positionInChunkMs: Long,
    )

    fun toTotalPositionMs(chunkIndex: Int, positionInChunkMs: Long): Long {
        val idx = chunkIndex.coerceIn(0, (chunkDurationsMs.size - 1).coerceAtLeast(0))
        val chunkDur = chunkDurationsMs.getOrNull(idx) ?: 0L
        val pos = positionInChunkMs.coerceIn(0L, chunkDur)
        val base = chunkOffsetsMs.getOrNull(idx) ?: 0L
        return (base + pos).coerceIn(0L, totalDurationMs)
    }

    fun locate(totalPositionMs: Long): ChunkPosition {
        val n = chunkDurationsMs.size
        if (n <= 0) return ChunkPosition(chunkIndex = 0, positionInChunkMs = 0L)

        val clamped = totalPositionMs.coerceIn(0L, totalDurationMs)

        if (clamped >= totalDurationMs) {
            val lastIdx = (n - 1).coerceAtLeast(0)
            return ChunkPosition(
                chunkIndex = lastIdx,
                positionInChunkMs = chunkDurationsMs.getOrNull(lastIdx)?.coerceAtLeast(0L) ?: 0L,
            )
        }

        var lo = 0
        var hi = n - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val off = chunkOffsetsMs[mid]
            if (off <= clamped) lo = mid + 1 else hi = mid - 1
        }
        val idx = (lo - 1).coerceIn(0, n - 1)
        val pos = (clamped - chunkOffsetsMs[idx]).coerceAtLeast(0L)
        return ChunkPosition(chunkIndex = idx, positionInChunkMs = pos)
    }
}

