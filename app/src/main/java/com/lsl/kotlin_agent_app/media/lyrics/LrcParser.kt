package com.lsl.kotlin_agent_app.media.lyrics

data class LrcLine(
    val timeMs: Long,
    val text: String,
)

object LrcParser {
    private val timeTagRx = Regex("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?\\]")

    fun parseTimedLinesOrNull(raw: String?): List<LrcLine>? {
        val src = raw?.trim()?.ifBlank { null } ?: return null
        val result = mutableListOf<LrcLine>()

        src.lineSequence().forEach { line0 ->
            val line = line0.trimEnd()
            if (line.isBlank()) return@forEach
            val matches = timeTagRx.findAll(line).toList()
            if (matches.isEmpty()) return@forEach

            val text = line.replace(timeTagRx, "").trim().ifBlank { "" }
            for (m in matches) {
                val mm = m.groupValues[1].toIntOrNull() ?: continue
                val ss = m.groupValues[2].toIntOrNull() ?: continue
                val fracRaw = m.groupValues.getOrNull(3).orEmpty()
                val fracMs =
                    when (fracRaw.length) {
                        0 -> 0
                        1 -> fracRaw.toIntOrNull()?.let { it * 100 } ?: 0
                        2 -> fracRaw.toIntOrNull()?.let { it * 10 } ?: 0
                        else -> fracRaw.take(3).toIntOrNull() ?: 0
                    }
                val t = (mm * 60_000L) + (ss * 1000L) + fracMs
                if (t >= 0L) result.add(LrcLine(timeMs = t, text = text))
            }
        }

        if (result.isEmpty()) return null
        return result.sortedWith(compareBy<LrcLine> { it.timeMs }.thenBy { it.text })
    }
}

