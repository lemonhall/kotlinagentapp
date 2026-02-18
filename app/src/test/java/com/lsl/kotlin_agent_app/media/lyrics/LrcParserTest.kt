package com.lsl.kotlin_agent_app.media.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LrcParserTest {

    @Test
    fun parseTimedLinesOrNull_null_returnsNull() {
        assertNull(LrcParser.parseTimedLinesOrNull(null))
    }

    @Test
    fun parseTimedLinesOrNull_plainText_returnsNull() {
        assertNull(LrcParser.parseTimedLinesOrNull("hello\nworld"))
    }

    @Test
    fun parseTimedLinesOrNull_parsesMinutesSecondsAndFraction() {
        val lines =
            LrcParser.parseTimedLinesOrNull(
                """
                [00:01.2]a
                [00:02.34]b
                [00:03.456]c
                """.trimIndent()
            )
        assertNotNull(lines)
        assertEquals(listOf(1200L, 2340L, 3456L), lines!!.map { it.timeMs })
        assertEquals(listOf("a", "b", "c"), lines.map { it.text })
    }

    @Test
    fun parseTimedLinesOrNull_multipleTagsOnOneLine_duplicatesTextWithDifferentTimes() {
        val lines =
            LrcParser.parseTimedLinesOrNull(
                "[00:01.00][00:02.00]hello"
            )!!
        assertEquals(2, lines.size)
        assertEquals(listOf(1000L, 2000L), lines.map { it.timeMs })
        assertEquals(listOf("hello", "hello"), lines.map { it.text })
    }
}

