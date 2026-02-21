package com.lsl.kotlin_agent_app.ui.qqmail

internal data class QqMailMarkdownParsed(
    val frontMatter: Map<String, String>,
    val bodyMarkdown: String,
)

internal object QqMailMarkdown {

    fun parse(text: String): QqMailMarkdownParsed {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("---\n") && !trimmed.startsWith("---\r\n")) {
            return QqMailMarkdownParsed(frontMatter = emptyMap(), bodyMarkdown = text.trimEnd())
        }

        val lines = text.split("\n")
        var i = 0
        if (!lines.getOrNull(i).orEmpty().trim().equals("---", ignoreCase = false)) {
            return QqMailMarkdownParsed(frontMatter = emptyMap(), bodyMarkdown = text.trimEnd())
        }
        i += 1

        val fm = linkedMapOf<String, String>()
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trimEnd()
            if (t.trim() == "---") {
                i += 1
                break
            }
            val idx = t.indexOf(':')
            if (idx > 0) {
                val key = t.substring(0, idx).trim()
                var value = t.substring(idx + 1).trim()
                if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
                    value = value.substring(1, value.length - 1)
                    value =
                        value
                            .replace("\\n", "\n")
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\")
                }
                if (key.isNotBlank()) fm[key] = value
            }
            i += 1
        }

        val body = lines.drop(i).joinToString("\n").trimEnd()
        return QqMailMarkdownParsed(frontMatter = fm, bodyMarkdown = body)
    }
}

