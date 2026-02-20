package com.lsl.kotlin_agent_app.radio_transcript

import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.asr.AsrResult
import com.lsl.kotlin_agent_app.asr.AsrSegment
import com.lsl.kotlin_agent_app.asr.CloudAsrClient
import com.lsl.kotlin_agent_app.radio_recordings.RecordingMetaV1
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
class TranscriptTaskManagerResumeTest {

    @Test
    fun runTask_recomputesProgressFromExistingFiles_andSkipsCompletedChunks() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val ws = AgentsWorkspace(context)
            ws.ensureInitialized()

            val sessionId = "rec_20260220_010203_resume"
            ws.mkdir(".agents/workspace/radio_recordings/$sessionId")

            val chunks =
                listOf(
                    RecordingMetaV1.Chunk(file = "chunk_001.ogg", index = 1),
                    RecordingMetaV1.Chunk(file = "chunk_002.ogg", index = 2),
                    RecordingMetaV1.Chunk(file = "chunk_003.ogg", index = 3),
                )
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
                    chunks = chunks,
                )
            val pretty = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true }
            ws.writeTextFile(
                ".agents/workspace/radio_recordings/$sessionId/_meta.json",
                pretty.encodeToString(JsonObject.serializer(), meta.toJsonObject()) + "\n",
            )
            for (c in chunks) {
                ws.writeTextFile(".agents/workspace/radio_recordings/$sessionId/${c.file}", "fake ogg\n")
            }

            val store = TranscriptTaskStore(ws)
            store.ensureSessionRoot(sessionId)
            val taskId = "tx_resume_1"
            store.createTask(sessionId = sessionId, taskId = taskId, sourceLanguage = "ja", totalChunks = chunks.size)

            // Pretend chunk_001 already transcribed.
            val out1 = RadioTranscriptPaths.chunkTranscriptJson(sessionId, taskId, 1)
            ws.writeTextFile(out1, "{ \"ok\": true }\n")

            val calls = AtomicInteger(0)
            val fakeClient =
                object : CloudAsrClient {
                    override suspend fun transcribe(audioFile: File, mimeType: String, language: String?): AsrResult {
                        calls.incrementAndGet()
                        return AsrResult(
                            segments =
                                listOf(
                                    AsrSegment(id = 0, startMs = 0, endMs = 1000, text = "ok", emotion = null),
                                ),
                            detectedLanguage = language,
                        )
                    }
                }

            val mgr = TranscriptTaskManager(appContext = context, ws = ws, store = store)
            mgr.runTask(sessionId = sessionId, taskId = taskId, cloudAsrClient = fakeClient)

            assertEquals("should transcribe only missing chunks", 2, calls.get())
            assertTrue(ws.exists(RadioTranscriptPaths.chunkTranscriptJson(sessionId, taskId, 2)))
            assertTrue(ws.exists(RadioTranscriptPaths.chunkTranscriptJson(sessionId, taskId, 3)))

            val raw = ws.readTextFile(RadioTranscriptPaths.taskJson(sessionId, taskId), maxBytes = 256 * 1024)
            val parsed = TranscriptTaskV1.parse(raw)
            assertEquals("completed", parsed.state)
            assertEquals(3, parsed.totalChunks)
            assertEquals(3, parsed.transcribedChunks)
        }
}

