package com.lsl.kotlin_agent_app.agent.tools.irc

internal object IrcTruncation {
    const val MARKER_TEXT: String = "[...TRUNCATED...]"
    const val MAX_SINGLE_MESSAGE_CHARS: Int = 512
    const val MAX_TOTAL_TEXT_CHARS: Int = 8_000

    fun truncateMessageText(text: String): String {
        val t = text.replace("\r\n", "\n")
        if (t.length <= MAX_SINGLE_MESSAGE_CHARS) return t
        val marker = "\n…(truncated)…\n"
        val remaining = (MAX_SINGLE_MESSAGE_CHARS - marker.length).coerceAtLeast(0)
        val headLen = remaining / 2
        val tailLen = remaining - headLen
        return t.take(headLen) + marker + t.takeLast(tailLen)
    }

    data class TruncateBatchResult(
        val messages: List<IrcInboundMessage>,
        val truncated: Boolean,
    )

    fun truncateBatch(messages: List<IrcInboundMessage>): TruncateBatchResult {
        if (messages.isEmpty()) return TruncateBatchResult(messages = messages, truncated = false)
        val total = messages.sumOf { it.text.length }
        if (total <= MAX_TOTAL_TEXT_CHARS) return TruncateBatchResult(messages = messages, truncated = false)

        val headCount = minOf(10, messages.size)
        val tailCount = minOf(10, (messages.size - headCount).coerceAtLeast(0))
        val head = messages.take(headCount)
        val tail = if (tailCount > 0) messages.takeLast(tailCount) else emptyList()

        val marker =
            IrcInboundMessage(
                id = "truncation_marker",
                tsMs = tail.lastOrNull()?.tsMs ?: head.last().tsMs,
                channel = head.last().channel,
                nick = "",
                text = MARKER_TEXT,
                seq = tail.lastOrNull()?.seq ?: head.last().seq,
            )

        val out = if (tail.isNotEmpty()) head + marker + tail else head + marker
        return TruncateBatchResult(messages = out, truncated = true)
    }
}

