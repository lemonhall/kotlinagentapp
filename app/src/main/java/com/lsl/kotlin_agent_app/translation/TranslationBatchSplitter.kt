package com.lsl.kotlin_agent_app.translation

import com.lsl.kotlin_agent_app.radio_transcript.TranscriptSegment

internal object TranslationBatchSplitter {
    fun splitByApproxChars(
        segments: List<TranscriptSegment>,
        maxChars: Int,
    ): List<List<TranscriptSegment>> {
        val cap = maxChars.coerceAtLeast(256)
        if (segments.isEmpty()) return emptyList()

        val out = ArrayList<List<TranscriptSegment>>()
        var cur = ArrayList<TranscriptSegment>()
        var curChars = 0

        fun flush() {
            if (cur.isEmpty()) return
            out.add(cur)
            cur = ArrayList()
            curChars = 0
        }

        for (s in segments) {
            val text = s.text.trim()
            val cost = (text.length + 64).coerceAtLeast(1)
            if (cur.isNotEmpty() && curChars + cost > cap) flush()
            cur.add(s)
            curChars += cost
        }
        flush()
        return out
    }
}

