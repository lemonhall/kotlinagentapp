package com.lsl.kotlin_agent_app.ui.env_editor

data class EnvEntry(
    val key: String,
    val value: String,
)

sealed class EnvLine {
    data class Blank(val raw: String = "") : EnvLine()
    data class Comment(val raw: String) : EnvLine()
    data class Pair(
        val key: String,
        val value: String,
        val quote: Char?,
        val inlineComment: String?,
        val originalRaw: String,
    ) : EnvLine()
    data class Other(val raw: String) : EnvLine()
}

data class EnvDocument(
    val lines: List<EnvLine>,
) {
    fun pairs(): List<EnvLine.Pair> = lines.mapNotNull { it as? EnvLine.Pair }
}

