package com.lsl.kotlin_agent_app.voiceinput

class VoiceInputDraftComposer(
    initialText: String = "",
) {
    private var committed = initialText
    private var preview = initialText

    val committedText: String
        get() = committed

    val previewText: String
        get() = preview

    fun applyPartial(text: String): String {
        val normalized = text.trim()
        preview = if (normalized.isBlank()) committed else appendSegment(committed, normalized)
        return preview
    }

    fun applyFinal(text: String): String {
        val normalized = text.trim()
        if (normalized.isBlank()) {
            preview = committed
            return committed
        }
        committed = appendSegment(committed, normalized)
        preview = committed
        return committed
    }

    private fun appendSegment(prefix: String, addition: String): String {
        if (prefix.isBlank()) return addition
        if (addition.isBlank()) return prefix

        val left = prefix.last()
        val right = addition.first()
        val needsSpace = left.isAsciiWordChar() && right.isAsciiWordChar()
        return if (needsSpace) "$prefix $addition" else prefix + addition
    }

    private fun Char.isAsciiWordChar(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}
