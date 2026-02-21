package com.lsl.kotlin_agent_app.ui.env_editor

object EnvRenderer {

    fun render(
        doc: EnvDocument,
        updatedPairs: List<EnvEntry>,
    ): String {
        val updatedByKey = linkedMapOf<String, EnvEntry>()
        for (e in updatedPairs) {
            val k = e.key.trim()
            if (k.isBlank()) continue
            updatedByKey[k] = EnvEntry(key = k, value = e.value)
        }

        val usedKeys = linkedSetOf<String>()
        val out = StringBuilder()

        for (line in doc.lines) {
            when (line) {
                is EnvLine.Blank -> out.appendLine(line.raw)
                is EnvLine.Comment -> out.appendLine(line.raw)
                is EnvLine.Other -> out.appendLine(line.raw)
                is EnvLine.Pair -> {
                    val upd = updatedByKey[line.key]
                    if (upd != null) {
                        usedKeys.add(line.key)
                        out.appendLine(renderPair(line, upd.value))
                    } else {
                        out.appendLine(line.originalRaw)
                    }
                }
            }
        }

        val newOnes =
            updatedPairs.filter { e ->
                val k = e.key.trim()
                k.isNotBlank() && !usedKeys.contains(k) && doc.pairs().none { it.key == k }
            }
        if (newOnes.isNotEmpty()) {
            if (out.isNotEmpty() && out[out.length - 1] != '\n') out.appendLine()
            out.appendLine("# Added by kotlin-agent-app")
            for (e in newOnes) {
                out.appendLine("${e.key.trim()}=${quoteIfNeeded(e.value)}")
            }
        }

        return out.toString().trimEnd() + "\n"
    }

    private fun renderPair(
        original: EnvLine.Pair,
        newValue: String,
    ): String {
        val key = original.key
        val value =
            when (original.quote) {
                '"' -> "\"" + escapeDoubleQuoted(newValue) + "\""
                '\'' -> "'" + newValue.replace("'", "\\'") + "'"
                else -> quoteIfNeeded(newValue)
            }
        val ic = original.inlineComment?.trim()?.ifBlank { null }
        return buildString {
            append(key)
            append('=')
            append(value)
            if (ic != null) {
                if (!ic.startsWith("#")) append(' ')
                append(' ')
                append(ic)
            }
        }.trimEnd()
    }

    private fun quoteIfNeeded(value: String): String {
        val v = value
        if (v.isEmpty()) return ""
        val needs = v.any { it.isWhitespace() } || v.contains('#') || v.contains('"') || v.contains('\\')
        return if (!needs) v else "\"" + escapeDoubleQuoted(v) + "\""
    }

    private fun escapeDoubleQuoted(value: String): String {
        return buildString(value.length) {
            for (ch in value) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
    }
}
