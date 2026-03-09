package com.lsl.kotlin_agent_app.ui.simultaneous_interpretation

import android.content.Context
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

internal class SimultaneousInterpretationArchiveManager(
    appContext: Context,
    private val nowProvider: () -> ZonedDateTime = { ZonedDateTime.now(ZoneId.systemDefault()) },
) {
    private val filesDir = appContext.applicationContext.filesDir
    private val archiveRoot = File(filesDir, ".agents/workspace/simultaneous_interpretation")

    @Synchronized
    fun startNewSession(
        targetLanguageCode: String,
        targetLanguageLabel: String,
    ): String {
        archiveRoot.mkdirs()
        val dir = uniqueArchiveDir(formatSessionDirName(nowProvider.invoke()))
        dir.mkdirs()
        File(dir, "meta.json").writeText(
            buildString {
                appendLine("{")
                appendLine("  \"status\": \"created\",")
                appendLine("  \"targetLanguageCode\": \"${targetLanguageCode.trim().ifBlank { "en" }}\",")
                appendLine("  \"targetLanguageLabel\": \"${targetLanguageLabel.trim().ifBlank { "英语" }}\"")
                appendLine("}")
            },
            Charsets.UTF_8,
        )
        return ".agents/workspace/simultaneous_interpretation/${dir.name}"
    }

    private fun uniqueArchiveDir(baseName: String): File {
        var suffix = 0
        while (true) {
            val name = if (suffix == 0) baseName else "$baseName-$suffix"
            val dir = File(archiveRoot, name)
            if (!dir.exists()) return dir
            suffix++
        }
    }
}

private fun formatSessionDirName(at: ZonedDateTime): String {
    val partOfDay =
        when (at.hour) {
            in 0..5 -> "凌晨"
            in 6..11 -> "上午"
            12 -> "中午"
            in 13..17 -> "下午"
            else -> "晚"
        }
    return String.format(
        "%04d年%02d月%02d日 %s%02d点%02d分",
        at.year,
        at.monthValue,
        at.dayOfMonth,
        partOfDay,
        at.hour,
        at.minute,
    )
}