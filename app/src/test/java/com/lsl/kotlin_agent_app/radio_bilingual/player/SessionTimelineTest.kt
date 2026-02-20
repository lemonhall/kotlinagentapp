package com.lsl.kotlin_agent_app.radio_bilingual.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTimelineTest {

    @Test
    fun locate_mapsTotalToChunkPosition() {
        val tl = SessionTimeline(chunkDurationsMs = listOf(1000L, 2000L, 3000L))

        assertEquals(SessionTimeline.ChunkPosition(0, 0L), tl.locate(0L))
        assertEquals(SessionTimeline.ChunkPosition(0, 999L), tl.locate(999L))
        assertEquals(SessionTimeline.ChunkPosition(1, 0L), tl.locate(1000L))
        assertEquals(SessionTimeline.ChunkPosition(1, 1999L), tl.locate(2999L))
        assertEquals(SessionTimeline.ChunkPosition(2, 0L), tl.locate(3000L))
        assertEquals(SessionTimeline.ChunkPosition(2, 3000L), tl.locate(6000L))
        assertEquals(SessionTimeline.ChunkPosition(2, 3000L), tl.locate(999999L))
    }

    @Test
    fun toTotalPositionMs_roundTripsWithLocate() {
        val tl = SessionTimeline(chunkDurationsMs = listOf(1000L, 2000L))

        val total = tl.toTotalPositionMs(chunkIndex = 1, positionInChunkMs = 123L)
        assertEquals(1123L, total)
        assertEquals(SessionTimeline.ChunkPosition(1, 123L), tl.locate(total))
    }
}

