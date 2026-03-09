package com.lsl.kotlin_agent_app.ui.instant_translation

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal class InstantTranslationArchiveManager(
    appContext: Context,
    private val nowProvider: () -> ZonedDateTime = { ZonedDateTime.now(ZoneId.systemDefault()) },
) {
    private val filesDir = appContext.applicationContext.filesDir
    private val archiveRoot = File(filesDir, ".agents/workspace/instant_translation")
    private val json = Json { prettyPrint = true; explicitNulls = false }

    private var sessionDir: File? = null
    private var sessionRelPath: String? = null
    private var sampleRateHz: Int = 16_000
    private var targetLanguageCode: String = "en"
    private var targetLanguageLabel: String = "英语"
    private var recordingOutput: FileOutputStream? = null
    private var active: Boolean = false

    @Synchronized
    fun startNewSession(
        targetLanguageCode: String,
        targetLanguageLabel: String,
        sampleRateHz: Int,
    ): String {
        finishSession()
        archiveRoot.mkdirs()

        this.sampleRateHz = sampleRateHz
        this.targetLanguageCode = targetLanguageCode.trim().ifBlank { "en" }
        this.targetLanguageLabel = targetLanguageLabel.trim().ifBlank { this.targetLanguageCode }

        val baseName = formatSessionDirName(nowProvider.invoke())
        val dir = uniqueArchiveDir(baseName)
        dir.mkdirs()

        sessionDir = dir
        sessionRelPath = ".agents/workspace/instant_translation/${dir.name}"
        active = true
        recordingOutput = FileOutputStream(File(dir, "recording.pcm"), true)
        File(dir, "tts").mkdirs()
        writeMeta(status = "recording", errorMessage = null)
        writeTurns(emptyList())
        return sessionRelPath!!
    }

    @Synchronized
    fun appendAudioFrame(bytes: ByteArray) {
        if (!active || bytes.isEmpty()) return
        val output = recordingOutput ?: return
        output.write(bytes)
        output.flush()
    }

    @Synchronized
    fun writeTurns(turns: List<InstantTranslationTurn>) {
        val currentRelPath = sessionRelPath
        if (turns.isEmpty()) {
            currentRelPath?.let { relPath ->
                resolveSessionDir(relPath)?.let { dir ->
                    writeTurnsForSession(
                        dir = dir,
                        sessionRelativePath = relPath,
                        turns = emptyList(),
                    )
                }
            }
            writeMeta(status = if (active) "recording" else "stopped", errorMessage = null)
            return
        }

        val turnsBySession =
            turns.groupBy { turn ->
                turn.archiveSessionRelativePath?.trim()?.ifBlank { currentRelPath } ?: currentRelPath
            }

        turnsBySession.forEach { (relPath, sessionTurns) ->
            if (relPath == null) return@forEach
            val dir = resolveSessionDir(relPath) ?: return@forEach
            writeTurnsForSession(
                dir = dir,
                sessionRelativePath = relPath,
                turns = sessionTurns.sortedBy { it.id },
            )
        }

        if (currentRelPath != null && turnsBySession[currentRelPath] == null) {
            resolveSessionDir(currentRelPath)?.let { dir ->
                writeTurnsForSession(
                    dir = dir,
                    sessionRelativePath = currentRelPath,
                    turns = emptyList(),
                )
            }
        }

        writeMeta(status = if (active) "recording" else "stopped", errorMessage = null)
    }

    @Synchronized
    fun prepareTtsOutputFiles(
        turn: InstantTranslationTurn,
        audioExtension: String = "wav",
    ): InstantTranslationTtsArchiveFiles? {
        val dir = resolveSessionDir(turn.archiveSessionRelativePath ?: sessionRelPath) ?: return null
        val ttsDir = File(dir, "tts")
        if (!ttsDir.exists()) ttsDir.mkdirs()

        val baseName = "turn-${turn.id.toString().padStart(4, '0')}"
        val textFile = File(ttsDir, "$baseName.txt")
        val audioFile = File(ttsDir, "$baseName.$audioExtension")
        textFile.writeText(turn.translatedText.trim() + "\n", Charsets.UTF_8)
        return InstantTranslationTtsArchiveFiles(
            textFile = textFile,
            audioFile = audioFile,
            textRelativePath = "tts/${textFile.name}",
            audioRelativePath = "tts/${audioFile.name}",
        )
    }

    @Synchronized
    fun finishSession(errorMessage: String? = null) {
        runCatching { recordingOutput?.flush() }
        runCatching { recordingOutput?.close() }
        recordingOutput = null
        if (sessionDir != null) {
            writeMeta(status = "stopped", errorMessage = errorMessage)
        }
        active = false
    }

    @Synchronized
    fun currentSessionRelativePath(): String? = sessionRelPath

    private fun writeTurnsForSession(
        dir: File,
        sessionRelativePath: String,
        turns: List<InstantTranslationTurn>,
    ) {
        val turnsFile = File(dir, "turns.json")
        val transcriptFile = File(dir, "transcript.md")
        val obj =
            buildJsonObject {
                put("kind", JsonPrimitive("instant_translation_turns"))
                put("turns", buildJsonArray {
                    for (turn in turns) {
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(turn.id))
                                put("sourceText", JsonPrimitive(turn.sourceText))
                                put("targetLanguageCode", JsonPrimitive(turn.targetLanguageCode))
                                put("targetLanguageLabel", JsonPrimitive(turn.targetLanguageLabel))
                                put("translatedText", JsonPrimitive(turn.translatedText))
                                put("isPending", JsonPrimitive(turn.isPending))
                                put("archiveSessionRelativePath", JsonPrimitive(turn.archiveSessionRelativePath ?: sessionRelativePath))
                                val ttsArtifacts = resolveExistingTtsArtifacts(dir = dir, turnId = turn.id)
                                if (ttsArtifacts?.textFile?.exists() == true) {
                                    put("ttsTextFile", JsonPrimitive(ttsArtifacts.textRelativePath))
                                }
                                if (ttsArtifacts?.audioFile?.exists() == true) {
                                    put("ttsAudioFile", JsonPrimitive(ttsArtifacts.audioRelativePath))
                                }
                            },
                        )
                    }
                })
            }
        turnsFile.writeText(json.encodeToString(JsonObject.serializer(), obj) + "\n", Charsets.UTF_8)
        transcriptFile.writeText(renderTranscriptMarkdown(dir = dir, turns = turns), Charsets.UTF_8)
    }

    private fun resolveSessionDir(relativePath: String?): File? {
        val rel = relativePath?.replace('\\', '/')?.trim()?.trimStart('/')?.ifBlank { null } ?: return null
        return File(filesDir, rel)
    }

    private fun uniqueArchiveDir(baseName: String): File {
        var index = 1
        var candidate = File(archiveRoot, baseName)
        while (candidate.exists()) {
            index += 1
            candidate = File(archiveRoot, "$baseName-$index")
        }
        return candidate
    }

    private fun renderTranscriptMarkdown(
        dir: File,
        turns: List<InstantTranslationTurn>,
    ): String {
        val sessionLanguageCode = turns.firstOrNull()?.targetLanguageCode ?: targetLanguageCode
        val sessionLanguageLabel = turns.firstOrNull()?.targetLanguageLabel ?: targetLanguageLabel
        return buildString {
            appendLine("# 即时翻译记录")
            appendLine()
            appendLine("- 目标语言：$sessionLanguageLabel ($sessionLanguageCode)")
            appendLine("- 采样率：${sampleRateHz}Hz")
            appendLine()
            if (turns.isEmpty()) {
                appendLine("暂无翻译片段。")
            } else {
                for (turn in turns) {
                    val ttsArtifacts = resolveExistingTtsArtifacts(dir = dir, turnId = turn.id)
                    appendLine("## 片段 ${turn.id}")
                    appendLine()
                    appendLine("- 原文：${turn.sourceText}")
                    appendLine("- 译文：${turn.translatedText.ifBlank { "（空）" }}")
                    appendLine("- 目标语言：${turn.targetLanguageLabel} (${turn.targetLanguageCode})")
                    appendLine("- 状态：${if (turn.isPending) "pending" else "done"}")
                    if (ttsArtifacts?.audioFile?.exists() == true) {
                        appendLine("- TTS 音频：${ttsArtifacts.audioRelativePath}")
                    }
                    appendLine()
                }
            }
        }
    }

    private fun resolveExistingTtsArtifacts(
        dir: File,
        turnId: Long,
    ): InstantTranslationTtsArchiveFiles? {
        val ttsDir = File(dir, "tts")
        if (!ttsDir.exists()) return null
        val baseName = "turn-${turnId.toString().padStart(4, '0')}"
        val textFile = File(ttsDir, "$baseName.txt")
        val audioFile =
            listOf("wav", "pcm", "mp3", "opus")
                .asSequence()
                .map { ext -> File(ttsDir, "$baseName.$ext") }
                .firstOrNull { it.exists() }
                ?: File(ttsDir, "$baseName.wav")
        return InstantTranslationTtsArchiveFiles(
            textFile = textFile,
            audioFile = audioFile,
            textRelativePath = "tts/${textFile.name}",
            audioRelativePath = "tts/${audioFile.name}",
        )
    }

    private fun writeMeta(
        status: String,
        errorMessage: String?,
    ) {
        val dir = sessionDir ?: return
        val obj =
            buildJsonObject {
                put("kind", JsonPrimitive("instant_translation_session"))
                put("status", JsonPrimitive(status))
                put("updatedAt", JsonPrimitive(nowProvider.invoke().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
                put("targetLanguageCode", JsonPrimitive(targetLanguageCode))
                put("targetLanguageLabel", JsonPrimitive(targetLanguageLabel))
                put("sampleRateHz", JsonPrimitive(sampleRateHz))
                put("recordingFile", JsonPrimitive("recording.pcm"))
                put("turnsFile", JsonPrimitive("turns.json"))
                put("transcriptFile", JsonPrimitive("transcript.md"))
                put("ttsDir", JsonPrimitive("tts"))
                if (!errorMessage.isNullOrBlank()) {
                    put("errorMessage", JsonPrimitive(errorMessage))
                }
            }
        File(dir, "meta.json").writeText(json.encodeToString(JsonObject.serializer(), obj) + "\n", Charsets.UTF_8)
    }
}

internal data class InstantTranslationTtsArchiveFiles(
    val textFile: File,
    val audioFile: File,
    val textRelativePath: String,
    val audioRelativePath: String,
)

internal fun formatSessionDirName(at: ZonedDateTime): String {
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
