package com.lsl.kotlin_agent_app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackQueueControllerTest {

    @Test
    fun sequentialLoop_nextPrev_wraps() {
        val q = PlaybackQueueController()
        q.setMode(MusicPlaybackMode.SequentialLoop)
        q.setQueue(listOf("a", "b", "c"), current = "b")
        assertEquals(2, q.manualNextIndex())
        assertEquals(0, q.manualNextIndex())
        assertEquals(2, q.manualPrevIndex())
    }

    @Test
    fun playOnce_onEnded_stops() {
        val q = PlaybackQueueController()
        q.setMode(MusicPlaybackMode.PlayOnce)
        q.setQueue(listOf("a", "b"), current = "a")
        assertNull(q.onEndedNextIndex())
    }

    @Test
    fun repeatOne_onEnded_repeatsSameIndex() {
        val q = PlaybackQueueController()
        q.setMode(MusicPlaybackMode.RepeatOne)
        q.setQueue(listOf("a", "b", "c"), current = "c")
        assertEquals(2, q.onEndedNextIndex())
        assertEquals(2, q.onEndedNextIndex())
    }

    @Test
    fun shuffleLoop_next_avoidsImmediateRepeat_whenSizeGreaterThanOne() {
        val q = PlaybackQueueController(random = kotlin.random.Random(123))
        q.setMode(MusicPlaybackMode.ShuffleLoop)
        q.setQueue(listOf("a", "b"), current = "a")
        val next = q.manualNextIndex()
        assertNotEquals(0, next)
    }

    @Test
    fun shuffleLoop_singleItem_doesNotCrashAndReturnsIndex0() {
        val q = PlaybackQueueController(random = kotlin.random.Random(123))
        q.setMode(MusicPlaybackMode.ShuffleLoop)
        q.setQueue(listOf("a"), current = "a")
        assertEquals(0, q.manualNextIndex())
        assertEquals(0, q.manualPrevIndex())
        assertEquals(0, q.onEndedNextIndex())
    }
}

