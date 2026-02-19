package com.lsl.kotlin_agent_app.agent.tools.terminal

import com.lsl.kotlin_agent_app.agent.tools.tts.TtsRuntimeProvider
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsVoiceSummary
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalExecToolTtsTest {

    @Test
    fun tts_help_isAvailable() =
        runTerminalExecToolTest(
            setup = { _ ->
                TtsRuntimeProvider.installForTests(FakeTtsRuntime())
                { TtsRuntimeProvider.clearForTests() }
            },
        ) { tool ->
            val out = tool.exec("tts --help")
            assertEquals(0, out.exitCode)
            assertTrue(out.stdout.contains("tts speak"))
            val r = out.result ?: error("missing result")
            assertEquals("true", r["ok"]!!.jsonPrimitive.content)
        }

    @Test
    fun tts_voices_returnsCount() =
        runTerminalExecToolTest(
            setup = { _ ->
                TtsRuntimeProvider.installForTests(
                    FakeTtsRuntime(
                        voices =
                            listOf(
                                TtsVoiceSummary(name = "v1", localeTag = "zh-CN"),
                                TtsVoiceSummary(name = "v2", localeTag = "en-US"),
                            ),
                    ),
                )
                { TtsRuntimeProvider.clearForTests() }
            },
        ) { tool ->
            val out = tool.exec("tts voices")
            assertEquals(0, out.exitCode)
            val r = out.result ?: error("missing result")
            assertEquals("tts voices", r["command"]!!.jsonPrimitive.content)
            assertEquals(2, r["voices_count"]!!.jsonPrimitive.content.toInt())
        }

    @Test
    fun tts_speak_requiresText() =
        runTerminalExecToolTest(
            setup = { _ ->
                TtsRuntimeProvider.installForTests(FakeTtsRuntime())
                { TtsRuntimeProvider.clearForTests() }
            },
        ) { tool ->
            val out = tool.exec("tts speak")
            assertTrue(out.exitCode != 0)
            assertEquals("InvalidArgs", out.errorCode)
        }

    @Test
    fun tts_speak_returnsUtteranceId() =
        runTerminalExecToolTest(
            setup = { _ ->
                TtsRuntimeProvider.installForTests(FakeTtsRuntime())
                { TtsRuntimeProvider.clearForTests() }
            },
        ) { tool ->
            val out = tool.exec("tts speak --text \"hi\"")
            assertEquals(0, out.exitCode)
            val r = out.result ?: error("missing result")
            assertEquals("tts speak", r["command"]!!.jsonPrimitive.content)
            assertTrue(r["utterance_id"]!!.jsonPrimitive.content.isNotBlank())
            assertEquals("started", r["completion"]!!.jsonPrimitive.content)
        }

    @Test
    fun tts_speak_await_timeout_returnsTimeoutError() =
        runTerminalExecToolTest(
            setup = { _ ->
                TtsRuntimeProvider.installForTests(FakeTtsRuntime())
                { TtsRuntimeProvider.clearForTests() }
            },
        ) { tool ->
            val out = tool.exec("tts speak --text \"hi\" --await true --timeout_ms 1")
            assertTrue(out.exitCode != 0)
            assertEquals("Timeout", out.errorCode)
        }

    @Test
    fun tts_stop_isAvailable() =
        runTerminalExecToolTest(
            setup = { _ ->
                TtsRuntimeProvider.installForTests(FakeTtsRuntime())
                { TtsRuntimeProvider.clearForTests() }
            },
        ) { tool ->
            val out = tool.exec("tts stop")
            assertEquals(0, out.exitCode)
            val r = out.result ?: error("missing result")
            assertEquals("tts stop", r["command"]!!.jsonPrimitive.content)
            assertEquals("true", r["stopped"]!!.jsonPrimitive.content)
        }
}

