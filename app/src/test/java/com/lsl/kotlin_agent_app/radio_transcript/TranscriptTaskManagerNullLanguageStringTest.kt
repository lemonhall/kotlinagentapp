package com.lsl.kotlin_agent_app.radio_transcript

import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.asr.AsrResult
import com.lsl.kotlin_agent_app.asr.AsrSegment
import com.lsl.kotlin_agent_app.asr.CloudAsrClient
import com.lsl.kotlin_agent_app.radio_recordings.RecordingMetaV1
import java.io.File
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
class TranscriptTaskManagerNullLanguageStringTest {

    @Test
    fun runTask_treatsStringNullLanguageAsAuto() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val ws = AgentsWorkspace(context)
            ws.ensureInitialized()

            ws.writeTextFile(
                ".agents/workspace/radio_recordings/.env",
                "DASHSCOPE_API_KEY=k_test\n" +
                    "DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com/api/v1\n" +
                    "ASR_MODEL=qwen3-asr-flash-filetrans\n",
            )

            val sessionId = "rec_20260220_null_lang_string"
            ws.mkdir(".agents/workspace/radio_recordings/$sessionId")
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
                    chunks = listOf(RecordingMetaV1.Chunk(file = "chunk_001.ogg", index = 1)),
                )
            val pretty = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true }
            ws.writeTextFile(
                ".agents/workspace/radio_recordings/$sessionId/_meta.json",
                pretty.encodeToString(JsonObject.serializer(), meta.toJsonObject()) + "\n",
            )

            val chunkFile = File(context.filesDir, ".agents/workspace/radio_recordings/$sessionId/chunk_001.ogg")
            chunkFile.parentFile?.mkdirs()
            chunkFile.writeBytes(byteArrayOf(0x00))

            val mgr = TranscriptTaskManager(appContext = context, ws = ws)
            val store = TranscriptTaskStore(ws)
            val task = store.createTask(sessionId = sessionId, taskId = "tx_test_null_lang", sourceLanguage = "null", totalChunks = 1)

            var capturedLanguage: String? = "sentinel"
            val fakeClient =
                object : CloudAsrClient {
                    override suspend fun transcribe(
                        audioFile: File,
                        mimeType: String,
                        language: String?,
                    ): AsrResult {
                        capturedLanguage = language
                        return AsrResult(segments = listOf(AsrSegment(id = 1, startMs = 0, endMs = 1000, text = "ok", emotion = null)), detectedLanguage = null)
                    }
                }

            mgr.runTask(sessionId = sessionId, taskId = task.taskId, cloudAsrClient = fakeClient)

            assertEquals(null, capturedLanguage)
            assertTrue(ws.exists(".agents/workspace/radio_recordings/$sessionId/transcripts/${task.taskId}/chunk_001.transcript.json"))
        }
}

