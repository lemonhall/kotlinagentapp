package com.lsl.kotlin_agent_app.agent.tools.terminal

import com.lsl.kotlin_agent_app.agent.tools.mail.QqMailImapClient
import com.lsl.kotlin_agent_app.agent.tools.mail.QqMailMessage
import com.lsl.kotlin_agent_app.agent.tools.mail.QqMailSendRequest
import com.lsl.kotlin_agent_app.agent.tools.mail.QqMailSendResult
import com.lsl.kotlin_agent_app.agent.tools.mail.QqMailSmtpClient
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.qqmail.QqMailCommandTestHooks
import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalExecToolQqmailTest {

    @Test
    fun qqmail_send_withoutConfirm_isRejected() =
        runTerminalExecToolTest { tool ->
            val out = tool.exec("qqmail send --to a@example.com --subject hi --body-stdin")
            assertTrue(out.exitCode != 0)
            assertEquals("ConfirmRequired", out.errorCode)
        }

    @Test
    fun qqmail_fetch_missingCredentials_isRejected() =
        runTerminalExecToolTest(
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
                { }
            },
        ) { tool ->
            val out = tool.exec("qqmail fetch")
            assertTrue(out.exitCode != 0)
            assertEquals("MissingCredentials", out.errorCode)
        }

    @Test
    fun qqmail_fetch_writesMarkdown_andSupportsOutArtifact_andDedupesByMessageId() =
        runTerminalExecToolTest(
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
                { QqMailCommandTestHooks.clear() }
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
    fun qqmail_send_supportsBodyStdin_andOutArtifact_andNeverEchoesSecrets() =
        runTerminalExecToolTest(
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
                { QqMailCommandTestHooks.clear() }
            },
        ) { tool ->
            val outRel = "artifacts/qqmail/test-send.json"
            val out =
                tool.exec(
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
    fun qqmail_rejectsSensitiveArgv() =
        runTerminalExecToolTest(
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
                { QqMailCommandTestHooks.clear() }
            },
        ) { tool ->
            val out = tool.exec("qqmail fetch --password SUPER_SECRET_AUTH_CODE")
            assertTrue(out.exitCode != 0)
            assertEquals("SensitiveArgv", out.errorCode)
        }
}

