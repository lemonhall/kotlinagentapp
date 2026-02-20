package com.lsl.kotlin_agent_app.agent.tools.terminal

import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import java.io.File
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalExecToolRadioRecordTest {

    @Test
    fun radio_record_help_forms_exit0() =
        runTerminalExecToolTest { tool ->
            val forms =
                listOf(
                    "radio record --help",
                    "radio help record",
                )
            for (cmd in forms) {
                val out = tool.exec(cmd)
                assertEquals("help should exit 0 for: $cmd", 0, out.exitCode)
                assertTrue("stdout should be non-empty for: $cmd", out.stdout.isNotBlank())
            }
        }

    @Test
    fun radio_record_start_rejectsNonRadiosPath() =
        runTerminalExecToolTest { tool ->
            val out = tool.exec("radio record start --in workspace/musics/not_allowed.radio")
            assertTrue(out.exitCode != 0)
            assertEquals("NotInRadiosDir", out.errorCode)
        }

    @Test
    fun radio_record_start_enforcesMaxConcurrentRecordings_andCreatesFiles() =
        runTerminalExecToolTest(
            setup = { context ->
                AgentsWorkspace(context).ensureInitialized()
                val radioJson1 =
                    """
                    {
                      "schema": "kotlin-agent-app/radio-station@v1",
                      "id": "radio-browser:test-1",
                      "name": "Test Station 1",
                      "streamUrl": "https://example.com/live1"
                    }
                    """.trimIndent()
                val radioJson2 =
                    """
                    {
                      "schema": "kotlin-agent-app/radio-station@v1",
                      "id": "radio-browser:test-2",
                      "name": "Test Station 2",
                      "streamUrl": "https://example.com/live2"
                    }
                    """.trimIndent()
                val radioJson3 =
                    """
                    {
                      "schema": "kotlin-agent-app/radio-station@v1",
                      "id": "radio-browser:test-3",
                      "name": "Test Station 3",
                      "streamUrl": "https://example.com/live3"
                    }
                    """.trimIndent()

                fun writeRadio(name: String, raw: String) {
                    val f = File(context.filesDir, ".agents/workspace/radios/$name")
                    f.parentFile?.mkdirs()
                    f.writeText(raw, Charsets.UTF_8)
                }

                writeRadio("test1.radio", radioJson1)
                writeRadio("test2.radio", radioJson2)
                writeRadio("test3.radio", radioJson3);

                { }
            },
        ) { tool ->
            val out1 = tool.exec("radio record start --in workspace/radios/test1.radio")
            assertEquals(0, out1.exitCode)
            val sid1 = out1.result!!["session_id"]!!.jsonPrimitive.content

            val out2 = tool.exec("radio record start --in workspace/radios/test2.radio")
            assertEquals(0, out2.exitCode)
            val sid2 = out2.result!!["session_id"]!!.jsonPrimitive.content
            assertTrue("session ids should be distinct", sid1 != sid2)

            val out3 = tool.exec("radio record start --in workspace/radios/test3.radio")
            assertTrue(out3.exitCode != 0)
            assertEquals("MaxConcurrentRecordings", out3.errorCode)

            val meta1 = File(out1.filesDir, ".agents/workspace/radio_recordings/$sid1/_meta.json")
            assertTrue("meta should exist: ${meta1.path}", meta1.exists())
        }

    @Test
    fun radio_record_start_recordOnly_writesMetaWithNullPipeline() =
        runTerminalExecToolTest(
            setup = { context ->
                AgentsWorkspace(context).ensureInitialized()
                val radioJson =
                    """
                    {
                      "schema": "kotlin-agent-app/radio-station@v1",
                      "id": "radio-browser:test-ro",
                      "name": "Record Only Station",
                      "streamUrl": "https://example.com/live"
                    }
                    """.trimIndent()
                val f = File(context.filesDir, ".agents/workspace/radios/record_only.radio")
                f.parentFile?.mkdirs()
                f.writeText(radioJson, Charsets.UTF_8)
                val teardown: () -> Unit = { }
                teardown
            },
        ) { tool ->
            val out = tool.exec("radio record start --in workspace/radios/record_only.radio --record_only")
            assertEquals(0, out.exitCode)
            assertEquals("true", out.result!!["record_only"]!!.jsonPrimitive.content)

            val sid = out.result!!["session_id"]!!.jsonPrimitive.content
            val meta = File(out.filesDir, ".agents/workspace/radio_recordings/$sid/_meta.json")
            assertTrue(meta.exists())
            val raw = meta.readText(Charsets.UTF_8)
            assertTrue("meta should contain pipeline=null", raw.contains("\"pipeline\": null"))
        }
}
