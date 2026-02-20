package com.lsl.kotlin_agent_app.radio_transcript

import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.radio_recordings.RecordingMetaV1
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
class TranscriptTaskManagerAutoLanguageTest {

    @Test
    fun start_acceptsSourceLangAuto_andStoresNullLanguage() =
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

            val sessionId = "rec_20260220_auto_lang"
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

            val mgr = TranscriptTaskManager(appContext = context, ws = ws)
            val task = mgr.start(sessionId = sessionId, sourceLanguage = "auto", force = false)
            assertEquals(null, task.sourceLanguage)
        }
}
