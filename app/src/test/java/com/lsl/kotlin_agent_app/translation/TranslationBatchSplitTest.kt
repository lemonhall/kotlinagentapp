package com.lsl.kotlin_agent_app.translation

import com.lsl.kotlin_agent_app.radio_transcript.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationBatchSplitTest {

    @Test
    fun splitByApproxChars_preservesOrder_andSplitsWhenSmallCap() {
        val segments =
            (0 until 10).map { i ->
                val text = "x".repeat(if (i % 2 == 0) 50 else 200)
                TranscriptSegment(id = i, startMs = (i * 1000).toLong(), endMs = (i * 1000 + 999).toLong(), text = text, emotion = null)
            }

        val batches = TranslationBatchSplitter.splitByApproxChars(segments, maxChars = 300)
        assertTrue(batches.size >= 2)
        assertEquals(segments.map { it.id }, batches.flatten().map { it.id })
        assertTrue(batches.all { it.isNotEmpty() })
    }
}

