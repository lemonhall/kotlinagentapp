package com.lsl.kotlin_agent_app.ui.env_editor

object EnvParser {

    fun parse(raw: String): EnvDocument {
        val normalized = raw.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split("\n")
        val parsed =
            lines.map { line ->
                parseLine(line)
            }
        return EnvDocument(lines = parsed)
    }

    private fun parseLine(line0: String): EnvLine {
        val raw = line0
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return EnvLine.Blank(raw = raw)
        if (trimmed.startsWith("#")) return EnvLine.Comment(raw = raw)

        val line = raw.trimStart()
        val afterExport =
            if (line.startsWith("export ")) line.removePrefix("export ").trimStart() else line

        val eq = afterExport.indexOf('=')
        if (eq <= 0) return EnvLine.Other(raw = raw)

        val key = afterExport.substring(0, eq).trim()
        if (!keyRx.matches(key)) return EnvLine.Other(raw = raw)

        val rhs = afterExport.substring(eq + 1)
        val (valuePart, inlineComment) = splitInlineComment(rhs)
        val valueTrimmed = valuePart.trim()
        val (value, quote) = unquote(valueTrimmed)

        return EnvLine.Pair(
            key = key,
            value = value,
            quote = quote,
            inlineComment = inlineComment?.takeIf { it.isNotBlank() },
            originalRaw = raw,
        )
    }

    private val keyRx = Regex("^[A-Za-z_][A-Za-z0-9_]*\$")

    private fun splitInlineComment(rhs0: String): Pair<String, String?> {
        var inSingle = false
        var inDouble = false
        var escaped = false
        val rhs = rhs0
        for (i in rhs.indices) {
            val ch = rhs[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                continue
            }
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle
                continue
            }
            if (ch == '"' && !inSingle) {
                inDouble = !inDouble
                continue
            }
            if (ch == '#' && !inSingle && !inDouble) {
                // treat as comment only if preceded by whitespace (common .env convention)
                val prev = rhs.getOrNull(i - 1)
                if (prev == null || prev.isWhitespace()) {
                    return rhs.substring(0, i) to rhs.substring(i).trimEnd()
                }
            }
        }
        return rhs to null
    }

    private fun unquote(v: String): Pair<String, Char?> {
        if (v.length >= 2) {
            val first = v.first()
            val last = v.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                val inner = v.substring(1, v.length - 1)
                return unescape(inner, quote = first) to first
            }
        }
        return v to null
    }

    private fun unescape(s: String, quote: Char): String {
        if (quote != '"') return s
        // best-effort for common escapes inside double quotes
        return buildString(s.length) {
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                if (ch == '\\' && i + 1 < s.length) {
                    val n = s[i + 1]
                    when (n) {
                        'n' -> append('\n')
                        'r' -> append('\r')
                        't' -> append('\t')
                        '\\' -> append('\\')
                        '"' -> append('"')
                        else -> {
                            append(n)
                        }
                    }
                    i += 2
                    continue
                }
                append(ch)
                i++
            }
        }
    }
}
