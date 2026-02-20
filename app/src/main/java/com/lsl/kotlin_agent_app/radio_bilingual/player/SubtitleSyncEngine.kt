package com.lsl.kotlin_agent_app.radio_bilingual.player

internal object SubtitleSyncEngine {

    data class SubtitleSegment(
        val id: Int,
        val totalStartMs: Long,
        val totalEndMs: Long,
        val sourceText: String,
        val translatedText: String?,
        val emotion: String?,
    )

    /**
     * Returns the index of the segment that should be highlighted for [totalPositionMs].
     *
     * - Segments must be sorted by [SubtitleSegment.totalStartMs].
     * - If [totalPositionMs] falls into a gap, apply a ±[toleranceMs] fuzzy match:
     *   - prefer the segment whose (end/start) is closest within tolerance.
     */
    fun findCurrentSegmentIndex(
        segments: List<SubtitleSegment>,
        totalPositionMs: Long,
        toleranceMs: Long = 200L,
    ): Int {
        if (segments.isEmpty()) return -1
        val pos = totalPositionMs.coerceAtLeast(0L)
        val tol = toleranceMs.coerceAtLeast(0L)

        var lo = 0
        var hi = segments.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = segments[mid]
            if (s.totalStartMs <= pos) lo = mid + 1 else hi = mid - 1
        }
        val cand = (lo - 1).coerceIn(0, segments.lastIndex)
        val c = segments[cand]

        if (pos < c.totalEndMs + tol) return cand

        val next = cand + 1
        if (next <= segments.lastIndex) {
            val n = segments[next]
            if (n.totalStartMs - pos <= tol) return next
        }

        if (cand == 0 && pos < c.totalStartMs && c.totalStartMs - pos <= tol) return 0

        return -1
    }
}
