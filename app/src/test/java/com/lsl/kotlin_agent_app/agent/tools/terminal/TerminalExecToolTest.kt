package com.lsl.kotlin_agent_app.agent.tools.terminal

import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.agent.tools.calendar.FakeCalendarPermissionChecker
import com.lsl.kotlin_agent_app.agent.tools.calendar.InMemoryCalendarStore
import com.lsl.kotlin_agent_app.agent.tools.irc.IrcClient
import com.lsl.kotlin_agent_app.agent.tools.irc.IrcClientListener
import com.lsl.kotlin_agent_app.agent.tools.irc.IrcClientTestHooks
import com.lsl.kotlin_agent_app.agent.tools.irc.IrcConfig
import com.lsl.kotlin_agent_app.agent.tools.irc.IrcSessionRuntimeStore
import com.lsl.kotlin_agent_app.agent.tools.mail.QqMailImapClient
import com.lsl.kotlin_agent_app.agent.tools.mail.QqMailMessage
import com.lsl.kotlin_agent_app.agent.tools.mail.QqMailSendRequest
import com.lsl.kotlin_agent_app.agent.tools.mail.QqMailSendResult
import com.lsl.kotlin_agent_app.agent.tools.exchange_rate.ExchangeRateClientTestHooks
import com.lsl.kotlin_agent_app.agent.tools.exchange_rate.ExchangeRateTransport
import com.lsl.kotlin_agent_app.agent.tools.mail.QqMailSmtpClient
import com.lsl.kotlin_agent_app.agent.tools.rss.RssClientTestHooks
import com.lsl.kotlin_agent_app.agent.tools.rss.RssHttpResponse
import com.lsl.kotlin_agent_app.agent.tools.rss.RssTransport
import com.lsl.kotlin_agent_app.agent.tools.stock.FinnhubClientTestHooks
import com.lsl.kotlin_agent_app.agent.tools.stock.FinnhubHttpResponse
import com.lsl.kotlin_agent_app.agent.tools.stock.FinnhubTransport
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.cal.CalCommandTestHooks
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.qqmail.QqMailCommandTestHooks
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsQueueMode
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsRuntime
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsRuntimeProvider
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsSpeakCompletion
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsSpeakRequest
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsSpeakResponse
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsStopResponse
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsTimeout
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsVoiceSummary
import com.lsl.kotlin_agent_app.media.MusicPlayerController
import com.lsl.kotlin_agent_app.media.MusicPlayerControllerProvider
import com.lsl.kotlin_agent_app.media.MusicPlaybackRequest
import com.lsl.kotlin_agent_app.media.MusicTransport
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.tools.ToolContext
import me.lemonhall.openagentic.sdk.tools.ToolOutput
import okhttp3.HttpUrl
import okio.FileSystem
import okio.Path.Companion.toPath
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalExecToolTest {

    @Test
    fun unknownCommand_isRejected() = runTest { tool ->
        val out = tool.exec("no_such_command")
        assertTrue(out.exitCode != 0)
        assertEquals("UnknownCommand", out.errorCode)
    }

    @Test
    fun hello_printsAsciiAndSignature_andWritesAuditRun() = runTest { tool ->
        val out = tool.exec("hello")
        assertEquals(0, out.exitCode)
        assertTrue(out.stdout.contains("HELLO"))
        assertTrue(out.stdout.contains("lemonhall"))

        val runId = out.runId
        assertTrue(runId.isNotBlank())

        val auditPath = File(out.filesDir, ".agents/artifacts/terminal_exec/runs/$runId.json")
        assertTrue("audit file should exist: $auditPath", auditPath.exists())
        val auditText = auditPath.readText(Charsets.UTF_8)
        assertTrue(auditText.contains("\"command\""))
        assertTrue(auditText.contains("hello"))
        assertTrue("audit should include stdout", auditText.contains("\"stdout\""))
        assertTrue("audit should not include stdin key", !auditText.contains("\"stdin\""))
    }

    @Test
    fun tts_help_isAvailable() =
        runTest(
            setup = { _ ->
                TtsRuntimeProvider.installForTests(FakeTtsRuntime());
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
        runTest(
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
                ;
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
        runTest(
            setup = { _ ->
                TtsRuntimeProvider.installForTests(FakeTtsRuntime());
                { TtsRuntimeProvider.clearForTests() }
            },
        ) { tool ->
            val out = tool.exec("tts speak")
            assertTrue(out.exitCode != 0)
            assertEquals("InvalidArgs", out.errorCode)
        }

    @Test
    fun tts_speak_returnsUtteranceId() =
        runTest(
            setup = { _ ->
                TtsRuntimeProvider.installForTests(FakeTtsRuntime());
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
        runTest(
            setup = { _ ->
                TtsRuntimeProvider.installForTests(FakeTtsRuntime());
                { TtsRuntimeProvider.clearForTests() }
            },
        ) { tool ->
            val out = tool.exec("tts speak --text \"hi\" --await true --timeout_ms 1")
            assertTrue(out.exitCode != 0)
            assertEquals("Timeout", out.errorCode)
        }

    @Test
    fun tts_stop_isAvailable() =
        runTest(
            setup = { _ ->
                TtsRuntimeProvider.installForTests(FakeTtsRuntime());
                { TtsRuntimeProvider.clearForTests() }
            },
        ) { tool ->
            val out = tool.exec("tts stop")
            assertEquals(0, out.exitCode)
            val r = out.result ?: error("missing result")
            assertEquals("tts stop", r["command"]!!.jsonPrimitive.content)
            assertEquals("true", r["stopped"]!!.jsonPrimitive.content)
        }

    @Test
    fun music_status_idle_byDefault() = runTest(
        setup = { context ->
            MusicPlayerControllerProvider.resetForTests()
            MusicPlayerControllerProvider.installAppContext(context)
            MusicPlayerControllerProvider.factoryOverride = { ctx ->
                MusicPlayerController(ctx, transport = FakeMusicTransport())
            }
            {
                MusicPlayerControllerProvider.resetForTests()
            }
        },
    ) { tool ->
        val out = tool.exec("music status")
        assertEquals(0, out.exitCode)
        val r = out.result ?: error("missing result")
        assertEquals("idle", r["state"]!!.jsonPrimitive.content)
        assertEquals(0, r["queue_size"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun music_play_rejectsPathOutsideMusicsTree() = runTest(
        setup = { context ->
            MusicPlayerControllerProvider.resetForTests()
            MusicPlayerControllerProvider.installAppContext(context)
            MusicPlayerControllerProvider.factoryOverride = { ctx ->
                MusicPlayerController(ctx, transport = FakeMusicTransport())
            }
            {
                MusicPlayerControllerProvider.resetForTests()
            }
        },
    ) { tool ->
        val out = tool.exec("music play --in workspace/inbox/not_allowed.mp3")
        assertTrue(out.exitCode != 0)
        assertEquals("PathNotAllowed", out.errorCode)
    }

    @Test
    fun radio_help_forms_exit0() = runTest { tool ->
        val forms =
            listOf(
                "radio --help",
                "radio help",
                "radio status --help",
                "radio help status",
                "radio play --help",
                "radio help play",
                "radio pause --help",
                "radio help pause",
                "radio resume --help",
                "radio help resume",
                "radio stop --help",
                "radio help stop",
            )
        for (cmd in forms) {
            val out = tool.exec(cmd)
            assertEquals("help should exit 0 for: $cmd", 0, out.exitCode)
            val r = out.result ?: error("missing result for: $cmd")
            assertEquals(true, r["ok"]!!.jsonPrimitive.content.toBooleanStrict())
            assertTrue("stdout should be non-empty for: $cmd", out.stdout.isNotBlank())
        }
    }

    @Test
    fun radio_play_rejectsBadPaths_andMissingFile() = runTest(
        setup = { context ->
            MusicPlayerControllerProvider.resetForTests()
            MusicPlayerControllerProvider.installAppContext(context)
            MusicPlayerControllerProvider.factoryOverride = { ctx ->
                MusicPlayerController(ctx, transport = FakeMusicTransport())
            }
            {
                MusicPlayerControllerProvider.resetForTests()
            }
        },
    ) { tool ->
        val outOutside = tool.exec("radio play --in workspace/musics/not_allowed.radio")
        assertTrue(outOutside.exitCode != 0)
        assertEquals("NotInRadiosDir", outOutside.errorCode)

        val outExt = tool.exec("radio play --in workspace/radios/not_radio.txt")
        assertTrue(outExt.exitCode != 0)
        assertEquals("NotRadioFile", outExt.errorCode)

        val outMissing = tool.exec("radio play --in workspace/radios/missing.radio")
        assertTrue(outMissing.exitCode != 0)
        assertEquals("NotFound", outMissing.errorCode)
    }

    @Test
    fun radio_play_pause_resume_stop_roundtrip() = runTest(
        setup = { context ->
            val ws = AgentsWorkspace(context)
            ws.ensureInitialized()
            val radioJson =
                """
                {
                  "schema": "kotlin-agent-app/radio-station@v1",
                  "id": "radio-browser:test-1",
                  "name": "Test Station 1",
                  "streamUrl": "https://example.com/live?token=SECRET",
                  "country": "Testland",
                  "faviconUrl": "https://example.com/favicon.png"
                }
                """.trimIndent()
            val f = File(context.filesDir, ".agents/workspace/radios/test1.radio")
            f.parentFile?.mkdirs()
            f.writeText(radioJson, Charsets.UTF_8)

            MusicPlayerControllerProvider.resetForTests()
            MusicPlayerControllerProvider.installAppContext(context)
            MusicPlayerControllerProvider.factoryOverride = { ctx ->
                MusicPlayerController(ctx, transport = FakeMusicTransport())
            }
            {
                MusicPlayerControllerProvider.resetForTests()
            }
        },
    ) { tool ->
        val outPlay = tool.exec("radio play --in workspace/radios/test1.radio")
        assertEquals(0, outPlay.exitCode)

        val outStatus1 = tool.exec("radio status")
        assertEquals(0, outStatus1.exitCode)
        assertEquals("playing", outStatus1.result!!["state"]!!.jsonPrimitive.content)
        val station1 = outStatus1.result!!["station"]!!.jsonObject
        assertEquals("workspace/radios/test1.radio", station1["path"]!!.jsonPrimitive.content)
        assertEquals("radio-browser:test-1", station1["id"]!!.jsonPrimitive.content)
        assertEquals("Test Station 1", station1["name"]!!.jsonPrimitive.content)

        val outPause = tool.exec("radio pause")
        assertEquals(0, outPause.exitCode)
        assertEquals("paused", outPause.result!!["state"]!!.jsonPrimitive.content)

        val outResume = tool.exec("radio resume")
        assertEquals(0, outResume.exitCode)
        assertEquals("playing", outResume.result!!["state"]!!.jsonPrimitive.content)

        val outStop = tool.exec("radio stop")
        assertEquals(0, outStop.exitCode)
        val outStatus2 = tool.exec("radio status")
        assertEquals(0, outStatus2.exitCode)
        assertEquals("stopped", outStatus2.result!!["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun music_play_pause_resume_seek_stop_roundtrip() = runTest(
        setup = { context ->
            val ws = AgentsWorkspace(context)
            ws.ensureInitialized()
            val p = "workspace/musics/demo.mp3"
            val f = File(context.filesDir, ".agents/$p")
            f.parentFile?.mkdirs()
            f.writeBytes(buildFakeMp3Bytes())

            MusicPlayerControllerProvider.resetForTests()
            MusicPlayerControllerProvider.installAppContext(context)
            MusicPlayerControllerProvider.factoryOverride = { ctx ->
                MusicPlayerController(ctx, transport = FakeMusicTransport())
            }
            {
                MusicPlayerControllerProvider.resetForTests()
            }
        },
    ) { tool ->
        val outPlay = tool.exec("music play --in workspace/musics/demo.mp3")
        assertEquals(0, outPlay.exitCode)

        val outStatus1 = tool.exec("music status")
        assertEquals(0, outStatus1.exitCode)
        assertEquals("playing", outStatus1.result!!["state"]!!.jsonPrimitive.content)

        val outPause = tool.exec("music pause")
        assertEquals(0, outPause.exitCode)
        assertEquals("paused", outPause.result!!["state"]!!.jsonPrimitive.content)

        val outResume = tool.exec("music resume")
        assertEquals(0, outResume.exitCode)
        assertEquals("playing", outResume.result!!["state"]!!.jsonPrimitive.content)

        val outSeek = tool.exec("music seek --to-ms 1234")
        assertEquals(0, outSeek.exitCode)
        val outStatus2 = tool.exec("music status")
        assertEquals(0, outStatus2.exitCode)
        assertEquals(1234L, outStatus2.result!!["position_ms"]!!.jsonPrimitive.content.toLong())

        val outStop = tool.exec("music stop")
        assertEquals(0, outStop.exitCode)
        val outStatus3 = tool.exec("music status")
        assertEquals(0, outStatus3.exitCode)
        assertEquals("stopped", outStatus3.result!!["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun music_next_prev_usesDeterministicQueue() = runTest(
        setup = { context ->
            val ws = AgentsWorkspace(context)
            ws.ensureInitialized()
            val f1 = File(context.filesDir, ".agents/workspace/musics/a.mp3")
            val f2 = File(context.filesDir, ".agents/workspace/musics/b.mp3")
            f1.parentFile?.mkdirs()
            f1.writeBytes(buildFakeMp3Bytes())
            f2.writeBytes(buildFakeMp3Bytes())

            MusicPlayerControllerProvider.resetForTests()
            MusicPlayerControllerProvider.installAppContext(context)
            MusicPlayerControllerProvider.factoryOverride = { ctx ->
                MusicPlayerController(ctx, transport = FakeMusicTransport())
            }
            {
                MusicPlayerControllerProvider.resetForTests()
            }
        },
    ) { tool ->
        assertEquals(0, tool.exec("music play --in workspace/musics/a.mp3").exitCode)
        assertEquals(0, tool.exec("music next").exitCode)
        val st1 = tool.exec("music status").result ?: error("missing result")
        val track1 = st1["track"]!!.jsonObject
        assertEquals("workspace/musics/b.mp3", track1["path"]!!.jsonPrimitive.content)

        assertEquals(0, tool.exec("music prev").exitCode)
        val st2 = tool.exec("music status").result ?: error("missing result")
        val track2 = st2["track"]!!.jsonObject
        assertEquals("workspace/musics/a.mp3", track2["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun music_meta_set_requiresConfirm_andDoesNotModifyFile() = runTest { tool ->
        val context = RuntimeEnvironment.getApplication()
        val ws = AgentsWorkspace(context)
        ws.ensureInitialized()
        val f = File(context.filesDir, ".agents/workspace/musics/meta.mp3")
        f.parentFile?.mkdirs()
        val before = buildFakeMp3Bytes()
        f.writeBytes(before)

        val out = tool.exec("music meta set --in workspace/musics/meta.mp3 --title new-title")
        assertTrue(out.exitCode != 0)
        assertEquals("ConfirmRequired", out.errorCode)
        assertTrue(f.readBytes().contentEquals(before))
    }

    @Test
    fun music_meta_set_writesAtomically_andMetaGetReflectsChanges() = runTest { tool ->
        val context = RuntimeEnvironment.getApplication()
        val ws = AgentsWorkspace(context)
        ws.ensureInitialized()
        val f = File(context.filesDir, ".agents/workspace/musics/meta2.mp3")
        f.parentFile?.mkdirs()
        f.writeBytes(buildFakeMp3Bytes())

        val outSet =
            tool.exec(
                "music meta set --in workspace/musics/meta2.mp3 --title \"t1\" --artist \"a1\" --lyrics \"l1\" --confirm",
            )
        assertEquals(0, outSet.exitCode)
        val outGet = tool.exec("music meta get --in workspace/musics/meta2.mp3")
        assertEquals(0, outGet.exitCode)
        val md = outGet.result!!["metadata"]!!.jsonObject
        assertEquals("t1", md["title"]!!.jsonPrimitive.content)
        assertEquals("a1", md["artist"]!!.jsonPrimitive.content)
        assertEquals("l1", md["lyrics"]!!.jsonPrimitive.content)
    }

    @Test
    fun music_meta_set_rollbackOnReplaceFailure() = runTest(
        setup = { context ->
            System.setProperty("kotlin-agent-app.music.atomic_replace.fail_for_test", "1");
            {
                System.clearProperty("kotlin-agent-app.music.atomic_replace.fail_for_test")
            }
        },
    ) { tool ->
        val context = RuntimeEnvironment.getApplication()
        val ws = AgentsWorkspace(context)
        ws.ensureInitialized()
        val f = File(context.filesDir, ".agents/workspace/musics/meta3.mp3")
        f.parentFile?.mkdirs()
        val before = buildFakeMp3Bytes()
        f.writeBytes(before)

        val out =
            tool.exec(
                "music meta set --in workspace/musics/meta3.mp3 --title \"t2\" --confirm",
            )
        assertTrue(out.exitCode != 0)
        assertEquals("WriteFailed", out.errorCode)
        assertTrue(f.readBytes().contentEquals(before))
    }

    @Test
    fun newlineIsRejected() = runTest { tool ->
        val out = tool.exec("hello\nworld")
        assertTrue(out.exitCode != 0)
        assertEquals("InvalidCommand", out.errorCode)
    }

    @Test
    fun irc_status_missingCredentials_whenEnvIncomplete() = runTest(
        setup = { ctx ->
            val prefs = ctx.getSharedPreferences("kotlin-agent-app", android.content.Context.MODE_PRIVATE)
            val sid = "sess_irc_" + UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString(com.lsl.kotlin_agent_app.config.AppPrefsKeys.CHAT_SESSION_ID, sid).apply();
            {
                prefs.edit().remove(com.lsl.kotlin_agent_app.config.AppPrefsKeys.CHAT_SESSION_ID).apply()
                IrcClientTestHooks.clear()
                IrcSessionRuntimeStore.clearForTest()
            }
        },
    ) { tool ->
        val out = tool.exec("irc status")
        assertTrue(out.exitCode != 0)
        assertEquals("MissingCredentials", out.errorCode)
    }

    @Test
    fun irc_rejects_nickTooLong() = runTest(
        setup = { ctx ->
            val prefs = ctx.getSharedPreferences("kotlin-agent-app", android.content.Context.MODE_PRIVATE)
            val sid = "sess_irc_" + UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString(com.lsl.kotlin_agent_app.config.AppPrefsKeys.CHAT_SESSION_ID, sid).apply();
            val agentsRoot = File(ctx.filesDir, ".agents")
            val env = File(agentsRoot, "skills/irc-cli/secrets/.env")
            env.parentFile?.mkdirs()
            env.writeText(
                """
                IRC_SERVER=example.com
                IRC_PORT=6697
                IRC_TLS=0
                IRC_CHANNEL=#test
                IRC_NICK=0123456789
                """.trimIndent() + "\n",
                Charsets.UTF_8,
            );
            {
                prefs.edit().remove(com.lsl.kotlin_agent_app.config.AppPrefsKeys.CHAT_SESSION_ID).apply()
                IrcClientTestHooks.clear()
                IrcSessionRuntimeStore.clearForTest()
            }
        },
    ) { tool ->
        val out = tool.exec("irc status")
        assertTrue(out.exitCode != 0)
        assertEquals("NickTooLong", out.errorCode)
    }

    @Test
    fun irc_send_requiresConfirm_forNonDefaultTarget() = runTest(
        setup = { ctx ->
            val prefs = ctx.getSharedPreferences("kotlin-agent-app", android.content.Context.MODE_PRIVATE)
            val sid = "sess_irc_" + UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString(com.lsl.kotlin_agent_app.config.AppPrefsKeys.CHAT_SESSION_ID, sid).apply();
            CapturingIrcClient.connectCalls = 0
            CapturingIrcClient.last = null
            val agentsRoot = File(ctx.filesDir, ".agents")
            val env = File(agentsRoot, "skills/irc-cli/secrets/.env")
            env.parentFile?.mkdirs()
            env.writeText(
                """
                IRC_SERVER=example.com
                IRC_PORT=6697
                IRC_TLS=0
                IRC_CHANNEL=#default
                IRC_NICK=lemonbot
                """.trimIndent() + "\n",
                Charsets.UTF_8,
            )

            val fake = CapturingIrcClient()
            IrcClientTestHooks.install { _: IrcConfig, _: kotlinx.coroutines.CoroutineScope, listener: IrcClientListener ->
                fake.listener = listener
                fake
            };

            {
                prefs.edit().remove(com.lsl.kotlin_agent_app.config.AppPrefsKeys.CHAT_SESSION_ID).apply()
                IrcClientTestHooks.clear()
                IrcSessionRuntimeStore.clearForTest()
            }
        },
    ) { tool ->
        val out = tool.exec("irc send --to #other --text-stdin", stdin = "hi")
        assertTrue(out.exitCode != 0)
        assertEquals("ConfirmRequired", out.errorCode)
    }

    @Test
    fun irc_send_reusesConnection_acrossToolInstances_inSameSession() = runTest(
        setup = { ctx ->
            val prefs = ctx.getSharedPreferences("kotlin-agent-app", android.content.Context.MODE_PRIVATE)
            val sid = "sess_irc_" + UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString(com.lsl.kotlin_agent_app.config.AppPrefsKeys.CHAT_SESSION_ID, sid).apply();
            CapturingIrcClient.connectCalls = 0
            CapturingIrcClient.last = null
            val agentsRoot = File(ctx.filesDir, ".agents")
            val env = File(agentsRoot, "skills/irc-cli/secrets/.env")
            env.parentFile?.mkdirs()
            env.writeText(
                """
                IRC_SERVER=example.com
                IRC_PORT=6697
                IRC_TLS=0
                IRC_CHANNEL=#default
                IRC_NICK=lemonbot
                """.trimIndent() + "\n",
                Charsets.UTF_8,
            )

            val fake = CapturingIrcClient()
            IrcClientTestHooks.install { _: IrcConfig, _: kotlinx.coroutines.CoroutineScope, listener: IrcClientListener ->
                fake.listener = listener
                fake
            };

            {
                prefs.edit().remove(com.lsl.kotlin_agent_app.config.AppPrefsKeys.CHAT_SESSION_ID).apply()
                IrcClientTestHooks.clear()
                IrcSessionRuntimeStore.clearForTest()
            }
        },
    ) { tool ->
        val a = tool.exec("irc send --text-stdin", stdin = "one")
        assertEquals(0, a.exitCode)

        val b = tool.execWithFreshTool("irc send --text-stdin", stdin = "two")
        assertEquals(0, b.exitCode)

        val c = tool.execWithFreshTool("irc send --text-stdin", stdin = "three")
        assertEquals(0, c.exitCode)

        assertEquals(1, CapturingIrcClient.connectCalls)
    }

    @Test
    fun irc_pull_cursor_dedup_perChannel_and_peek() = runTest(
        setup = { ctx ->
            val prefs = ctx.getSharedPreferences("kotlin-agent-app", android.content.Context.MODE_PRIVATE)
            val sid = "sess_irc_" + UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString(com.lsl.kotlin_agent_app.config.AppPrefsKeys.CHAT_SESSION_ID, sid).apply();
            CapturingIrcClient.connectCalls = 0
            CapturingIrcClient.last = null
            val agentsRoot = File(ctx.filesDir, ".agents")
            val env = File(agentsRoot, "skills/irc-cli/secrets/.env")
            env.parentFile?.mkdirs()
            env.writeText(
                """
                IRC_SERVER=example.com
                IRC_PORT=6697
                IRC_TLS=0
                IRC_CHANNEL=#default
                IRC_NICK=lemonbot
                """.trimIndent() + "\n",
                Charsets.UTF_8,
            )

            val fake = CapturingIrcClient()
            IrcClientTestHooks.install { _: IrcConfig, _: kotlinx.coroutines.CoroutineScope, listener: IrcClientListener ->
                fake.listener = listener
                fake
            };

            {
                prefs.edit().remove(com.lsl.kotlin_agent_app.config.AppPrefsKeys.CHAT_SESSION_ID).apply()
                IrcClientTestHooks.clear()
                IrcSessionRuntimeStore.clearForTest()
            }
        },
    ) { tool ->
        // Ensure runtime exists.
        assertEquals(0, tool.exec("irc status").exitCode)

        CapturingIrcClient.last!!.emitPrivmsg("#default", "a", "hello")
        CapturingIrcClient.last!!.emitPrivmsg("#default", "b", "world")
        CapturingIrcClient.last!!.emitPrivmsg("#other", "c", "x")

        val peek = tool.exec("irc pull --from #default --limit 10 --peek")
        assertEquals(0, peek.exitCode)
        val peekMessages = peek.result!!.get("messages")!!.jsonArray
        assertEquals(2, peekMessages.size)

        val first = tool.exec("irc pull --from #default --limit 10")
        assertEquals(0, first.exitCode)
        val firstMessages = first.result!!.get("messages")!!.jsonArray
        assertEquals(2, firstMessages.size)

        val second = tool.exec("irc pull --from #default --limit 10")
        assertEquals(0, second.exitCode)
        val secondMessages = second.result!!.get("messages")!!.jsonArray
        assertEquals(0, secondMessages.size)

        val other = tool.exec("irc pull --from #other --limit 10")
        assertEquals(0, other.exitCode)
        val otherMessages = other.result!!.get("messages")!!.jsonArray
        assertEquals(1, otherMessages.size)
    }

    @Test
    fun irc_pull_truncates_and_audit_doesNotContainSecrets() = runTest(
        setup = { ctx ->
            val prefs = ctx.getSharedPreferences("kotlin-agent-app", android.content.Context.MODE_PRIVATE)
            val sid = "sess_irc_" + UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString(com.lsl.kotlin_agent_app.config.AppPrefsKeys.CHAT_SESSION_ID, sid).apply();
            CapturingIrcClient.connectCalls = 0
            CapturingIrcClient.last = null
            val agentsRoot = File(ctx.filesDir, ".agents")
            val env = File(agentsRoot, "skills/irc-cli/secrets/.env")
            env.parentFile?.mkdirs()
            env.writeText(
                """
                IRC_SERVER=example.com
                IRC_PORT=6697
                IRC_TLS=0
                IRC_CHANNEL=#default
                IRC_NICK=lemonbot
                IRC_SERVER_PASSWORD=supersecret
                IRC_CHANNEL_KEY=chansecret
                IRC_NICKSERV_PASSWORD=nicksecret
                """.trimIndent() + "\n",
                Charsets.UTF_8,
            )

            val fake = CapturingIrcClient()
            IrcClientTestHooks.install { _: IrcConfig, _: kotlinx.coroutines.CoroutineScope, listener: IrcClientListener ->
                fake.listener = listener
                fake
            };

            {
                prefs.edit().remove(com.lsl.kotlin_agent_app.config.AppPrefsKeys.CHAT_SESSION_ID).apply()
                IrcClientTestHooks.clear()
                IrcSessionRuntimeStore.clearForTest()
            }
        },
    ) { tool ->
        val status = tool.exec("irc status")
        assertEquals(0, status.exitCode)
        val runId = status.runId
        val auditPath = File(tool.filesDir, ".agents/artifacts/terminal_exec/runs/$runId.json")
        assertTrue(auditPath.exists())
        val auditText = auditPath.readText(Charsets.UTF_8)
        assertTrue(!auditText.contains("supersecret"))
        assertTrue(!auditText.contains("chansecret"))
        assertTrue(!auditText.contains("nicksecret"))

        // Ensure runtime exists and push lots of long inbound messages to force truncation.
        val longText = "x".repeat(2000)
        repeat(60) { i ->
            CapturingIrcClient.last!!.emitPrivmsg("#default", "n$i", longText + i.toString())
        }
        val pull = tool.exec("irc pull --from #default --limit 200")
        assertEquals(0, pull.exitCode)
        val truncated = (pull.result!!["truncated"] as JsonPrimitive).content.toBoolean()
        assertTrue(truncated)
        val texts =
            pull.result!!.get("messages")!!.jsonArray.map { el ->
                el.jsonObject["text"]!!.jsonPrimitive.content
            }
        assertTrue(texts.any { it.contains("[...TRUNCATED...]") })
    }

    @Test
    fun rss_add_writesSubscriptions_and_list_readsBack() = runTest { tool ->
        val add = tool.exec("rss add --name test-feed --url https://example.com/feed.xml")
        assertEquals(0, add.exitCode)
        assertEquals("rss add", add.result?.get("command")?.let { (it as JsonPrimitive).content })

        val subsPath = File(tool.filesDir, ".agents/workspace/rss/subscriptions.json")
        assertTrue("subscriptions should exist: $subsPath", subsPath.exists())
        val subsText = subsPath.readText(Charsets.UTF_8)
        assertTrue(subsText.contains("test-feed"))
        assertTrue(subsText.contains("https://example.com/feed.xml"))

        val list = tool.exec("rss list --max 10")
        assertEquals(0, list.exitCode)
        assertEquals("rss list", list.result?.get("command")?.let { (it as JsonPrimitive).content })
        val items = list.result?.get("items")?.jsonArray
        assertNotNull(items)
        assertTrue(items!!.isNotEmpty())
        val first = items.first().jsonObject
        assertEquals("test-feed", (first["name"] as? JsonPrimitive)?.content)
        assertEquals("https://example.com/feed.xml", (first["url"] as? JsonPrimitive)?.content)
    }

    @Test
    fun rss_remove_missing_isNotFound() = runTest { tool ->
        val out = tool.exec("rss remove --name no_such_feed")
        assertTrue(out.exitCode != 0)
        assertEquals("NotFound", out.errorCode)
    }

    @Test
    fun rss_fetch_fileScheme_isRejected() = runTest { tool ->
        val out = tool.exec("rss fetch --url file:///etc/passwd --max-items 5")
        assertTrue(out.exitCode != 0)
        assertEquals("InvalidArgs", out.errorCode)
    }

    @Test
    fun rss_fetch_429_isRateLimited_andReturnsRetryAfterMs() {
        var transport: CapturingRssTransport? = null
        runTest(
            setup = {
                transport =
                    CapturingRssTransport(
                        statusCode = 429,
                        bodyText = "rate limited",
                        headers = mapOf("Retry-After" to "5"),
                    )
                RssClientTestHooks.install(transport!!)
                val teardown = { RssClientTestHooks.clear() }
                teardown
            },
        ) { tool ->
            val out = tool.exec("rss fetch --url https://example.com/feed.xml --max-items 5")
            assertTrue(out.exitCode != 0)
            assertEquals("RateLimited", out.errorCode)
            assertEquals("5000", (out.result?.get("retry_after_ms") as? JsonPrimitive)?.content)
        }
    }

    @Test
    fun rss_fetch_withOut_writesItems_andUpdatesFetchState_andUsesEtagOnSecondFetch() {
        var transport: CapturingRssTransport? = null
        val rssXml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Test Feed</title>
                <item>
                  <title>Item 1</title>
                  <link>https://example.com/1</link>
                  <guid>1</guid>
                  <pubDate>Wed, 18 Feb 2026 01:30:32 +0000</pubDate>
                  <description>Summary 1</description>
                </item>
                <item>
                  <title>Item 2</title>
                  <link>https://example.com/2</link>
                  <guid>2</guid>
                  <pubDate>Wed, 18 Feb 2026 02:30:32 +0000</pubDate>
                  <description>Summary 2</description>
                </item>
              </channel>
            </rss>
            """.trimIndent()

        runTest(
            setup = {
                transport =
                    CapturingRssTransport(
                        statusCode = 200,
                        bodyText = rssXml,
                        headers = mapOf("ETag" to "W/\"abc\"", "Last-Modified" to "Wed, 18 Feb 2026 01:30:32 GMT"),
                    )
                RssClientTestHooks.install(transport!!)
                val teardown = { RssClientTestHooks.clear() }
                teardown
            },
        ) { tool ->
            val add = tool.exec("rss add --name test-feed --url https://example.com/feed.xml")
            assertEquals(0, add.exitCode)

            val fetch1 = tool.exec("rss fetch --name test-feed --max-items 2 --out artifacts/rss/test-items.json")
            assertEquals(0, fetch1.exitCode)
            assertTrue(fetch1.artifacts.contains(".agents/artifacts/rss/test-items.json"))

            val outFile = File(tool.filesDir, ".agents/artifacts/rss/test-items.json")
            assertTrue(outFile.exists())
            val outText = outFile.readText(Charsets.UTF_8)
            assertTrue(outText.contains("Item 1"))
            assertTrue(outText.contains("https://example.com/1"))

            val stateFile = File(tool.filesDir, ".agents/workspace/rss/fetch_state.json")
            assertTrue(stateFile.exists())
            val stateText = stateFile.readText(Charsets.UTF_8)
            assertTrue(stateText.contains("W/\\\"abc\\\"") || stateText.contains("W/\"abc\""))

            // Second fetch should include If-None-Match based on stored etag (transport captures lastHeaders).
            tool.exec("rss fetch --name test-feed --max-items 1")
            val lastHeaders = transport?.lastHeaders.orEmpty()
            assertTrue("If-None-Match should be set", lastHeaders.keys.any { it.equals("If-None-Match", ignoreCase = true) })
        }
    }

    @Test
    fun stock_symbols_withoutOut_isRejectedWithOutRequired() = runTest { tool ->
        val out = tool.exec("stock symbols --exchange US")
        assertTrue(out.exitCode != 0)
        assertEquals("OutRequired", out.errorCode)
    }

    @Test
    fun exchangeRate_help_forms_exit0() = runTest { tool ->
        val forms =
            listOf(
                "exchange-rate --help",
                "exchange-rate help",
                "exchange-rate latest --help",
                "exchange-rate help latest",
                "exchange-rate convert --help",
                "exchange-rate help convert",
            )
        for (cmd in forms) {
            val out = tool.exec(cmd)
            assertEquals("help should exit 0 for: $cmd", 0, out.exitCode)
            val r = out.result ?: error("missing result for: $cmd")
            assertEquals(true, r["ok"]!!.jsonPrimitive.content.toBooleanStrict())
            assertTrue("stdout should be non-empty for: $cmd", out.stdout.isNotBlank())
        }
    }

    @Test
    fun exchangeRate_latest_withSymbols_onlyReturnsRequestedKeys() {
        var transport: CapturingExchangeRateTransport? = null
        runTest(
            setup = { context ->
                val cache = File(context.filesDir, ".agents/cache/exchange-rate/latest-CNY.json")
                if (cache.exists()) cache.delete()

                transport =
                    CapturingExchangeRateTransport(
                        statusCode = 200,
                        bodyText = fakeExchangeRateLatestCnyJson(nextUpdateUtc = "Tue, 18 Feb 2099 00:00:01 +0000"),
                        headers = emptyMap(),
                    )
                ExchangeRateClientTestHooks.install(transport!!)
                val teardown = { ExchangeRateClientTestHooks.clear() }
                teardown
            },
        ) { t ->
            val out = t.exec("exchange-rate latest --base CNY --symbols USD,EUR")
            assertEquals(0, out.exitCode)
            val r = out.result ?: error("missing result")
            assertEquals("exchange-rate latest", r["command"]!!.jsonPrimitive.content)
            val rates = r["rates"]!!.jsonObject
            assertEquals(setOf("USD", "EUR"), rates.keys)

            val url = transport!!.lastUrl
            assertNotNull(url)
            assertTrue(url!!.encodedPath.endsWith("/v6/latest/CNY"))
        }
    }

    @Test
    fun exchangeRate_latest_withOut_writesArtifact() {
        var transport: CapturingExchangeRateTransport? = null
        runTest(
            setup = { context ->
                val cache = File(context.filesDir, ".agents/cache/exchange-rate/latest-CNY.json")
                if (cache.exists()) cache.delete()

                val outFile = File(context.filesDir, ".agents/artifacts/exchange-rate/latest-CNY.json")
                if (outFile.exists()) outFile.delete()

                transport =
                    CapturingExchangeRateTransport(
                        statusCode = 200,
                        bodyText = fakeExchangeRateLatestCnyJson(nextUpdateUtc = "Tue, 18 Feb 2099 00:00:01 +0000"),
                        headers = emptyMap(),
                    )
                ExchangeRateClientTestHooks.install(transport!!)
                val teardown = { ExchangeRateClientTestHooks.clear() }
                teardown
            },
        ) { t ->
            val out = t.exec("exchange-rate latest --base CNY --out artifacts/exchange-rate/latest-CNY.json")
            assertEquals(0, out.exitCode)
            assertTrue(out.artifacts.contains(".agents/artifacts/exchange-rate/latest-CNY.json"))
            assertTrue(File(t.filesDir, ".agents/artifacts/exchange-rate/latest-CNY.json").exists())
        }
    }

    @Test
    fun exchangeRate_latest_usesCache_onSecondCall_andNoCacheForcesNetwork() {
        var transport: CapturingExchangeRateTransport? = null
        runTest(
            setup = { context ->
                val cache = File(context.filesDir, ".agents/cache/exchange-rate/latest-CNY.json")
                if (cache.exists()) cache.delete()

                transport =
                    CapturingExchangeRateTransport(
                        statusCode = 200,
                        bodyText = fakeExchangeRateLatestCnyJson(nextUpdateUtc = "Tue, 18 Feb 2099 00:00:01 +0000"),
                        headers = emptyMap(),
                    )
                ExchangeRateClientTestHooks.install(transport!!)
                val teardown = { ExchangeRateClientTestHooks.clear() }
                teardown
            },
        ) { tool ->
            val out1 = tool.exec("exchange-rate latest --base CNY --symbols USD")
            assertEquals(0, out1.exitCode)
            assertEquals(false, out1.result!!["cached"]!!.jsonPrimitive.content.toBooleanStrict())
            assertEquals(1, transport!!.callCount)

            val out2 = tool.exec("exchange-rate latest --base CNY --symbols USD")
            assertEquals(0, out2.exitCode)
            assertEquals(true, out2.result!!["cached"]!!.jsonPrimitive.content.toBooleanStrict())
            assertEquals(1, transport!!.callCount)

            val out3 = tool.exec("exchange-rate latest --base CNY --symbols USD --no-cache")
            assertEquals(0, out3.exitCode)
            assertEquals(false, out3.result!!["cached"]!!.jsonPrimitive.content.toBooleanStrict())
            assertEquals(2, transport!!.callCount)

            val out4 = tool.exec("exchange-rate latest --base CNY --symbols USD --no-cache")
            assertEquals(0, out4.exitCode)
            assertEquals(false, out4.result!!["cached"]!!.jsonPrimitive.content.toBooleanStrict())
            assertEquals(3, transport!!.callCount)
        }
    }

    @Test
    fun exchangeRate_remoteError_isMapped() {
        var transport: CapturingExchangeRateTransport? = null
        runTest(
            setup = { _ ->
                transport =
                    CapturingExchangeRateTransport(
                        statusCode = 200,
                        bodyText = """{"result":"error","error-type":"invalid-base"}""",
                        headers = emptyMap(),
                    )
                ExchangeRateClientTestHooks.install(transport!!)
                val teardown = { ExchangeRateClientTestHooks.clear() }
                teardown
            },
        ) { tool ->
            val out = tool.exec("exchange-rate latest --base XXX --symbols USD")
            assertTrue(out.exitCode != 0)
            assertEquals("RemoteError", out.errorCode)
        }
    }

    @Test
    fun exchangeRate_convert_invalidAmount_isRejected() = runTest { tool ->
        val out = tool.exec("exchange-rate convert --from CNY --to USD --amount abc")
        assertTrue(out.exitCode != 0)
        assertEquals("InvalidArgs", out.errorCode)
    }

    @Test
    fun stock_outPathWithDotDot_isRejected() = runTest { tool ->
        val out = tool.exec("stock symbols --exchange US --out ../pwn.json")
        assertTrue(out.exitCode != 0)
        assertEquals("PathEscapesAgentsRoot", out.errorCode)
    }

    @Test
    fun stock_missingCredentials_isRejected() = runTest { tool ->
        val out = tool.exec("stock quote --symbol AAPL")
        assertTrue(out.exitCode != 0)
        assertEquals("MissingCredentials", out.errorCode)
    }

    @Test
    fun stock_candle_isNotSupported() = runTest { tool ->
        val out = tool.exec("stock candle --symbol AAPL")
        assertTrue(out.exitCode != 0)
        assertEquals("NotSupported", out.errorCode)
    }

    @Test
    fun stock_quote_happyPath_makesRequest_andDoesNotLeakToken() {
        var transport: CapturingFinnhubTransport? = null
        val token = "TEST_FINNHUB_TOKEN_123"
        runTest(
            setup = { context ->
                val agentsRoot = File(context.filesDir, ".agents")
                val env = File(agentsRoot, "skills/stock-cli/secrets/.env")
                env.parentFile?.mkdirs()
                env.writeText("FINNHUB_API_KEY=$token\n", Charsets.UTF_8)

                transport =
                    CapturingFinnhubTransport(
                        statusCode = 200,
                        bodyText = """{"c":123.45,"h":124.0,"l":120.0,"o":121.0,"pc":122.0,"t":1700000000}""",
                        headers = emptyMap(),
                    )
                FinnhubClientTestHooks.install(transport!!)
                val teardown = { FinnhubClientTestHooks.clear() }
                teardown
            },
        ) { tool ->
            val out = tool.exec("stock quote --symbol AAPL")
            assertEquals(0, out.exitCode)
            assertTrue(out.stdout.contains("AAPL"))
            assertTrue("stdout must not contain token", !out.stdout.contains(token))

            val result = out.result
            assertNotNull(result)
            assertEquals("stock quote", (result!!["command"] as? JsonPrimitive)?.content)
            assertEquals("AAPL", (result["symbol"] as? JsonPrimitive)?.content)

            val url = transport!!.lastUrl
            val headers = transport!!.lastHeaders
            assertNotNull(url)
            assertNotNull(headers)
            assertTrue(url!!.encodedPath.endsWith("/quote"))
            assertEquals("AAPL", url.queryParameter("symbol"))
            assertEquals(token, headers!!["X-Finnhub-Token"])
        }
    }

    @Test
    fun stock_symbols_withOut_writesArtifact_andCapturesRequest() {
        var transport: CapturingFinnhubTransport? = null
        val token = "TEST_FINNHUB_TOKEN_123"
        runTest(
            setup = { context ->
                val agentsRoot = File(context.filesDir, ".agents")
                val env = File(agentsRoot, "skills/stock-cli/secrets/.env")
                env.parentFile?.mkdirs()
                env.writeText("FINNHUB_API_KEY=$token\n", Charsets.UTF_8)

                transport =
                    CapturingFinnhubTransport(
                        statusCode = 200,
                        bodyText = """[{"symbol":"AAPL"},{"symbol":"MSFT"}]""",
                        headers = emptyMap(),
                    )
                FinnhubClientTestHooks.install(transport!!)
                val teardown = { FinnhubClientTestHooks.clear() }
                teardown
            },
        ) { tool ->
            val out = tool.exec("stock symbols --exchange US --out artifacts/stock/symbols-us.json")
            assertEquals(0, out.exitCode)
            assertTrue(out.artifacts.contains(".agents/artifacts/stock/symbols-us.json"))
            assertTrue(File(tool.filesDir, ".agents/artifacts/stock/symbols-us.json").exists())

            val result = out.result
            assertNotNull(result)
            assertEquals("stock symbols", (result!!["command"] as? JsonPrimitive)?.content)
            assertEquals("US", (result["exchange"] as? JsonPrimitive)?.content)
            assertEquals("2", (result["count_total"] as? JsonPrimitive)?.content)

            val url = transport!!.lastUrl
            val headers = transport!!.lastHeaders
            assertNotNull(url)
            assertNotNull(headers)
            assertTrue(url!!.encodedPath.endsWith("/stock/symbol"))
            assertEquals("US", url.queryParameter("exchange"))
            assertEquals(token, headers!!["X-Finnhub-Token"])
        }
    }

    @Test
    fun stock_quote_http429_includesRetryAfterMs_whenProvided() {
        var transport: CapturingFinnhubTransport? = null
        val token = "TEST_FINNHUB_TOKEN_123"
        runTest(
            setup = { context ->
                val agentsRoot = File(context.filesDir, ".agents")
                val env = File(agentsRoot, "skills/stock-cli/secrets/.env")
                env.parentFile?.mkdirs()
                env.writeText("FINNHUB_API_KEY=$token\n", Charsets.UTF_8)

                transport =
                    CapturingFinnhubTransport(
                        statusCode = 429,
                        bodyText = "",
                        headers = mapOf("Retry-After" to "2"),
                    )
                FinnhubClientTestHooks.install(transport!!)
                val teardown = { FinnhubClientTestHooks.clear() }
                teardown
            },
        ) { tool ->
            val out = tool.exec("stock quote --symbol AAPL")
            assertTrue(out.exitCode != 0)
            assertEquals("RateLimited", out.errorCode)
            val retryAfterMs = (out.result?.get("retry_after_ms") as? JsonPrimitive)?.content?.toLongOrNull()
            assertEquals(2_000L, retryAfterMs)
        }
    }

    @Test
    fun agentsWorkspace_installsStockCliSkill_andSeedsEnv() = runTest { tool ->
        val skill = File(tool.filesDir, ".agents/skills/stock-cli/SKILL.md")
        assertTrue("stock-cli skill should exist: $skill", skill.exists())
        val env = File(tool.filesDir, ".agents/skills/stock-cli/secrets/.env")
        assertTrue("stock-cli .env should exist: $env", env.exists())
    }

    @Test
    fun agentsWorkspace_installsExchangeRateCliSkill() = runTest { tool ->
        val skill = File(tool.filesDir, ".agents/skills/exchange-rate-cli/SKILL.md")
        assertTrue("exchange-rate-cli skill should exist: $skill", skill.exists())
    }

    @Test
    fun ledger_add_withoutInit_returnsNotInitialized() = runTest { tool ->
        val ledgerDir = File(tool.filesDir, ".agents/workspace/ledger")
        if (ledgerDir.exists()) ledgerDir.deleteRecursively()

        val out = tool.exec("ledger add --type expense --amount 12.34 --category 餐饮 --account 微信")
        assertTrue(out.exitCode != 0)
        assertEquals("NotInitialized", out.errorCode)
    }

    @Test
    fun ledger_init_createsWorkspaceFiles_andSecondInitRequiresConfirm() = runTest { tool ->
        val ledgerDir = File(tool.filesDir, ".agents/workspace/ledger")
        if (ledgerDir.exists()) ledgerDir.deleteRecursively()

        val init = tool.exec("ledger init")
        assertEquals(0, init.exitCode)

        val meta = File(tool.filesDir, ".agents/workspace/ledger/meta.json")
        val categories = File(tool.filesDir, ".agents/workspace/ledger/categories.json")
        val accounts = File(tool.filesDir, ".agents/workspace/ledger/accounts.json")
        val tx = File(tool.filesDir, ".agents/workspace/ledger/transactions.jsonl")
        assertTrue(meta.exists())
        assertTrue(categories.exists())
        assertTrue(accounts.exists())
        assertTrue(tx.exists())

        val again = tool.exec("ledger init")
        assertTrue(again.exitCode != 0)
        assertEquals("ConfirmRequired", again.errorCode)

        val confirmed = tool.exec("ledger init --confirm")
        assertEquals(0, confirmed.exitCode)
    }

    @Test
    fun ledger_add_appendsJsonl_and_listSupportsOutArtifact() = runTest { tool ->
        val ledgerDir = File(tool.filesDir, ".agents/workspace/ledger")
        if (ledgerDir.exists()) ledgerDir.deleteRecursively()

        val init = tool.exec("ledger init")
        assertEquals(0, init.exitCode)

        val add = tool.exec("ledger add --type expense --amount 10.00 --category 餐饮 --account 现金 --note 午饭 --at 2026-02-18T12:00:00+08:00")
        assertEquals(0, add.exitCode)

        val txFile = File(tool.filesDir, ".agents/workspace/ledger/transactions.jsonl")
        assertTrue(txFile.exists())
        val lines = txFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertTrue("transactions.jsonl should have >= 1 line", lines.isNotEmpty())
        val parsed = Json.parseToJsonElement(lines.first())
        assertTrue("transactions.jsonl line should be JSON object", parsed is JsonObject)

        val list = tool.exec("ledger list --max 10")
        assertEquals(0, list.exitCode)
        assertTrue(list.artifacts.isEmpty())
        assertEquals("ledger list", (list.result?.get("command") as? JsonPrimitive)?.content)

        val outRel = "artifacts/ledger/test-list.json"
        val listOut = tool.exec("ledger list --out $outRel")
        assertEquals(0, listOut.exitCode)
        assertTrue(listOut.artifacts.contains(".agents/$outRel"))

        val outFile = File(tool.filesDir, ".agents/$outRel")
        assertTrue(outFile.exists())
        assertTrue(outFile.readText(Charsets.UTF_8).contains("\"count_total\""))
    }

    @Test
    fun ledger_summarySupportsOutArtifact_andRejectsPathTraversal() = runTest { tool ->
        val ledgerDir = File(tool.filesDir, ".agents/workspace/ledger")
        if (ledgerDir.exists()) ledgerDir.deleteRecursively()

        val init = tool.exec("ledger init")
        assertEquals(0, init.exitCode)

        val addExpense = tool.exec("ledger add --type expense --amount 10.00 --category 餐饮 --account 现金 --at 2026-02-18T12:00:00+08:00")
        assertEquals(0, addExpense.exitCode)
        val addIncome = tool.exec("ledger add --type income --amount 20.00 --category 工资 --account 银行卡 --at 2026-02-01T09:00:00+08:00")
        assertEquals(0, addIncome.exitCode)

        val outRel = "artifacts/ledger/test-summary.json"
        val summary = tool.exec("ledger summary --month 2026-02 --by category --out $outRel")
        assertEquals(0, summary.exitCode)
        assertTrue(summary.artifacts.contains(".agents/$outRel"))
        assertEquals(1000L, (summary.result?.get("expense_total_fen") as? JsonPrimitive)?.content?.toLongOrNull())
        assertEquals(2000L, (summary.result?.get("income_total_fen") as? JsonPrimitive)?.content?.toLongOrNull())

        val traversal = tool.exec("ledger list --out ../escape.json")
        assertTrue(traversal.exitCode != 0)
        assertEquals("PathEscapesAgentsRoot", traversal.errorCode)
    }

    @Test
    fun cal_listCalendars_withoutReadPermission_returnsPermissionDenied() = runTest(
        setup = {
            CalCommandTestHooks.install(
                store = InMemoryCalendarStore(),
                permissions = FakeCalendarPermissionChecker(read = false, write = false),
            );
            { CalCommandTestHooks.clear() }
        },
    ) { tool ->
        val out = tool.exec("cal list-calendars")
        assertTrue(out.exitCode != 0)
        assertEquals("PermissionDenied", out.errorCode)
    }

    @Test
    fun cal_listCalendars_withReadPermission_supportsOutArtifact() = runTest(
        setup = {
            val store =
                InMemoryCalendarStore().apply {
                    addCalendar(id = 1, displayName = "Personal", accountName = "me", accountType = "local")
                    addCalendar(id = 2, displayName = "Work", accountName = "me", accountType = "local")
                }
            CalCommandTestHooks.install(
                store = store,
                permissions = FakeCalendarPermissionChecker(read = true, write = false),
            );
            { CalCommandTestHooks.clear() }
        },
    ) { tool ->
        val outRel = "artifacts/cal/test-calendars.json"
        val out = tool.exec("cal list-calendars --max 1 --out $outRel")
        assertEquals(0, out.exitCode)
        assertTrue(out.artifacts.contains(".agents/$outRel"))

        val outFile = File(tool.filesDir, ".agents/$outRel")
        assertTrue(outFile.exists())
        assertTrue(outFile.readText(Charsets.UTF_8).contains("\"count_total\""))
    }

    @Test
    fun cal_listEvents_withReadPermission_supportsOutArtifact() = runTest(
        setup = {
            val store =
                InMemoryCalendarStore().apply {
                    addCalendar(id = 1, displayName = "Personal", accountName = "me", accountType = "local")
                }
            CalCommandTestHooks.install(
                store = store,
                permissions = FakeCalendarPermissionChecker(read = true, write = true),
            );
            { CalCommandTestHooks.clear() }
        },
    ) { tool ->
        val created =
            tool.exec(
                "cal create-event --calendar-id 1 --title \"Demo\" --start 2026-02-18T10:00:00Z --end 2026-02-18T11:00:00Z --confirm",
            )
        assertEquals(0, created.exitCode)

        val outRel = "artifacts/cal/test-events.json"
        val listed =
            tool.exec(
                "cal list-events --from 2026-02-18T00:00:00Z --to 2026-02-19T00:00:00Z --out $outRel",
            )
        assertEquals(0, listed.exitCode)
        assertTrue(listed.artifacts.contains(".agents/$outRel"))

        val outFile = File(tool.filesDir, ".agents/$outRel")
        assertTrue(outFile.exists())
        assertTrue(outFile.readText(Charsets.UTF_8).contains("\"events\""))
    }

    @Test
    fun cal_createEvent_withoutConfirm_isRejected() = runTest(
        setup = {
            CalCommandTestHooks.install(
                store = InMemoryCalendarStore(),
                permissions = FakeCalendarPermissionChecker(read = true, write = true),
            );
            { CalCommandTestHooks.clear() }
        },
    ) { tool ->
        val out =
            tool.exec(
                "cal create-event --calendar-id 1 --title \"t\" --start 2026-02-18T10:00:00Z --end 2026-02-18T11:00:00Z",
            )
        assertTrue(out.exitCode != 0)
        assertEquals("ConfirmRequired", out.errorCode)
    }

    @Test
    fun cal_createUpdateAddReminderDelete_happyPath() = runTest(
        setup = {
            val store =
                InMemoryCalendarStore().apply {
                    addCalendar(id = 1, displayName = "Personal", accountName = "me", accountType = "local")
                }
            CalCommandTestHooks.install(
                store = store,
                permissions = FakeCalendarPermissionChecker(read = true, write = true),
            );
            { CalCommandTestHooks.clear() }
        },
    ) { tool ->
        val created =
            tool.exec(
                "cal create-event --calendar-id 1 --title \"Demo\" --start 2026-02-18T10:00:00Z --end 2026-02-18T11:00:00Z --remind-minutes 15 --confirm",
            )
        assertEquals(0, created.exitCode)
        val eventId = (created.result?.get("event_id") as? JsonPrimitive)?.content?.toLongOrNull()
        assertTrue("expected event_id", (eventId ?: 0L) > 0L)

        val updated =
            tool.exec(
                "cal update-event --event-id $eventId --location \"Room\" --confirm",
            )
        assertEquals(0, updated.exitCode)

        val reminder =
            tool.exec(
                "cal add-reminder --event-id $eventId --minutes 30 --confirm",
            )
        assertEquals(0, reminder.exitCode)

        val listed =
            tool.exec(
                "cal list-events --from 2026-02-18T00:00:00Z --to 2026-02-19T00:00:00Z --max 50",
            )
        assertEquals(0, listed.exitCode)
        val events = listed.result?.get("events")?.jsonArray
        assertTrue("expected at least 1 event", (events?.size ?: 0) >= 1)

        val deleted =
            tool.exec(
                "cal delete-event --event-id $eventId --confirm",
            )
        assertEquals(0, deleted.exitCode)
    }

    @Test
    fun git_init_status_add_commit_log_happyPath() = runTest { tool ->
        val repoName = "jgit-demo-" + UUID.randomUUID().toString().replace("-", "").take(8)
        val repoRel = "workspace/$repoName"

        // init repo
        val initOut = tool.exec("git init --dir $repoRel")
        assertEquals(0, initOut.exitCode)

        // create a new file (untracked)
        val repoDir = File(initOut.filesDir, ".agents/$repoRel")
        assertTrue(repoDir.exists())
        File(repoDir, "a.txt").writeText("hello", Charsets.UTF_8)

        val status1 = tool.exec("git status --repo $repoRel")
        assertEquals(0, status1.exitCode)
        assertTrue(status1.stdout.contains("untracked", ignoreCase = true))

        // add + commit
        val addOut = tool.exec("git add --repo $repoRel --all")
        assertEquals(0, addOut.exitCode)

        val commitOut = tool.exec("git commit --repo $repoRel --message \"init\"")
        assertEquals(0, commitOut.exitCode)

        val status2 = tool.exec("git status --repo $repoRel")
        assertEquals(0, status2.exitCode)
        assertTrue(status2.stdout.contains("clean", ignoreCase = true))

        val logOut = tool.exec("git log --repo $repoRel --max 1")
        assertEquals(0, logOut.exitCode)
        assertTrue(logOut.stdout.contains("init"))
    }

    @Test
    fun git_repoPathTraversal_isRejected() = runTest { tool ->
        val out = tool.exec("git status --repo ../")
        assertTrue(out.exitCode != 0)
        assertEquals("PathEscapesAgentsRoot", out.errorCode)
    }

    @Test
    fun git_branch_checkout_show_diff_reset_stash_happyPath() = runTest { tool ->
        val repoName = "jgit-v14-" + UUID.randomUUID().toString().replace("-", "").take(8)
        val repoRel = "workspace/$repoName"

        // init + first commit
        assertEquals(0, tool.exec("git init --dir $repoRel").exitCode)
        val repoDir = File(tool.filesDir, ".agents/$repoRel")
        File(repoDir, "a.txt").writeText("v1", Charsets.UTF_8)
        assertEquals(0, tool.exec("git add --repo $repoRel --all").exitCode)
        assertEquals(0, tool.exec("git commit --repo $repoRel --message \"c1\"").exitCode)

        // branch + checkout
        val co = tool.exec("git checkout --repo $repoRel --branch feat --create")
        assertEquals(0, co.exitCode)
        val branches = tool.exec("git branch --repo $repoRel")
        assertEquals(0, branches.exitCode)
        assertTrue(branches.stdout.contains("feat"))

        // second commit on feat
        File(repoDir, "a.txt").writeText("v2", Charsets.UTF_8)
        assertEquals(0, tool.exec("git add --repo $repoRel --all").exitCode)
        assertEquals(0, tool.exec("git commit --repo $repoRel --message \"c2\"").exitCode)

        // show HEAD
        val show = tool.exec("git show --repo $repoRel --commit HEAD --patch --max-chars 2000")
        assertEquals(0, show.exitCode)
        assertTrue(show.stdout.contains("c2"))

        // diff between commits
        val diff = tool.exec("git diff --repo $repoRel --from HEAD~1 --to HEAD --patch --max-chars 2000")
        assertEquals(0, diff.exitCode)
        assertTrue(diff.stdout.isNotBlank())

        // stash: dirty working tree then stash push/list/pop
        File(repoDir, "a.txt").writeText("dirty", Charsets.UTF_8)
        val stashPush = tool.exec("git stash push --repo $repoRel --message \"wip\"")
        assertEquals(0, stashPush.exitCode)
        val statusAfterStash = tool.exec("git status --repo $repoRel")
        assertEquals(0, statusAfterStash.exitCode)
        assertTrue(statusAfterStash.stdout.contains("clean", ignoreCase = true))

        val stashList = tool.exec("git stash list --repo $repoRel --max 5")
        assertEquals(0, stashList.exitCode)
        assertTrue(stashList.stdout.contains("wip"))

        val stashPop = tool.exec("git stash pop --repo $repoRel --index 0")
        assertEquals(0, stashPop.exitCode)
        val statusAfterPop = tool.exec("git status --repo $repoRel")
        assertEquals(0, statusAfterPop.exitCode)
        assertTrue(statusAfterPop.stdout.contains("not clean", ignoreCase = true))

        // reset: hard back one commit (from c2 to c1)
        val reset = tool.exec("git reset --repo $repoRel --mode hard --to HEAD~1")
        assertEquals(0, reset.exitCode)
        val log1 = tool.exec("git log --repo $repoRel --max 1")
        assertEquals(0, log1.exitCode)
        assertTrue(log1.stdout.contains("c1"))
    }

    @Test
    fun git_remoteClone_requiresConfirm() = runTest { tool ->
        val out =
            tool.exec(
                "git clone --remote https://example.com/repo.git --dir workspace/clone-no-confirm",
            )
        assertTrue(out.exitCode != 0)
        assertEquals("ConfirmRequired", out.errorCode)
    }

    @Test
    fun git_remoteClone_rejectsUserinfoUrl() = runTest { tool ->
        val out =
            tool.exec(
                "git clone --remote https://user:pass@example.com/repo.git --dir workspace/clone-bad --confirm",
            )
        assertTrue(out.exitCode != 0)
        assertEquals("InvalidRemoteUrl", out.errorCode)
    }

    @Test
    fun git_localRemote_clone_push_pull_happyPath() = runTest { tool ->
        val id = UUID.randomUUID().toString().replace("-", "").take(8)
        val originRel = "workspace/origin-$id.git"
        val clone1Rel = "workspace/clone1-$id"
        val clone2Rel = "workspace/clone2-$id"

        // Bare origin (needed for push)
        val initBare = tool.exec("git init --dir $originRel --bare")
        assertEquals(0, initBare.exitCode)

        // Clone1, commit, push
        assertEquals(0, tool.exec("git clone --local-remote $originRel --dir $clone1Rel --confirm").exitCode)
        val clone1Dir = File(tool.filesDir, ".agents/$clone1Rel")
        File(clone1Dir, "a.txt").writeText("v1", Charsets.UTF_8)
        assertEquals(0, tool.exec("git add --repo $clone1Rel --all").exitCode)
        assertEquals(0, tool.exec("git commit --repo $clone1Rel --message \"c1\"").exitCode)
        assertEquals(0, tool.exec("git push --repo $clone1Rel --confirm").exitCode)

        // Clone2 then pull after new push
        assertEquals(0, tool.exec("git clone --local-remote $originRel --dir $clone2Rel --confirm").exitCode)

        // New commit in clone1
        File(clone1Dir, "a.txt").writeText("v2", Charsets.UTF_8)
        assertEquals(0, tool.exec("git add --repo $clone1Rel --all").exitCode)
        assertEquals(0, tool.exec("git commit --repo $clone1Rel --message \"c2\"").exitCode)
        assertEquals(0, tool.exec("git push --repo $clone1Rel --confirm").exitCode)

        // Pull into clone2 and verify newest commit message appears in log
        assertEquals(0, tool.exec("git pull --repo $clone2Rel --confirm").exitCode)
        val log = tool.exec("git log --repo $clone2Rel --max 1")
        assertEquals(0, log.exitCode)
        assertTrue(log.stdout.contains("c2"))
    }

    @Test
    fun zip_create_requiresConfirm() = runTest { tool ->
        val srcRel = "workspace/zip-src-" + UUID.randomUUID().toString().replace("-", "").take(8)
        val srcDir = File(tool.filesDir, ".agents/$srcRel")
        srcDir.mkdirs()
        File(srcDir, "a.txt").writeText("hello", Charsets.UTF_8)

        val out = tool.exec("zip create --src $srcRel --out workspace/out.zip")
        assertTrue(out.exitCode != 0)
        assertEquals("ConfirmRequired", out.errorCode)
    }

    @Test
    fun zip_extract_blocksPathTraversalEntry() = runTest { tool ->
        val zipRel = "workspace/bad-" + UUID.randomUUID().toString().replace("-", "").take(8) + ".zip"
        val zipFile = File(tool.filesDir, ".agents/$zipRel")
        zipFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("../evil.txt"))
            zos.write("nope".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("ok.txt"))
            zos.write("ok".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val destRel = "workspace/unpack-" + UUID.randomUUID().toString().replace("-", "").take(8)
        val out = tool.exec("zip extract --in $zipRel --dest $destRel --confirm")
        assertEquals(0, out.exitCode)

        val escaped = File(tool.filesDir, ".agents/workspace/evil.txt")
        assertTrue("zip slip must not create escaped file: $escaped", !escaped.exists())

        val skipped = out.result?.get("skipped")?.jsonObject
        val unsafe = skipped?.get("unsafe_path") as? JsonPrimitive
        assertTrue("expected skipped.unsafe_path >= 1", (unsafe?.content?.toLongOrNull() ?: 0L) >= 1L)
    }

    @Test
    fun zip_extract_supportsEncodingFlag_forCp932Names() = runTest { tool ->
        val zipRel = "workspace/cp932-" + UUID.randomUUID().toString().replace("-", "").take(8) + ".zip"
        val zipFile = File(tool.filesDir, ".agents/$zipRel")
        zipFile.parentFile?.mkdirs()

        val entryName = "残酷な天使のテーゼ.txt"
        ZipArchiveOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.setEncoding("windows-31j")
            zos.setUseLanguageEncodingFlag(false)
            val e = ZipArchiveEntry(entryName)
            zos.putArchiveEntry(e)
            zos.write("ok".toByteArray(Charsets.UTF_8))
            zos.closeArchiveEntry()
            zos.finish()
        }

        val destRel = "workspace/unpack-" + UUID.randomUUID().toString().replace("-", "").take(8)
        val out = tool.exec("zip extract --in $zipRel --dest $destRel --confirm --encoding cp932")
        assertEquals(0, out.exitCode)

        val extracted = File(tool.filesDir, ".agents/$destRel/$entryName")
        assertTrue("expected decoded filename to be preserved: $extracted", extracted.exists())
        assertEquals("ok", extracted.readText(Charsets.UTF_8))
    }

    @Test
    fun zip_extract_autoEncoding_canHandleGbkEncodedJapaneseNames() = runTest { tool ->
        val zipRel = "workspace/gbk-" + UUID.randomUUID().toString().replace("-", "").take(8) + ".zip"
        val zipFile = File(tool.filesDir, ".agents/$zipRel")
        zipFile.parentFile?.mkdirs()

        val entryName = "残酷な天使のテーゼ.txt"
        ZipArchiveOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.setEncoding("GBK")
            zos.setUseLanguageEncodingFlag(false)
            val e = ZipArchiveEntry(entryName)
            zos.putArchiveEntry(e)
            zos.write("ok".toByteArray(Charsets.UTF_8))
            zos.closeArchiveEntry()
            zos.finish()
        }

        val destRel = "workspace/unpack-" + UUID.randomUUID().toString().replace("-", "").take(8)
        val out = tool.exec("zip extract --in $zipRel --dest $destRel --confirm --encoding auto")
        assertEquals(0, out.exitCode)

        val extracted = File(tool.filesDir, ".agents/$destRel/$entryName")
        assertTrue("expected decoded filename to be preserved: $extracted", extracted.exists())
        assertEquals("ok", extracted.readText(Charsets.UTF_8))
    }

    @Test
    fun zip_list_supportsOutArtifact() = runTest { tool ->
        val zipRel = "workspace/many-" + UUID.randomUUID().toString().replace("-", "").take(8) + ".zip"
        val zipFile = File(tool.filesDir, ".agents/$zipRel")
        zipFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            for (i in 0 until 250) {
                zos.putNextEntry(ZipEntry("f/$i.txt"))
                zos.write("x".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }

        val outRel = "artifacts/archive/zip-list-" + UUID.randomUUID().toString().replace("-", "").take(8) + ".json"
        val out = tool.exec("zip list --in $zipRel --max 5 --out $outRel")
        assertEquals(0, out.exitCode)
        assertTrue(out.artifacts.any { it.endsWith("/$outRel") })

        val outFile = File(tool.filesDir, ".agents/$outRel")
        assertTrue("list --out file should exist: $outFile", outFile.exists())
        val text = outFile.readText(Charsets.UTF_8)
        assertTrue(text.contains("\"count_total\""))
        assertTrue(text.contains("\"entries\""))
    }

    @Test
    fun tar_create_requiresConfirm() = runTest { tool ->
        val srcRel = "workspace/tar-src-" + UUID.randomUUID().toString().replace("-", "").take(8)
        val srcDir = File(tool.filesDir, ".agents/$srcRel")
        srcDir.mkdirs()
        File(srcDir, "a.txt").writeText("hello", Charsets.UTF_8)

        val out = tool.exec("tar create --src $srcRel --out workspace/out.tar")
        assertTrue(out.exitCode != 0)
        assertEquals("ConfirmRequired", out.errorCode)
    }

    @Test
    fun tar_extract_blocksPathTraversalEntry() = runTest { tool ->
        val tarRel = "workspace/bad-" + UUID.randomUUID().toString().replace("-", "").take(8) + ".tar"
        val tarFile = File(tool.filesDir, ".agents/$tarRel")
        tarFile.parentFile?.mkdirs()

        TarArchiveOutputStream(FileOutputStream(tarFile)).use { tout ->
            tout.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tout.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)

            val evil = TarArchiveEntry("../evil.txt")
            val evilBytes = "nope".toByteArray(Charsets.UTF_8)
            evil.size = evilBytes.size.toLong()
            tout.putArchiveEntry(evil)
            tout.write(evilBytes)
            tout.closeArchiveEntry()

            val ok = TarArchiveEntry("ok.txt")
            val okBytes = "ok".toByteArray(Charsets.UTF_8)
            ok.size = okBytes.size.toLong()
            tout.putArchiveEntry(ok)
            tout.write(okBytes)
            tout.closeArchiveEntry()

            tout.finish()
        }

        val destRel = "workspace/unpack-" + UUID.randomUUID().toString().replace("-", "").take(8)
        val out = tool.exec("tar extract --in $tarRel --dest $destRel --confirm")
        assertEquals(0, out.exitCode)

        val escaped = File(tool.filesDir, ".agents/workspace/evil.txt")
        assertTrue("tar slip must not create escaped file: $escaped", !escaped.exists())

        val skipped = out.result?.get("skipped")?.jsonObject
        val unsafe = skipped?.get("unsafe_path") as? JsonPrimitive
        assertTrue("expected skipped.unsafe_path >= 1", (unsafe?.content?.toLongOrNull() ?: 0L) >= 1L)
    }

    @Test
    fun qqmail_send_withoutConfirm_isRejected() = runTest { tool ->
        val out = tool.exec("qqmail send --to a@example.com --subject hi --body-stdin")
        assertTrue(out.exitCode != 0)
        assertEquals("ConfirmRequired", out.errorCode)
    }

    @Test
    fun qqmail_fetch_missingCredentials_isRejected() = runTest(
        setup = { context ->
            val agentsRoot = File(context.filesDir, ".agents")
            val env = File(agentsRoot, "skills/qqmail-cli/secrets/.env")
            env.parentFile?.mkdirs()
            env.writeText(
                """
                EMAIL_ADDRESS=
                EMAIL_PASSWORD=
                """.trimIndent() + "\n",
                Charsets.UTF_8,
            )
            val teardown = { }
            teardown
        },
    ) { tool ->
        val out = tool.exec("qqmail fetch")
        assertTrue(out.exitCode != 0)
        assertEquals("MissingCredentials", out.errorCode)
    }

    @Test
    fun qqmail_fetch_writesMarkdown_andSupportsOutArtifact_andDedupesByMessageId() = runTest(
        setup = { context ->
            val agentsRoot = File(context.filesDir, ".agents")
            val env = File(agentsRoot, "skills/qqmail-cli/secrets/.env")
            env.parentFile?.mkdirs()
            env.writeText(
                """
                EMAIL_ADDRESS=test@qq.com
                EMAIL_PASSWORD=SUPER_SECRET_AUTH_CODE
                SMTP_SERVER=smtp.qq.com
                SMTP_PORT=465
                IMAP_SERVER=imap.qq.com
                IMAP_PORT=993
                """.trimIndent() + "\n",
                Charsets.UTF_8,
            )

            val fakeImap =
                object : QqMailImapClient {
                    override suspend fun fetchLatest(
                        folder: String,
                        limit: Int,
                    ): List<QqMailMessage> {
                        return listOf(
                            QqMailMessage(
                                folder = folder,
                                messageId = "<m1@test>",
                                subject = "Hello 1",
                                from = "a@test",
                                to = "test@qq.com",
                                dateMs = 1_700_000_000_000L,
                                bodyText = "Body 1",
                            ),
                            QqMailMessage(
                                folder = folder,
                                messageId = "<m2@test>",
                                subject = "Hello 2",
                                from = "b@test",
                                to = "test@qq.com",
                                dateMs = 1_700_000_000_001L,
                                bodyText = "Body 2",
                            ),
                        ).take(limit.coerceAtLeast(0))
                    }
                }
            val fakeSmtp =
                object : QqMailSmtpClient {
                    override suspend fun send(req: QqMailSendRequest): QqMailSendResult {
                        return QqMailSendResult(messageId = "<sent@test>")
                    }
                }
            QqMailCommandTestHooks.install(imap = fakeImap, smtp = fakeSmtp)
            val teardown = { QqMailCommandTestHooks.clear() }
            teardown
        },
    ) { tool ->
        val outRel = "artifacts/qqmail/test-fetch.json"
        val out1 = tool.exec("qqmail fetch --folder INBOX --limit 2 --out $outRel")
        assertEquals(0, out1.exitCode)
        assertTrue(out1.artifacts.contains(".agents/$outRel"))

        val outFile = File(tool.filesDir, ".agents/$outRel")
        assertTrue(outFile.exists())
        assertTrue(outFile.readText(Charsets.UTF_8).contains("\"count_total\""))

        val inboxDir = File(tool.filesDir, ".agents/workspace/qqmail/inbox")
        assertTrue(inboxDir.exists())
        val firstFiles = inboxDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty()
        assertEquals(2, firstFiles.size)
        assertTrue(firstFiles[0].readText(Charsets.UTF_8).contains("message_id:"))

        val out2 = tool.exec("qqmail fetch --folder INBOX --limit 2")
        assertEquals(0, out2.exitCode)
        val secondFiles = inboxDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty()
        assertEquals(2, secondFiles.size)

        val audit = File(tool.filesDir, ".agents/artifacts/terminal_exec/runs/${out2.runId}.json").readText(Charsets.UTF_8)
        assertTrue("audit must not contain email password", !audit.contains("SUPER_SECRET_AUTH_CODE"))
    }

    @Test
    fun qqmail_send_supportsBodyStdin_andOutArtifact_andNeverEchoesSecrets() = runTest(
        setup = { context ->
            val agentsRoot = File(context.filesDir, ".agents")
            val env = File(agentsRoot, "skills/qqmail-cli/secrets/.env")
            env.parentFile?.mkdirs()
            env.writeText(
                """
                EMAIL_ADDRESS=test@qq.com
                EMAIL_PASSWORD=SUPER_SECRET_AUTH_CODE
                SMTP_SERVER=smtp.qq.com
                SMTP_PORT=465
                IMAP_SERVER=imap.qq.com
                IMAP_PORT=993
                """.trimIndent() + "\n",
                Charsets.UTF_8,
            )

            val fakeImap =
                object : QqMailImapClient {
                    override suspend fun fetchLatest(folder: String, limit: Int): List<QqMailMessage> = emptyList()
                }
            val fakeSmtp =
                object : QqMailSmtpClient {
                    override suspend fun send(req: QqMailSendRequest): QqMailSendResult {
                        return QqMailSendResult(messageId = "<sent@test>")
                    }
                }
            QqMailCommandTestHooks.install(imap = fakeImap, smtp = fakeSmtp)
            val teardown = { QqMailCommandTestHooks.clear() }
            teardown
        },
    ) { tool ->
        val outRel = "artifacts/qqmail/test-send.json"
        val out = tool.exec(
            command = "qqmail send --to a@example.com --subject hi --body-stdin --confirm --out $outRel",
            stdin = "hello from stdin",
        )
        assertEquals(0, out.exitCode)
        assertTrue(out.artifacts.contains(".agents/$outRel"))

        val outFile = File(tool.filesDir, ".agents/$outRel")
        assertTrue(outFile.exists())
        assertTrue(outFile.readText(Charsets.UTF_8).contains("\"saved_path\""))

        val sentDir = File(tool.filesDir, ".agents/workspace/qqmail/sent")
        assertTrue(sentDir.exists())
        val md = sentDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty()
        assertTrue(md.isNotEmpty())

        val audit = File(tool.filesDir, ".agents/artifacts/terminal_exec/runs/${out.runId}.json").readText(Charsets.UTF_8)
        assertTrue("audit must not contain email password", !audit.contains("SUPER_SECRET_AUTH_CODE"))
        assertTrue("audit must not contain stdin body", !audit.contains("hello from stdin"))
    }

    @Test
    fun qqmail_rejectsSensitiveArgv() = runTest(
        setup = { context ->
            val agentsRoot = File(context.filesDir, ".agents")
            val env = File(agentsRoot, "skills/qqmail-cli/secrets/.env")
            env.parentFile?.mkdirs()
            env.writeText(
                """
                EMAIL_ADDRESS=test@qq.com
                EMAIL_PASSWORD=SUPER_SECRET_AUTH_CODE
                """.trimIndent() + "\n",
                Charsets.UTF_8,
            )
            QqMailCommandTestHooks.install(
                imap =
                    object : QqMailImapClient {
                        override suspend fun fetchLatest(folder: String, limit: Int): List<QqMailMessage> = emptyList()
                    },
                smtp =
                    object : QqMailSmtpClient {
                        override suspend fun send(req: QqMailSendRequest): QqMailSendResult {
                            return QqMailSendResult(messageId = "<sent@test>")
                        }
                    },
            )
            val teardown = { QqMailCommandTestHooks.clear() }
            teardown
        },
    ) { tool ->
        val out = tool.exec("qqmail fetch --password SUPER_SECRET_AUTH_CODE")
        assertTrue(out.exitCode != 0)
        assertEquals("SensitiveArgv", out.errorCode)
    }

    @Test
    fun tar_create_extract_roundtrip() = runTest { tool ->
        val id = UUID.randomUUID().toString().replace("-", "").take(8)
        val srcRel = "workspace/tar-roundtrip-src-$id"
        val outRel = "workspace/tar-roundtrip-$id.tar"
        val destRel = "workspace/tar-roundtrip-dest-$id"

        val srcDir = File(tool.filesDir, ".agents/$srcRel")
        srcDir.mkdirs()
        File(srcDir, "a.txt").writeText("hello-tar", Charsets.UTF_8)

        val create = tool.exec("tar create --src $srcRel --out $outRel --confirm")
        assertEquals(0, create.exitCode)
        assertTrue(File(tool.filesDir, ".agents/$outRel").exists())

        val extract = tool.exec("tar extract --in $outRel --dest $destRel --confirm")
        assertEquals(0, extract.exitCode)
        val extracted = File(tool.filesDir, ".agents/$destRel/a.txt")
        assertTrue(extracted.exists())
        assertEquals("hello-tar", extracted.readText(Charsets.UTF_8))
    }

    @Test
    fun ssh_exec_requires_stdin() = runTest { tool ->
        seedSshEnv(tool.filesDir, "SSH_PASSWORD=dummy")
        installFakeSshClientIfAvailable(stdout = "ok", stderr = "", remoteExitStatus = 0, hostKeyFingerprint = "fp-test")

        val out = tool.exec("ssh exec --host example.com --port 22 --user root")
        assertTrue(out.exitCode != 0)
        assertEquals("InvalidArgs", out.errorCode)
    }

    @Test
    fun ssh_exec_unknownHost_withoutTrust_isRejected() = runTest { tool ->
        seedSshEnv(tool.filesDir, "SSH_PASSWORD=dummy")
        installFakeSshClientIfAvailable(stdout = "ok", stderr = "", remoteExitStatus = 0, hostKeyFingerprint = "fp-test")

        val out = tool.exec("ssh exec --host example.com --port 22 --user root", stdin = "id")
        assertTrue(out.exitCode != 0)
        assertEquals("HostKeyUntrusted", out.errorCode)
    }

    @Test
    fun ssh_exec_rejectsSensitiveArgv() = runTest { tool ->
        seedSshEnv(tool.filesDir, "SSH_PASSWORD=dummy")
        installFakeSshClientIfAvailable(stdout = "ok", stderr = "", remoteExitStatus = 0, hostKeyFingerprint = "fp-test")

        val out =
            tool.exec(
                "ssh exec --host example.com --port 22 --user root --password secret",
                stdin = "id",
            )
        assertTrue(out.exitCode != 0)
        assertEquals("SensitiveArgv", out.errorCode)
    }

    @Test
    fun ssh_exec_rejectsOutPathTraversal() = runTest { tool ->
        seedSshEnv(tool.filesDir, "SSH_PASSWORD=dummy")
        installFakeSshClientIfAvailable(stdout = "ok", stderr = "", remoteExitStatus = 0, hostKeyFingerprint = "fp-test")

        val out =
            tool.exec(
                "ssh exec --host example.com --port 22 --user root --trust-host-key --out ../oops.json",
                stdin = "id",
            )
        assertTrue(out.exitCode != 0)
        assertEquals("PathEscapesAgentsRoot", out.errorCode)
    }

    @Test
    fun ssh_exec_trustHostKey_writesKnownHosts_andOutArtifact() = runTest { tool ->
        seedSshEnv(tool.filesDir, "SSH_PASSWORD=dummy")
        installFakeSshClientIfAvailable(stdout = "hello", stderr = "", remoteExitStatus = 0, hostKeyFingerprint = "fp-1")

        val outRel = "artifacts/ssh/exec/test.json"
        val out =
            tool.exec(
                "ssh exec --host example.com --port 22 --user root --trust-host-key --out $outRel",
                stdin = "echo hi ; whoami",
            )
        assertEquals(0, out.exitCode)
        assertEquals("ssh exec", (out.result?.get("command") as? JsonPrimitive)?.content)
        assertTrue(out.artifacts.contains(".agents/$outRel"))
        assertTrue(File(tool.filesDir, ".agents/$outRel").exists())
        assertTrue(File(tool.filesDir, ".agents/workspace/ssh/known_hosts").exists())
    }

    @Test
    fun ssh_exec_remoteNonZeroExit_isStableErrorCode() = runTest { tool ->
        seedSshEnv(tool.filesDir, "SSH_PASSWORD=dummy")
        installFakeSshClientIfAvailable(stdout = "", stderr = "boom", remoteExitStatus = 7, hostKeyFingerprint = "fp-1")

        val outRel = "artifacts/ssh/exec/nonzero.json"
        val out =
            tool.exec(
                "ssh exec --host example.com --port 22 --user root --trust-host-key --out $outRel",
                stdin = "false",
            )
        assertTrue(out.exitCode != 0)
        assertEquals("RemoteNonZeroExit", out.errorCode)
        assertEquals(7, (out.result?.get("remote_exit_status") as? JsonPrimitive)?.content?.toInt())
    }

    private fun seedSshEnv(
        filesDir: File,
        content: String,
    ) {
        val env = File(filesDir, ".agents/skills/ssh-cli/secrets/.env")
        env.parentFile?.mkdirs()
        env.writeText(content.trimEnd() + "\n", Charsets.UTF_8)
    }

    private fun installFakeSshClientIfAvailable(
        stdout: String,
        stderr: String,
        remoteExitStatus: Int,
        hostKeyFingerprint: String,
    ) {
        try {
            val hooksClass = Class.forName("com.lsl.kotlin_agent_app.agent.tools.ssh.SshClientTestHooks")
            val clientInterface = Class.forName("com.lsl.kotlin_agent_app.agent.tools.ssh.SshClient")
            val responseClass = Class.forName("com.lsl.kotlin_agent_app.agent.tools.ssh.SshExecResponse")

            val response =
                responseClass.declaredConstructors.first { it.parameterTypes.size == 5 }.newInstance(
                    stdout,
                    stderr,
                    remoteExitStatus,
                    hostKeyFingerprint,
                    1L,
                )

            val proxy =
                Proxy.newProxyInstance(
                    clientInterface.classLoader,
                    arrayOf(clientInterface),
                ) { _, method, _ ->
                    when (method.name) {
                        "exec" -> response
                        else -> null
                    }
                }

            val hooks = hooksClass.getField("INSTANCE").get(null)
            hooksClass.getMethod("install", clientInterface).invoke(hooks, proxy)
        } catch (_: ClassNotFoundException) {
            // ssh tool not implemented yet (red stage)
        }
    }

    private data class ExecOut(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val runId: String,
        val errorCode: String?,
        val result: JsonObject?,
        val artifacts: List<String>,
        val filesDir: File,
    )

    private fun runTest(
        setup: (android.content.Context) -> (() -> Unit) = { { } },
        block: suspend (TestHarness) -> Unit,
    ) {
        val context = RuntimeEnvironment.getApplication()
        val teardown = setup(context)
        AgentsWorkspace(context).ensureInitialized()

        try {
            val tool = TerminalExecTool(appContext = context)
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = File(context.filesDir, ".agents").absolutePath.replace('\\', '/').toPath())
            val harness = TestHarness(appContext = context, tool = tool, ctx = ctx, filesDir = context.filesDir)

            kotlinx.coroutines.runBlocking {
                block(harness)
            }
        } finally {
            teardown()
        }
    }

    private class TestHarness(
        val appContext: android.content.Context,
        private val tool: TerminalExecTool,
        private val ctx: ToolContext,
        val filesDir: File,
    ) {
        suspend fun exec(
            command: String,
            stdin: String? = null,
        ): ExecOut {
            return execWithTool(tool = tool, command = command, stdin = stdin)
        }

        suspend fun execWithFreshTool(
            command: String,
            stdin: String? = null,
        ): ExecOut {
            val fresh = TerminalExecTool(appContext = appContext)
            return execWithTool(tool = fresh, command = command, stdin = stdin)
        }

        private suspend fun execWithTool(
            tool: TerminalExecTool,
            command: String,
            stdin: String?,
        ): ExecOut {
            val input =
                buildJsonObject {
                    put("command", JsonPrimitive(command))
                    if (stdin != null) put("stdin", JsonPrimitive(stdin))
                }
            val out0 = tool.run(input, ctx)
            val json = (out0 as ToolOutput.Json).value
            assertNotNull(json)
            val obj = json!!.jsonObject
            val resultObj =
                when (val r = obj["result"]) {
                    null, is JsonNull -> null
                    else -> r.jsonObject
                }
            val artifacts =
                obj["artifacts"]?.jsonArray?.mapNotNull { el ->
                    (el as? JsonObject)?.get("path")?.let { p ->
                        (p as? JsonPrimitive)?.content
                    }
                }.orEmpty()
            return ExecOut(
                exitCode = (obj["exit_code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1,
                stdout = (obj["stdout"] as? JsonPrimitive)?.content ?: "",
                stderr = (obj["stderr"] as? JsonPrimitive)?.content ?: "",
                runId = (obj["run_id"] as? JsonPrimitive)?.content ?: "",
                errorCode = (obj["error_code"] as? JsonPrimitive)?.content,
                result = resultObj,
                artifacts = artifacts,
                filesDir = filesDir,
            )
        }
    }

    private class CapturingIrcClient : IrcClient {
        companion object {
            @Volatile var last: CapturingIrcClient? = null
            @Volatile var connectCalls: Int = 0
        }

        @Volatile var listener: IrcClientListener? = null
        @Volatile private var connected: Boolean = false

        init {
            last = this
        }

        override val isConnected: Boolean
            get() = connected

        override suspend fun connect() {
            connectCalls += 1
            connected = true
        }

        override suspend fun disconnect(message: String?) {
            connected = false
        }

        override suspend fun join(
            channel: String,
            key: String?,
        ) {
            // no-op
        }

        override suspend fun privmsg(
            target: String,
            text: String,
        ) {
            // no-op
        }

        fun emitPrivmsg(
            channel: String,
            nick: String,
            text: String,
        ) {
            listener?.onPrivmsg(channel = channel, nick = nick, text = text, tsMs = System.currentTimeMillis())
        }
    }

    private class CapturingFinnhubTransport(
        private val statusCode: Int,
        private val bodyText: String,
        private val headers: Map<String, String>,
    ) : FinnhubTransport {
        @Volatile var lastUrl: HttpUrl? = null
        @Volatile var lastHeaders: Map<String, String>? = null

        override suspend fun get(
            url: HttpUrl,
            headers: Map<String, String>,
        ): FinnhubHttpResponse {
            lastUrl = url
            lastHeaders = headers.toMap()
            return FinnhubHttpResponse(statusCode = statusCode, bodyText = bodyText, headers = this.headers)
        }
    }

    private class CapturingRssTransport(
        private val statusCode: Int,
        private val bodyText: String,
        private val headers: Map<String, String>,
    ) : RssTransport {
        @Volatile var lastUrl: HttpUrl? = null
        @Volatile var lastHeaders: Map<String, String>? = null

        override suspend fun get(
            url: HttpUrl,
            headers: Map<String, String>,
        ): RssHttpResponse {
            lastUrl = url
            lastHeaders = headers.toMap()
            return RssHttpResponse(statusCode = statusCode, bodyText = bodyText, headers = this.headers)
        }
    }

    private class FakeMusicTransport : MusicTransport {
        @Volatile private var playing: Boolean = false
        @Volatile private var posMs: Long = 0L
        @Volatile private var durMs: Long? = 60_000L
        @Volatile private var vol: Float = 1.0f
        @Volatile private var transportListener: com.lsl.kotlin_agent_app.media.MusicTransportListener? = null

        @Volatile var lastPlayedAgentsPath: String? = null
        @Volatile var playCalls: Int = 0

        override suspend fun connect() {
            // no-op
        }

        override suspend fun play(request: MusicPlaybackRequest) {
            playCalls += 1
            lastPlayedAgentsPath = request.agentsPath
            playing = true
            posMs = 0L
            durMs = request.metadata.durationMs ?: durMs
        }

        override suspend fun pause() {
            playing = false
        }

        override suspend fun resume() {
            playing = true
        }

        override suspend fun stop() {
            playing = false
            posMs = 0L
        }

        override suspend fun seekTo(positionMs: Long) {
            posMs = positionMs.coerceAtLeast(0L)
        }

        override fun currentPositionMs(): Long = posMs

        override fun durationMs(): Long? = durMs

        override fun isPlaying(): Boolean = playing

        override suspend fun setVolume(volume: Float) {
            vol = volume
        }

        override fun volume(): Float? = vol

        override fun setListener(listener: com.lsl.kotlin_agent_app.media.MusicTransportListener?) {
            this.transportListener = listener
        }
    }

    private fun buildFakeMp3Bytes(): ByteArray {
        // Minimal "mp3-looking" bytes: frame sync + padding.
        // It's not meant to be playable; only to satisfy lightweight validation for unit tests.
        val header = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte())
        val body = ByteArray(4096) { 0 }
        return header + body
    }

    private class FakeTtsRuntime(
        private val voices: List<TtsVoiceSummary> =
            listOf(
                TtsVoiceSummary(name = "fake-zh", localeTag = "zh-CN"),
            ),
    ) : TtsRuntime {
        @Volatile var speakCalls: Int = 0
        @Volatile var lastSpeak: TtsSpeakRequest? = null
        @Volatile var stopCalls: Int = 0

        override suspend fun listVoices(): List<TtsVoiceSummary> = voices

        override suspend fun speak(
            req: TtsSpeakRequest,
            await: Boolean,
            timeoutMs: Long?,
        ): TtsSpeakResponse {
            speakCalls += 1
            lastSpeak = req
            if (await && (timeoutMs ?: 0L) <= 1L) {
                throw TtsTimeout("fake timeout")
            }
            val utteranceId = "fake_" + UUID.randomUUID().toString().replace("-", "").take(10)
            val completion = if (await) TtsSpeakCompletion.Done else TtsSpeakCompletion.Started
            return TtsSpeakResponse(utteranceId = utteranceId, completion = completion)
        }

        override suspend fun stop(): TtsStopResponse {
            stopCalls += 1
            return TtsStopResponse(stopped = true)
        }
    }

    private class CapturingExchangeRateTransport(
        private val statusCode: Int,
        private val bodyText: String,
        private val headers: Map<String, String>,
    ) : ExchangeRateTransport {
        @Volatile var lastUrl: HttpUrl? = null
        @Volatile var callCount: Int = 0

        override suspend fun get(url: HttpUrl): com.lsl.kotlin_agent_app.agent.tools.exchange_rate.ExchangeRateHttpResponse {
            lastUrl = url
            callCount += 1
            return com.lsl.kotlin_agent_app.agent.tools.exchange_rate.ExchangeRateHttpResponse(
                statusCode = statusCode,
                bodyText = bodyText,
                headers = headers,
            )
        }
    }

    private fun fakeExchangeRateLatestCnyJson(nextUpdateUtc: String): String {
        return """
            {
              "result": "success",
              "base_code": "CNY",
              "time_last_update_utc": "Mon, 17 Feb 2025 00:00:01 +0000",
              "time_next_update_utc": "$nextUpdateUtc",
              "rates": {
                "CNY": 1,
                "USD": 0.1370,
                "EUR": 0.1318,
                "JPY": 20.89,
                "GBP": 0.1095,
                "HKD": 1.0667
              }
            }
        """.trimIndent()
    }
}
