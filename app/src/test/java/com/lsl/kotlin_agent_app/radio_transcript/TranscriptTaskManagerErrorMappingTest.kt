package com.lsl.kotlin_agent_app.radio_transcript

import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.asr.AsrException
import com.lsl.kotlin_agent_app.asr.AsrNetworkError
import com.lsl.kotlin_agent_app.asr.AsrParseError
import com.lsl.kotlin_agent_app.asr.AsrRemoteError
import com.lsl.kotlin_agent_app.asr.AsrTaskTimeout
import com.lsl.kotlin_agent_app.asr.AsrUploadError
import com.lsl.kotlin_agent_app.asr.CloudAsrClient
import com.lsl.kotlin_agent_app.radio_recordings.RecordingMetaV1
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptTaskManagerErrorMappingTest {

    @Test
    fun runTask_mapsAsrExceptions_toStableErrorCodes() =
        runBlocking {
            val cases: List<Pair<AsrException, String>> =
                listOf(
                    AsrNetworkError("net") to "AsrNetworkError",
                    AsrUploadError("upload") to "AsrUploadError",
                    AsrParseError("parse") to "AsrParseError",
                    AsrTaskTimeout("timeout") to "AsrTaskTimeout",
                    AsrRemoteError("500", "remote") to "AsrRemoteError",
                )

            for ((ex, expectedCode) in cases) {
                val context = RuntimeEnvironment.getApplication()
                val ws = AgentsWorkspace(context)
                ws.ensureInitialized()

                val sessionId = "rec_20260220_err_${expectedCode.lowercase()}"
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
                ws.writeTextFile(".agents/workspace/radio_recordings/$sessionId/chunk_001.ogg", "fake ogg\n")

                val store = TranscriptTaskStore(ws)
                store.ensureSessionRoot(sessionId)
                val taskId = "tx_err_${expectedCode.lowercase()}"
                store.createTask(sessionId = sessionId, taskId = taskId, sourceLanguage = "ja", totalChunks = 1)

                val fakeClient =
                    object : CloudAsrClient {
                        override suspend fun transcribe(audioFile: File, mimeType: String, language: String?) =
                            throw ex
                    }

                val mgr = TranscriptTaskManager(appContext = context, ws = ws, store = store)
                mgr.runTask(sessionId = sessionId, taskId = taskId, cloudAsrClient = fakeClient)

                val raw = ws.readTextFile(RadioTranscriptPaths.taskJson(sessionId, taskId), maxBytes = 256 * 1024)
                val parsed = TranscriptTaskV1.parse(raw)
                assertEquals("failed", parsed.state)
                assertEquals(expectedCode, parsed.lastError?.code)
            }
        }
}
