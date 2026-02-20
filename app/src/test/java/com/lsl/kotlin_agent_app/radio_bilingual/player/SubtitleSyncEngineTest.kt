package com.lsl.kotlin_agent_app.radio_bilingual.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleSyncEngineTest {

    @Test
    fun findCurrentSegmentIndex_exactMatch() {
        val segs =
            listOf(
                SubtitleSyncEngine.SubtitleSegment(0, 0, 1000, "a", null, null),
                SubtitleSyncEngine.SubtitleSegment(1, 1000, 2000, "b", null, null),
                SubtitleSyncEngine.SubtitleSegment(2, 2000, 3000, "c", null, null),
            )

        assertEquals(0, SubtitleSyncEngine.findCurrentSegmentIndex(segs, totalPositionMs = 0))
        assertEquals(0, SubtitleSyncEngine.findCurrentSegmentIndex(segs, totalPositionMs = 999))
        assertEquals(1, SubtitleSyncEngine.findCurrentSegmentIndex(segs, totalPositionMs = 1000))
        assertEquals(2, SubtitleSyncEngine.findCurrentSegmentIndex(segs, totalPositionMs = 2500))
    }

    @Test
    fun findCurrentSegmentIndex_fuzzyMatchInGap() {
        val segs =
            listOf(
                SubtitleSyncEngine.SubtitleSegment(0, 0, 1000, "a", null, null),
                SubtitleSyncEngine.SubtitleSegment(1, 1500, 2000, "b", null, null),
            )

        // Gap [1000,1500). Near previous end.
        assertEquals(0, SubtitleSyncEngine.findCurrentSegmentIndex(segs, totalPositionMs = 1100, toleranceMs = 200))
        // Gap, not within tolerance => none.
        assertEquals(-1, SubtitleSyncEngine.findCurrentSegmentIndex(segs, totalPositionMs = 1250, toleranceMs = 200))
        // Near next start.
        assertEquals(1, SubtitleSyncEngine.findCurrentSegmentIndex(segs, totalPositionMs = 1400, toleranceMs = 200))
    }
}
