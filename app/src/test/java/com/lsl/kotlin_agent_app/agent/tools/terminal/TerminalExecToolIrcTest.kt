package com.lsl.kotlin_agent_app.agent.tools.terminal

import com.lsl.kotlin_agent_app.agent.tools.irc.IrcClientListener
import com.lsl.kotlin_agent_app.agent.tools.irc.IrcClientTestHooks
import com.lsl.kotlin_agent_app.agent.tools.irc.IrcConfig
import com.lsl.kotlin_agent_app.agent.tools.irc.IrcSessionRuntimeStore
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalExecToolIrcTest {

    @Test
    fun irc_status_missingCredentials_whenEnvIncomplete() =
        runTerminalExecToolTest(
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
    fun irc_disconnect_ok_evenWithoutCredentials() =
        runTerminalExecToolTest(
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
            val out = tool.exec("irc disconnect")
            assertEquals(0, out.exitCode)
        }

    @Test
    fun irc_rejects_nickTooLong() =
        runTerminalExecToolTest(
            setup = { ctx ->
                val prefs = ctx.getSharedPreferences("kotlin-agent-app", android.content.Context.MODE_PRIVATE)
                val sid = "sess_irc_" + UUID.randomUUID().toString().replace("-", "")
                prefs.edit().putString(com.lsl.kotlin_agent_app.config.AppPrefsKeys.CHAT_SESSION_ID, sid).apply()
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
                )
                ;
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
    fun irc_connect_requestsConnect_and_returnsOk() =
        runTerminalExecToolTest(
            setup = { ctx ->
                val prefs = ctx.getSharedPreferences("kotlin-agent-app", android.content.Context.MODE_PRIVATE)
                val sid = "sess_irc_" + UUID.randomUUID().toString().replace("-", "")
                prefs.edit().putString(com.lsl.kotlin_agent_app.config.AppPrefsKeys.CHAT_SESSION_ID, sid).apply()
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
                }
                ;

                {
                    prefs.edit().remove(com.lsl.kotlin_agent_app.config.AppPrefsKeys.CHAT_SESSION_ID).apply()
                    IrcClientTestHooks.clear()
                    IrcSessionRuntimeStore.clearForTest()
                }
            },
        ) { tool ->
            val out = tool.exec("irc connect --force")
            assertEquals(0, out.exitCode)

            val deadlineMs = System.currentTimeMillis() + 2_000
            while (CapturingIrcClient.connectCalls <= 0 && System.currentTimeMillis() < deadlineMs) {
                Thread.sleep(10)
            }
            assertTrue("connect should be requested", CapturingIrcClient.connectCalls >= 1)
        }

    @Test
    fun irc_send_requiresConfirm_forNonDefaultTarget() =
        runTerminalExecToolTest(
            setup = { ctx ->
                val prefs = ctx.getSharedPreferences("kotlin-agent-app", android.content.Context.MODE_PRIVATE)
                val sid = "sess_irc_" + UUID.randomUUID().toString().replace("-", "")
                prefs.edit().putString(com.lsl.kotlin_agent_app.config.AppPrefsKeys.CHAT_SESSION_ID, sid).apply()
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
                }
                ;

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
    fun irc_send_reusesConnection_acrossToolInstances_inSameSession() =
        runTerminalExecToolTest(
            setup = { ctx ->
                val prefs = ctx.getSharedPreferences("kotlin-agent-app", android.content.Context.MODE_PRIVATE)
                val sid = "sess_irc_" + UUID.randomUUID().toString().replace("-", "")
                prefs.edit().putString(com.lsl.kotlin_agent_app.config.AppPrefsKeys.CHAT_SESSION_ID, sid).apply()
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
                }
                ;

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
    fun irc_pull_cursor_dedup_perChannel_and_peek() =
        runTerminalExecToolTest(
            setup = { ctx ->
                val prefs = ctx.getSharedPreferences("kotlin-agent-app", android.content.Context.MODE_PRIVATE)
                val sid = "sess_irc_" + UUID.randomUUID().toString().replace("-", "")
                prefs.edit().putString(com.lsl.kotlin_agent_app.config.AppPrefsKeys.CHAT_SESSION_ID, sid).apply()
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
                }
                ;

                {
                    prefs.edit().remove(com.lsl.kotlin_agent_app.config.AppPrefsKeys.CHAT_SESSION_ID).apply()
                    IrcClientTestHooks.clear()
                    IrcSessionRuntimeStore.clearForTest()
                }
            },
        ) { tool ->
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
    fun irc_history_readsInboundAndOutboundJsonl() =
        runTerminalExecToolTest(
            setup = { ctx ->
                val prefs = ctx.getSharedPreferences("kotlin-agent-app", android.content.Context.MODE_PRIVATE)
                val sid = "sess_irc_" + UUID.randomUUID().toString().replace("-", "")
                prefs.edit().putString(com.lsl.kotlin_agent_app.config.AppPrefsKeys.CHAT_SESSION_ID, sid).apply()
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
                }
                ;

                {
                    prefs.edit().remove(com.lsl.kotlin_agent_app.config.AppPrefsKeys.CHAT_SESSION_ID).apply()
                    IrcClientTestHooks.clear()
                    IrcSessionRuntimeStore.clearForTest()
                }
            },
        ) { tool ->
            assertEquals(0, tool.exec("irc status").exitCode)
            CapturingIrcClient.last!!.emitPrivmsg("#default", "a", "hello")
            assertEquals(0, tool.exec("irc send --text-stdin", stdin = "out").exitCode)

            val out = tool.exec("irc history --from #default --limit 50")
            assertEquals(0, out.exitCode)
            val arr = out.result!!.jsonObject["messages"]!!.jsonArray
            assertTrue(arr.size >= 2)
            val dirs = arr.map { it.jsonObject["direction"]!!.jsonPrimitive.content }.toSet()
            assertTrue(dirs.contains("in"))
            assertTrue(dirs.contains("out"))
        }

    @Test
    fun irc_pull_truncates_and_audit_doesNotContainSecrets() =
        runTerminalExecToolTest(
            setup = { ctx ->
                val prefs = ctx.getSharedPreferences("kotlin-agent-app", android.content.Context.MODE_PRIVATE)
                val sid = "sess_irc_" + UUID.randomUUID().toString().replace("-", "")
                prefs.edit().putString(com.lsl.kotlin_agent_app.config.AppPrefsKeys.CHAT_SESSION_ID, sid).apply()
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
                }
                ;

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
}
