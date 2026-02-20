package com.lsl.kotlin_agent_app.radio_transcript

import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.asr.AsrException
import com.lsl.kotlin_agent_app.asr.AsrNetworkError
import com.lsl.kotlin_agent_app.asr.AsrParseError
import com.lsl.kotlin_agent_app.asr.AsrRemoteError
import com.lsl.kotlin_agent_app.asr.AsrTaskTimeout
import com.lsl.kotlin_agent_app.asr.AsrUploadError
import com.lsl.kotlin_agent_app.asr.CloudAsrClient
import com.lsl.kotlin_agent_app.radio_recordings.RadioRecordingsStore
import com.lsl.kotlin_agent_app.radio_recordings.RecordingMetaV1
import com.lsl.kotlin_agent_app.recordings.RecordingSessionResolver
import com.lsl.kotlin_agent_app.radio_translation.TranslationWorker
import com.lsl.kotlin_agent_app.translation.LlmNetworkError
import com.lsl.kotlin_agent_app.translation.LlmParseError
import com.lsl.kotlin_agent_app.translation.LlmRemoteError
import com.lsl.kotlin_agent_app.translation.TranslatedSegment
import com.lsl.kotlin_agent_app.translation.TranslationClient
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class RecordingPipelineException(
    val errorCode: String,
    message: String,
) : IllegalArgumentException(message)

internal class RecordingPipeline(
    private val ws: AgentsWorkspace,
    private val store: RadioRecordingsStore,
    private val asrClient: CloudAsrClient,
    private val translationClientFactory: (targetLanguage: String) -> TranslationClient,
    private val nowIso: () -> String = RecordingMetaV1.Companion::nowIso,
) {
    private val prettyJson = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true }

    suspend fun run(
        sessionId: String,
        targetLanguageOverride: String? = null,
        shouldStop: () -> Boolean = { false },
    ) {
        val sid = sessionId.trim()
        if (sid.isBlank()) throw RecordingPipelineException("InvalidArgs", "missing sessionId")

        val ref = RecordingSessionResolver.resolve(ws, sid) ?: throw RecordingPipelineException("SessionNotFound", "session not found: $sid")

        val rawMeta =
            withContext(Dispatchers.IO) {
                ws.readTextFile(ref.metaPath, maxBytes = 2L * 1024L * 1024L)
            }
        val meta = RecordingMetaV1.parse(rawMeta)
        val state = meta.state.trim().lowercase(Locale.ROOT)
        if (state == "recording" || state == "pending") {
            throw RecordingPipelineException("SessionStillRecording", "session $sid is still recording. Stop recording first.")
        }
        val chunks = meta.chunks.sortedBy { it.index }.filter { it.file.trim().endsWith(".ogg", ignoreCase = true) }
        if (chunks.isEmpty()) throw RecordingPipelineException("SessionNoChunks", "session $sid has no chunks")

        val existingPipe = meta.pipeline
        val override = targetLanguageOverride?.trim()?.ifBlank { null }
        val initialTarget = override ?: existingPipe?.targetLanguage?.trim()?.ifBlank { null }
        val pipe0 =
            (existingPipe ?: RecordingMetaV1.Pipeline())
                .copy(
                    targetLanguage = initialTarget,
                    transcriptState = existingPipe?.transcriptState?.ifBlank { "pending" } ?: "pending",
                    translationState = existingPipe?.translationState?.ifBlank { "pending" } ?: "pending",
                )

        if (pipe0.transcriptState == "running" || pipe0.translationState == "running") {
            throw RecordingPipelineException("PipelineAlreadyRunning", "pipeline already running for session: $sid")
        }

        writePipeline(meta, pipe0.copy(transcriptState = "running", lastError = null))

        val transcriptsDir = ref.transcriptsDir
        ws.mkdir(transcriptsDir)

        var pipe = pipe0.copy(transcriptState = "running", translationState = if (pipe0.targetLanguage != null) "pending" else "pending")
        var transcribed =
            chunks.count { c ->
                val idx = c.index.coerceAtLeast(1)
                val outPath = "$transcriptsDir/chunk_${idx.toString().padStart(3, '0')}.transcript.json"
                ws.exists(outPath)
            }
        var failed = 0
        pipe = pipe.copy(transcribedChunks = transcribed, failedChunks = failed)
        writePipeline(meta, pipe)

        for (c in chunks) {
            if (shouldStop()) return
            val idx = c.index.coerceAtLeast(1)
            val outPath = "$transcriptsDir/chunk_${idx.toString().padStart(3, '0')}.transcript.json"
            if (ws.exists(outPath)) {
                continue
            }

            val oggPath = ref.chunkOggPath(idx)
            val f = ws.toFile(oggPath)
            val asr =
                try {
                    asrClient.transcribe(audioFile = f, mimeType = "audio/ogg", language = null)
                } catch (t: AsrException) {
                    failed += 1
                    val code = mapAsrErrorCode(t)
                    val msg = mapAsrErrorMessage(t)
                    val next =
                        pipe.copy(
                            transcriptState = "failed",
                            failedChunks = failed,
                            lastError = RecordingMetaV1.ErrorInfo(code = code, message = msg),
                        )
                    writePipeline(meta, next)
                    return
                }

            val chunk =
                TranscriptChunkV1(
                    schema = TranscriptChunkV1.SCHEMA_V1,
                    sessionId = sid,
                    chunkIndex = idx,
                    detectedLanguage = asr.detectedLanguage,
                    segments =
                        asr.segments.map { s ->
                            TranscriptSegment(id = s.id, startMs = s.startMs, endMs = s.endMs, text = s.text, emotion = s.emotion)
                        },
                )
            val rawOut = prettyJson.encodeToString(JsonObject.serializer(), chunk.toJsonObject()) + "\n"
            ws.writeTextFileAtomic(outPath, rawOut)

            transcribed = (transcribed + 1).coerceAtMost(chunks.size)
            pipe = pipe.copy(transcribedChunks = transcribed, failedChunks = failed)
            writePipeline(meta, pipe.copy(transcriptState = "running"))
        }

        pipe = pipe.copy(transcriptState = "completed", transcribedChunks = chunks.size, failedChunks = failed)
        writePipeline(meta, pipe)

        val targetLanguage = pipe.targetLanguage?.trim()?.ifBlank { null }
        if (targetLanguage == null) {
            val done = pipe.copy(translationState = "completed", translatedChunks = 0)
            writePipeline(meta, done)
            return
        }

        writePipeline(meta, pipe.copy(translationState = "running", lastError = null))

        val translator = TranslationWorker(ws)
        var translatedChunks = 0
        var ctx = emptyList<TranslatedSegment>()
        val client =
            try {
                translationClientFactory(targetLanguage)
            } catch (t: Throwable) {
                failed += 1
                val next =
                    pipe.copy(
                        translationState = "failed",
                        failedChunks = failed,
                        lastError = RecordingMetaV1.ErrorInfo(code = "InvalidArgs", message = "translation client not configured: ${t.message ?: "unknown"}"),
                    )
                writePipeline(meta, next)
                return
            }
        for (c in chunks) {
            if (shouldStop()) return
            val idx = c.index.coerceAtLeast(1)
            val outPath = ref.translationChunkPath(idx)
            if (ws.exists(outPath)) {
                translatedChunks += 1
                val translatedSegs =
                    runCatching {
                        val rawT = ws.readTextFile(outPath, maxBytes = 8L * 1024L * 1024L)
                        val ch = com.lsl.kotlin_agent_app.radio_translation.TranslationChunkV1.parse(rawT)
                        ch.segments.map { s ->
                            TranslatedSegment(
                                id = s.id,
                                startMs = s.startMs,
                                endMs = s.endMs,
                                sourceText = s.sourceText,
                                translatedText = s.translatedText,
                                emotion = s.emotion,
                            )
                        }
                    }.getOrNull().orEmpty()
                if (translatedSegs.isNotEmpty()) ctx = (ctx + translatedSegs).takeLast(24)
                continue
            }

            val txPath = ref.transcriptChunkPath(idx)
            if (!ws.exists(txPath)) {
                failed += 1
                val next =
                    pipe.copy(
                        translationState = "failed",
                        failedChunks = failed,
                        lastError = RecordingMetaV1.ErrorInfo(code = "TranscriptNotReady", message = "missing transcript for chunk=$idx"),
                    )
                writePipeline(meta, next)
                return
            }
            val raw =
                withContext(Dispatchers.IO) {
                    ws.readTextFile(txPath, maxBytes = 8L * 1024L * 1024L)
                }
            val parsed = TranscriptChunkV1.parse(raw)
            val sourceLang = parsed.detectedLanguage?.trim()?.ifBlank { null } ?: "auto"

            try {
                translator.translateChunk(
                    sessionId = sid,
                    chunkIndex = idx,
                    sourceLanguage = sourceLang,
                    targetLanguage = targetLanguage,
                    translationClient = client,
                    context = ctx,
                )
            } catch (t: LlmNetworkError) {
                failed += 1
                val next =
                    pipe.copy(
                        translationState = "failed",
                        failedChunks = failed,
                        lastError = RecordingMetaV1.ErrorInfo(code = "LlmNetworkError", message = t.message),
                    )
                writePipeline(meta, next)
                return
            } catch (t: LlmRemoteError) {
                failed += 1
                val code = if ((t.message ?: "").contains("429")) "LlmQuotaExceeded" else "LlmRemoteError"
                val next =
                    pipe.copy(
                        translationState = "failed",
                        failedChunks = failed,
                        lastError = RecordingMetaV1.ErrorInfo(code = code, message = t.message),
                    )
                writePipeline(meta, next)
                return
            } catch (t: LlmParseError) {
                failed += 1
                val next =
                    pipe.copy(
                        translationState = "failed",
                        failedChunks = failed,
                        lastError = RecordingMetaV1.ErrorInfo(code = "LlmParseError", message = t.message),
                    )
                writePipeline(meta, next)
                return
            } catch (t: Throwable) {
                failed += 1
                val next =
                    pipe.copy(
                        translationState = "failed",
                        failedChunks = failed,
                        lastError = RecordingMetaV1.ErrorInfo(code = "LlmNetworkError", message = t.message ?: "unknown"),
                    )
                writePipeline(meta, next)
                return
            }

            translatedChunks += 1
            pipe = pipe.copy(translatedChunks = translatedChunks, failedChunks = failed)
            writePipeline(meta, pipe.copy(translationState = "running"))

            val translatedSegs =
                runCatching {
                    val rawT = ws.readTextFile(outPath, maxBytes = 8L * 1024L * 1024L)
                    val ch = com.lsl.kotlin_agent_app.radio_translation.TranslationChunkV1.parse(rawT)
                    ch.segments.map { s ->
                        TranslatedSegment(
                            id = s.id,
                            startMs = s.startMs,
                            endMs = s.endMs,
                            sourceText = s.sourceText,
                            translatedText = s.translatedText,
                            emotion = s.emotion,
                        )
                    }
                }.getOrNull().orEmpty()
            if (translatedSegs.isNotEmpty()) {
                ctx = (ctx + translatedSegs).takeLast(24)
            }
        }

        val done =
            pipe.copy(
                translationState = "completed",
                translatedChunks = translatedChunks,
                failedChunks = failed,
            )
        writePipeline(meta, done)
    }

    private fun writePipeline(
        metaBase: RecordingMetaV1,
        pipeline: RecordingMetaV1.Pipeline,
    ) {
        val sid = metaBase.sessionId.trim()
        val next = metaBase.copy(updatedAt = nowIso(), pipeline = pipeline)
        store.writeSessionMeta(sid, next)
    }

    private fun mapAsrErrorCode(t: AsrException): String {
        return when (t) {
            is AsrNetworkError -> "AsrNetworkError"
            is AsrUploadError -> "AsrUploadError"
            is AsrParseError -> "AsrParseError"
            is AsrTaskTimeout -> "AsrTaskTimeout"
            is AsrRemoteError -> "AsrRemoteError"
            else -> "AsrRemoteError"
        }
    }

    private fun mapAsrErrorMessage(t: AsrException): String? {
        return when (t) {
            is AsrRemoteError -> "remote_code=${t.code}: ${t.message ?: ""}".trim().ifBlank { null }
            else -> t.message?.trim()?.ifBlank { null }
        }
    }
}
