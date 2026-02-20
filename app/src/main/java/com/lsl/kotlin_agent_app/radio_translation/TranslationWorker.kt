package com.lsl.kotlin_agent_app.radio_translation

import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.radio_recordings.RadioRecordingsPaths
import com.lsl.kotlin_agent_app.radio_transcript.TranscriptChunkV1
import com.lsl.kotlin_agent_app.translation.TranslatedSegment
import com.lsl.kotlin_agent_app.translation.TranslationClient
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class TranslationWorker(
    private val ws: AgentsWorkspace,
) {
    private val prettyJson = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true }

    suspend fun translateChunk(
        sessionId: String,
        chunkIndex: Int,
        sourceLanguage: String,
        targetLanguage: String,
        translationClient: TranslationClient,
        context: List<TranslatedSegment>,
    ) {
        val sid = sessionId.trim()
        val idx = chunkIndex.coerceAtLeast(1)
        val transcriptsDir = "${RadioRecordingsPaths.sessionDir(sid)}/transcripts"
        val translationsDir = "${RadioRecordingsPaths.sessionDir(sid)}/translations"
        ws.mkdir(transcriptsDir)
        ws.mkdir(translationsDir)

        val txPath = "$transcriptsDir/chunk_${idx.toString().padStart(3, '0')}.transcript.json"
        val raw =
            withContext(Dispatchers.IO) {
                ws.readTextFile(txPath, maxBytes = 8L * 1024L * 1024L)
            }
        val chunk = TranscriptChunkV1.parse(raw)

        val translated =
            translationClient.translateBatch(
                segments = chunk.segments,
                context = context,
                sourceLanguage = sourceLanguage.trim().ifBlank { "auto" },
                targetLanguage = targetLanguage.trim(),
            )

        val outPath = "$translationsDir/chunk_${idx.toString().padStart(3, '0')}.translation.json"
        val outChunk =
            TranslationChunkV1(
                schema = TranslationChunkV1.SCHEMA_V1,
                sessionId = sid,
                chunkIndex = idx,
                sourceLanguage = sourceLanguage.trim().ifBlank { "auto" },
                targetLanguage = targetLanguage.trim(),
                segments =
                    translated.map { s ->
                        TranslationSegmentV1(
                            id = s.id,
                            startMs = s.startMs,
                            endMs = s.endMs,
                            sourceText = s.sourceText,
                            translatedText = s.translatedText,
                            emotion = s.emotion,
                        )
                    },
            )

        val rawOut = prettyJson.encodeToString(JsonObject.serializer(), outChunk.toJsonObject()) + "\n"
        ws.writeTextFileAtomic(outPath, rawOut)
    }
}

