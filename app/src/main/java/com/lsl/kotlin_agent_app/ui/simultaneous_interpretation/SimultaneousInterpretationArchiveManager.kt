package com.lsl.kotlin_agent_app.ui.simultaneous_interpretation

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal class SimultaneousInterpretationArchiveManager(
    appContext: Context,
    private val nowProvider: () -> ZonedDateTime = { ZonedDateTime.now(ZoneId.systemDefault()) },
) {
    private val filesDir = appContext.applicationContext.filesDir
    private val archiveRoot = File(filesDir, ".agents/workspace/simultaneous_interpretation")
    private val json = Json { prettyPrint = true; explicitNulls = false }

    private var sessionDir: File? = null
    private var sessionRelPath: String? = null
    private var sessionStartedAt: ZonedDateTime? = null
    private var targetLanguageCode: String = "en"
    private var targetLanguageLabel: String = "英语"
    private var active: Boolean = false
    private var inputOutput: FileOutputStream? = null
    private var translatedPcmOutput: FileOutputStream? = null
    private var translatedPcmFile: File? = null
    private val sourceLines = mutableListOf<String>()
    private val translationLines = mutableListOf<String>()
    private var segmentCount: Int = 0

    @Synchronized
    fun startNewSession(
        targetLanguageCode: String,
        targetLanguageLabel: String,
    ): String {
        finishSession()
        archiveRoot.mkdirs()
        sourceLines.clear()
        translationLines.clear()
        segmentCount = 0
        this.targetLanguageCode = targetLanguageCode.trim().ifBlank { "en" }
        this.targetLanguageLabel = targetLanguageLabel.trim().ifBlank { this.targetLanguageCode }
        this.sessionStartedAt = nowProvider.invoke()

        val dir = uniqueArchiveDir(formatSessionDirName(sessionStartedAt!!))
        dir.mkdirs()
        sessionDir = dir
        sessionRelPath = ".agents/workspace/simultaneous_interpretation/${dir.name}"
        active = true

        File(dir, "events.jsonl").writeText("", Charsets.UTF_8)
        File(dir, "segments.jsonl").writeText("", Charsets.UTF_8)
        File(dir, "source.md").writeText("", Charsets.UTF_8)
        File(dir, "translation.md").writeText("", Charsets.UTF_8)
        inputOutput = FileOutputStream(File(dir, "input_audio.pcm"), true)
        translatedPcmFile = File(dir, "translated_audio.pcm")
        translatedPcmOutput = FileOutputStream(translatedPcmFile!!, true)
        writeMeta(status = "running", errorMessage = null)
        return sessionRelPath!!
    }

    @Synchronized
    fun appendInputAudio(bytes: ByteArray) {
        if (!active || bytes.isEmpty()) return
        inputOutput?.write(bytes)
        inputOutput?.flush()
    }

    @Synchronized
    fun appendOutputAudio(bytes: ByteArray) {
        if (!active || bytes.isEmpty()) return
        translatedPcmOutput?.write(bytes)
        translatedPcmOutput?.flush()
    }

    @Synchronized
    fun appendEvent(
        type: String,
        message: String? = null,
    ) {
        val dir = sessionDir ?: return
        val file = File(dir, "events.jsonl")
        val line =
            buildJsonObject {
                put("tsEpochMs", JsonPrimitive(System.currentTimeMillis()))
                put("type", JsonPrimitive(type.trim()))
                message?.trim()?.takeIf { it.isNotBlank() }?.let {
                    put("message", JsonPrimitive(it))
                }
            }
        appendJsonLine(file, line)
    }

    @Synchronized
    fun appendSegment(
        sourceText: String,
        translatedText: String,
    ) {
        val dir = sessionDir ?: return
        val source = sourceText.trim()
        val translated = translatedText.trim()
        if (source.isBlank() || translated.isBlank()) return
        segmentCount += 1
        sourceLines += source
        translationLines += translated

        appendJsonLine(
            File(dir, "segments.jsonl"),
            buildJsonObject {
                put("id", JsonPrimitive(segmentCount))
                put("sourceText", JsonPrimitive(source))
                put("translatedText", JsonPrimitive(translated))
                put("tsEpochMs", JsonPrimitive(System.currentTimeMillis()))
            },
        )
        File(dir, "source.md").writeText(sourceLines.joinToString("\n\n"), Charsets.UTF_8)
        File(dir, "translation.md").writeText(translationLines.joinToString("\n\n"), Charsets.UTF_8)
        writeMeta(status = if (active) "running" else "completed", errorMessage = null)
    }

    @Synchronized
    fun finishSession(errorMessage: String? = null) {
        runCatching { inputOutput?.flush() }
        runCatching { inputOutput?.close() }
        inputOutput = null
        runCatching { translatedPcmOutput?.flush() }
        runCatching { translatedPcmOutput?.close() }
        translatedPcmOutput = null

        val dir = sessionDir
        val pcmFile = translatedPcmFile
        if (dir != null && pcmFile != null && pcmFile.exists()) {
            val pcmBytes = runCatching { pcmFile.readBytes() }.getOrDefault(byteArrayOf())
            if (pcmBytes.isNotEmpty()) {
                writeMono16BitPcmWaveFile(
                    outputFile = File(dir, "translated_audio.wav"),
                    pcmBytes = pcmBytes,
                    sampleRateHz = LiveTranslateDefaults.OUTPUT_SAMPLE_RATE_HZ,
                )
            }
            runCatching { pcmFile.delete() }
        }

        if (dir != null) {
            writeMeta(
                status = if (errorMessage.isNullOrBlank()) "completed" else "error",
                errorMessage = errorMessage,
            )
        }

        active = false
        sessionDir = null
        sessionRelPath = null
        translatedPcmFile = null
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

    private fun appendJsonLine(
        file: File,
        payload: JsonObject,
    ) {
        FileOutputStream(file, true).use { out ->
            out.write(json.encodeToString(payload).toByteArray(Charsets.UTF_8))
            out.write("\n".toByteArray(Charsets.UTF_8))
        }
    }

    private fun writeMeta(
        status: String,
        errorMessage: String?,
    ) {
        val dir = sessionDir ?: return
        val payload =
            buildJsonObject {
                put("status", JsonPrimitive(status))
                put("targetLanguageCode", JsonPrimitive(targetLanguageCode))
                put("targetLanguageLabel", JsonPrimitive(targetLanguageLabel))
                put("sessionRelativePath", JsonPrimitive(sessionRelPath.orEmpty()))
                put("segmentCount", JsonPrimitive(segmentCount))
                sessionStartedAt?.let { put("startedAt", JsonPrimitive(it.toString())) }
                put("updatedAt", JsonPrimitive(nowProvider.invoke().toString()))
                errorMessage?.trim()?.takeIf { it.isNotBlank() }?.let {
                    put("errorMessage", JsonPrimitive(it))
                }
            }
        File(dir, "meta.json").writeText(json.encodeToString(payload), Charsets.UTF_8)
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

private fun writeMono16BitPcmWaveFile(
    outputFile: File,
    pcmBytes: ByteArray,
    sampleRateHz: Int,
) {
    outputFile.parentFile?.mkdirs()
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    val byteRate = sampleRateHz * 2
    val dataSize = pcmBytes.size
    header.put("RIFF".toByteArray(Charsets.US_ASCII))
    header.putInt(36 + dataSize)
    header.put("WAVE".toByteArray(Charsets.US_ASCII))
    header.put("fmt ".toByteArray(Charsets.US_ASCII))
    header.putInt(16)
    header.putShort(1)
    header.putShort(1)
    header.putInt(sampleRateHz)
    header.putInt(byteRate)
    header.putShort(2)
    header.putShort(16)
    header.put("data".toByteArray(Charsets.US_ASCII))
    header.putInt(dataSize)
    outputFile.outputStream().use { out ->
        out.write(header.array())
        out.write(pcmBytes)
    }
}
