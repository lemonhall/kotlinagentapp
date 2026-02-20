package com.lsl.kotlin_agent_app.radio_transcript

import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.asr.AsrResult
import com.lsl.kotlin_agent_app.asr.AsrSegment
import com.lsl.kotlin_agent_app.asr.CloudAsrClient
import com.lsl.kotlin_agent_app.radio_recordings.RadioRecordingsStore
import com.lsl.kotlin_agent_app.radio_recordings.RecordingMetaV1
import com.lsl.kotlin_agent_app.translation.TranslatedSegment
import com.lsl.kotlin_agent_app.translation.TranslationClient
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingPipelineTest {

    @Test
    fun run_writesTranscriptAndTranslation_andUpdatesPipelineState() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val ws = AgentsWorkspace(context)
            ws.ensureInitialized()
            val store = RadioRecordingsStore(ws)

            val sessionId = "rec_20260220_pipeline_ok"
            ws.mkdir(".agents/workspace/radio_recordings/$sessionId")
            ws.writeTextFile(".agents/workspace/radio_recordings/$sessionId/chunk_001.ogg", "fake ogg\n")
            ws.writeTextFile(".agents/workspace/radio_recordings/$sessionId/chunk_002.ogg", "fake ogg\n")

            val meta =
                RecordingMetaV1(
                    schema = RecordingMetaV1.SCHEMA_V1,
                    sessionId = sessionId,
                    station =
                        RecordingMetaV1.Station(
                            stationId = "radio-browser:test",
                            name = "Test Station",
                            radioFilePath = "workspace/radios/test.radio",
                            streamUrl = "https://example.com/live",
                        ),
                    chunkDurationMin = 10,
                    outputFormat = "ogg/opus",
                    state = "completed",
                    createdAt = RecordingMetaV1.nowIso(),
                    updatedAt = RecordingMetaV1.nowIso(),
                    chunks =
                        listOf(
                            RecordingMetaV1.Chunk(file = "chunk_001.ogg", index = 1),
                            RecordingMetaV1.Chunk(file = "chunk_002.ogg", index = 2),
                        ),
                    pipeline =
                        RecordingMetaV1.Pipeline(
                            targetLanguage = "zh",
                            transcriptState = "pending",
                            translationState = "pending",
                        ),
                )
            val pretty = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true }
            ws.writeTextFile(
                ".agents/workspace/radio_recordings/$sessionId/_meta.json",
                pretty.encodeToString(JsonObject.serializer(), meta.toJsonObject()) + "\n",
            )

            val asrCalls = AtomicInteger(0)
            val fakeAsr =
                object : CloudAsrClient {
                    override suspend fun transcribe(audioFile: File, mimeType: String, language: String?): AsrResult {
                        asrCalls.incrementAndGet()
                        return AsrResult(
                            segments =
                                listOf(
                                    AsrSegment(id = 0, startMs = 0, endMs = 1000, text = "こんにちは", emotion = null),
                                    AsrSegment(id = 1, startMs = 1000, endMs = 2000, text = "世界", emotion = "neutral"),
                                ),
                            detectedLanguage = "ja",
                        )
                    }
                }

            val tlCalls = AtomicInteger(0)
            val fakeTl =
                object : TranslationClient {
                    override suspend fun translateBatch(
                        segments: List<TranscriptSegment>,
                        context: List<TranslatedSegment>,
                        sourceLanguage: String,
                        targetLanguage: String,
                    ): List<TranslatedSegment> {
                        tlCalls.incrementAndGet()
                        return segments.map { s ->
                            TranslatedSegment(
                                id = s.id,
                                startMs = s.startMs,
                                endMs = s.endMs,
                                sourceText = s.text,
                                translatedText = "T:${s.text}",
                                emotion = s.emotion,
                            )
                        }
                    }
                }

            val pipeline =
                RecordingPipeline(
                    ws = ws,
                    store = store,
                    asrClient = fakeAsr,
                    translationClientFactory = { _ -> fakeTl },
                )
            pipeline.run(sessionId = sessionId)

            assertEquals(2, asrCalls.get())
            assertEquals(2, tlCalls.get())
            assertTrue(ws.exists(".agents/workspace/radio_recordings/$sessionId/transcripts/chunk_001.transcript.json"))
            assertTrue(ws.exists(".agents/workspace/radio_recordings/$sessionId/translations/chunk_001.translation.json"))

            val metaRaw = ws.readTextFile(".agents/workspace/radio_recordings/$sessionId/_meta.json", maxBytes = 2L * 1024L * 1024L)
            val parsed = RecordingMetaV1.parse(metaRaw)
            assertEquals("completed", parsed.pipeline?.transcriptState)
            assertEquals("completed", parsed.pipeline?.translationState)
            assertEquals(2, parsed.pipeline?.transcribedChunks)
            assertEquals(2, parsed.pipeline?.translatedChunks)
        }

    @Test
    fun run_skipsExistingOutputs_resumeLikeBehavior() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val ws = AgentsWorkspace(context)
            ws.ensureInitialized()
            val store = RadioRecordingsStore(ws)

            val sessionId = "rec_20260220_pipeline_resume"
            ws.mkdir(".agents/workspace/radio_recordings/$sessionId")
            ws.writeTextFile(".agents/workspace/radio_recordings/$sessionId/chunk_001.ogg", "fake ogg\n")
            ws.writeTextFile(".agents/workspace/radio_recordings/$sessionId/chunk_002.ogg", "fake ogg\n")

            val meta =
                RecordingMetaV1(
                    schema = RecordingMetaV1.SCHEMA_V1,
                    sessionId = sessionId,
                    station =
                        RecordingMetaV1.Station(
                            stationId = "radio-browser:test",
                            name = "Test Station",
                            radioFilePath = "workspace/radios/test.radio",
                            streamUrl = "https://example.com/live",
                        ),
                    chunkDurationMin = 10,
                    outputFormat = "ogg/opus",
                    state = "completed",
                    createdAt = RecordingMetaV1.nowIso(),
                    updatedAt = RecordingMetaV1.nowIso(),
                    chunks =
                        listOf(
                            RecordingMetaV1.Chunk(file = "chunk_001.ogg", index = 1),
                            RecordingMetaV1.Chunk(file = "chunk_002.ogg", index = 2),
                        ),
                    pipeline =
                        RecordingMetaV1.Pipeline(
                            targetLanguage = "zh",
                            transcriptState = "pending",
                            translationState = "pending",
                        ),
                )
            val pretty = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true }
            ws.writeTextFile(
                ".agents/workspace/radio_recordings/$sessionId/_meta.json",
                pretty.encodeToString(JsonObject.serializer(), meta.toJsonObject()) + "\n",
            )

            ws.mkdir(".agents/workspace/radio_recordings/$sessionId/transcripts")
            ws.writeTextFile(".agents/workspace/radio_recordings/$sessionId/transcripts/chunk_001.transcript.json", "{ \"schema\":\"${TranscriptChunkV1.SCHEMA_V1}\",\"sessionId\":\"$sessionId\",\"chunkIndex\":1,\"segments\":[{\"id\":0,\"startMs\":0,\"endMs\":1000,\"text\":\"ok\"}] }\n")
            ws.mkdir(".agents/workspace/radio_recordings/$sessionId/translations")
            ws.writeTextFile(".agents/workspace/radio_recordings/$sessionId/translations/chunk_001.translation.json", "{ \"schema\":\"kotlin-agent-app/radio-translation-chunk@v1\",\"sessionId\":\"$sessionId\",\"chunkIndex\":1,\"sourceLanguage\":\"ja\",\"targetLanguage\":\"zh\",\"segments\":[{\"id\":0,\"startMs\":0,\"endMs\":1000,\"sourceText\":\"ok\",\"translatedText\":\"ok\"}] }\n")

            val asrCalls = AtomicInteger(0)
            val fakeAsr =
                object : CloudAsrClient {
                    override suspend fun transcribe(audioFile: File, mimeType: String, language: String?): AsrResult {
                        asrCalls.incrementAndGet()
                        return AsrResult(
                            segments = listOf(AsrSegment(id = 0, startMs = 0, endMs = 1000, text = "ok2", emotion = null)),
                            detectedLanguage = "ja",
                        )
                    }
                }

            val tlCalls = AtomicInteger(0)
            val fakeTl =
                object : TranslationClient {
                    override suspend fun translateBatch(
                        segments: List<TranscriptSegment>,
                        context: List<TranslatedSegment>,
                        sourceLanguage: String,
                        targetLanguage: String,
                    ): List<TranslatedSegment> {
                        tlCalls.incrementAndGet()
                        return segments.map { s ->
                            TranslatedSegment(id = s.id, startMs = s.startMs, endMs = s.endMs, sourceText = s.text, translatedText = "T:${s.text}", emotion = null)
                        }
                    }
                }

            val pipeline =
                RecordingPipeline(
                    ws = ws,
                    store = store,
                    asrClient = fakeAsr,
                    translationClientFactory = { _ -> fakeTl },
                )
            pipeline.run(sessionId = sessionId)

            assertEquals(1, asrCalls.get())
            assertEquals(1, tlCalls.get())
            assertTrue(ws.exists(".agents/workspace/radio_recordings/$sessionId/transcripts/chunk_002.transcript.json"))
            assertTrue(ws.exists(".agents/workspace/radio_recordings/$sessionId/translations/chunk_002.translation.json"))
        }
}

