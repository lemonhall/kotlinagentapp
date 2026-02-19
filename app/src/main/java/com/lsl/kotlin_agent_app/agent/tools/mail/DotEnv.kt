package com.lsl.kotlin_agent_app.agent.tools.mail

import java.io.File

internal object DotEnv {
    fun parse(text: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        text
            .replace("\r\n", "\n")
            .split('\n')
            .forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isBlank() || line.startsWith("#")) return@forEach
                val idx = line.indexOf('=')
                if (idx <= 0) return@forEach
                val key = line.substring(0, idx).trim()
                var value = line.substring(idx + 1).trim()
                if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
                    value = value.substring(1, value.length - 1)
                }
                out[key] = value
            }
        return out
    }

    fun load(file: File): Map<String, String> {
        if (!file.exists() || !file.isFile) return emptyMap()
        val text =
            try {
                file.readText(Charsets.UTF_8)
            } catch (_: Throwable) {
                ""
            }
        if (text.isBlank()) return emptyMap()
        return parse(text)
    }
}
