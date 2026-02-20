package com.lsl.kotlin_agent_app.agent.tools.terminal

import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import java.io.File
import com.lsl.kotlin_agent_app.radio_recordings.RecordingMetaV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalExecToolRadioTranscriptTest {

    @Test
    fun radio_transcript_help_forms_exit0() =
        runTerminalExecToolTest { tool ->
            val forms =
                listOf(
                    "radio transcript --help",
                    "radio help transcript",
                )
            for (cmd in forms) {
                val out = tool.exec(cmd)
                assertEquals("help should exit 0 for: $cmd", 0, out.exitCode)
                assertTrue("stdout should be non-empty for: $cmd", out.stdout.isNotBlank())
            }
        }

    @Test
    fun radio_transcript_start_validatesSessionState_andChunks() =
        runTerminalExecToolTest(
            setup = { context ->
                AgentsWorkspace(context).ensureInitialized()

                fun writeMeta(sessionId: String, state: String, chunks: List<RecordingMetaV1.Chunk>) {
                    val dir = File(context.filesDir, ".agents/workspace/radio_recordings/$sessionId")
                    dir.mkdirs()
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
                            state = state,
                            createdAt = RecordingMetaV1.nowIso(),
                            updatedAt = RecordingMetaV1.nowIso(),
                            chunks = chunks,
                            error = null,
                        )
                    File(dir, "_meta.json").writeText(
                        kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true }
                            .encodeToString(kotlinx.serialization.json.JsonObject.serializer(), meta.toJsonObject()) + "\n",
                        Charsets.UTF_8,
                    )
                }

                // Session A: still recording (should be rejected)
                writeMeta(
                    sessionId = "rec_20260220_010203_a",
                    state = "recording",
                    chunks =
                        listOf(
                            RecordingMetaV1.Chunk(file = "chunk_001.ogg", index = 1),
                        ),
                )
                File(context.filesDir, ".agents/workspace/radio_recordings/rec_20260220_010203_a/chunk_001.ogg").apply {
                    parentFile?.mkdirs()
                    writeBytes(byteArrayOf(0x01, 0x02))
                }

                // Session B: completed but no chunks (should be rejected)
                writeMeta(
                    sessionId = "rec_20260220_010203_b",
                    state = "completed",
                    chunks = emptyList(),
                )

                val teardown: () -> Unit = { }
                teardown
            },
        ) { tool ->
            val outRecording =
                tool.exec(
                    "radio transcript start --session rec_20260220_010203_a --source_lang ja",
                )
            assertTrue(outRecording.exitCode != 0)
            assertEquals("SessionStillRecording", outRecording.errorCode)

            val outNoChunks =
                tool.exec(
                    "radio transcript start --session rec_20260220_010203_b --source_lang ja",
                )
            assertTrue(outNoChunks.exitCode != 0)
            assertEquals("SessionNoChunks", outNoChunks.errorCode)
        }

    @Test
    fun radio_transcript_start_sessionNotFound() =
        runTerminalExecToolTest { tool ->
            val out = tool.exec("radio transcript start --session rec_missing --source_lang ja")
            assertTrue(out.exitCode != 0)
            assertEquals("SessionNotFound", out.errorCode)
        }
}
