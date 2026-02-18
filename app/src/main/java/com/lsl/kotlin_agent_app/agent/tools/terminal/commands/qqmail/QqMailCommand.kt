package com.lsl.kotlin_agent_app.agent.tools.terminal.commands.qqmail

import android.content.Context
import com.lsl.kotlin_agent_app.agent.tools.mail.DotEnv
import com.lsl.kotlin_agent_app.agent.tools.mail.JavaMailQqMailImapClient
import com.lsl.kotlin_agent_app.agent.tools.mail.JavaMailQqMailSmtpClient
import com.lsl.kotlin_agent_app.agent.tools.mail.QqMailImapClient
import com.lsl.kotlin_agent_app.agent.tools.mail.QqMailMessage
import com.lsl.kotlin_agent_app.agent.tools.mail.QqMailSecrets
import com.lsl.kotlin_agent_app.agent.tools.mail.QqMailSendRequest
import com.lsl.kotlin_agent_app.agent.tools.mail.QqMailSmtpClient
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalArtifact
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommandOutput
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.ConfirmRequired
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.optionalFlagValue
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.parseIntFlag
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.relPath
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.requireConfirm
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.requireFlagValue
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.resolveWithinAgents
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.mail.AuthenticationFailedException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal object QqMailCommandTestHooks {
    @Volatile private var imapOverride: QqMailImapClient? = null
    @Volatile private var smtpOverride: QqMailSmtpClient? = null

    fun install(
        imap: QqMailImapClient,
        smtp: QqMailSmtpClient,
    ) {
        imapOverride = imap
        smtpOverride = smtp
    }

    fun clear() {
        imapOverride = null
        smtpOverride = null
    }

    internal fun getImapOrNull(): QqMailImapClient? = imapOverride

    internal fun getSmtpOrNull(): QqMailSmtpClient? = smtpOverride
}

internal class MissingCredentials(
    message: String,
) : IllegalArgumentException(message)

internal class SensitiveArgv(
    message: String,
) : IllegalArgumentException(message)

internal class QqMailCommand(
    appContext: Context,
) : TerminalCommand {
    private val ctx = appContext.applicationContext
    private val agentsRoot = File(ctx.filesDir, ".agents").canonicalFile

    override val name: String = "qqmail"
    override val description: String = "QQMail IMAP/SMTP CLI (fetch/send) with dotenv secrets, confirm guardrails, and artifact-based large outputs."

    override suspend fun run(
        argv: List<String>,
        stdin: String?,
    ): TerminalCommandOutput {
        if (argv.size < 2) return invalidArgs("missing subcommand")

        return try {
            rejectSensitiveArgv(argv)
            when (argv[1].lowercase()) {
                "fetch" -> handleFetch(argv)
                "send" -> handleSend(argv, stdin)
                else -> invalidArgs("unknown subcommand: ${argv[1]}")
            }
        } catch (t: SensitiveArgv) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "sensitive argv is not allowed"),
                errorCode = "SensitiveArgv",
                errorMessage = t.message,
            )
        } catch (t: MissingCredentials) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "missing credentials"),
                errorCode = "MissingCredentials",
                errorMessage = t.message,
            )
        } catch (t: ConfirmRequired) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "missing --confirm"),
                errorCode = "ConfirmRequired",
                errorMessage = t.message,
            )
        } catch (t: AuthenticationFailedException) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "authentication failed"),
                errorCode = "AuthFailed",
                errorMessage = t.message,
            )
        } catch (t: IllegalArgumentException) {
            invalidArgs(t.message ?: "invalid args")
        } catch (t: Throwable) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "qqmail error"),
                errorCode = "QqMailError",
                errorMessage = t.message,
            )
        }
    }

    private suspend fun handleFetch(argv: List<String>): TerminalCommandOutput {
        val folder = optionalFlagValue(argv, "--folder")?.takeIf { it.isNotBlank() } ?: "INBOX"
        val limit = parseIntFlag(argv, "--limit", defaultValue = 20).coerceIn(0, 200)
        val outRel = optionalFlagValue(argv, "--out")?.trim()?.takeIf { it.isNotBlank() }

        val secrets = loadSecrets()
        val imap = QqMailCommandTestHooks.getImapOrNull() ?: JavaMailQqMailImapClient(secrets)
        val inboxDir = resolveWithinAgents(agentsRoot, "workspace/qqmail/inbox")
        if (!inboxDir.exists()) inboxDir.mkdirs()

        val messages = imap.fetchLatest(folder = folder, limit = limit)
        val written = mutableListOf<QqMailWritten>()
        var skipped = 0
        for (m in messages) {
            val outFile = fileForMessage(dir = inboxDir, m = m)
            if (outFile.exists()) {
                skipped += 1
                continue
            }
            val text = renderMessageMarkdown(m)
            outFile.writeText(text, Charsets.UTF_8)
            written.add(
                QqMailWritten(
                    path = ".agents/" + relPath(agentsRoot, outFile),
                    messageId = m.messageId,
                    subject = m.subject,
                    from = m.from,
                    dateMs = m.dateMs,
                ),
            )
        }

        val truncated = written.size > 20
        val writtenForResult = written.take(20)

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("qqmail fetch"))
                put("folder", JsonPrimitive(folder))
                put("limit", JsonPrimitive(limit))
                put("count_total", JsonPrimitive(messages.size))
                put("fetched", JsonPrimitive(written.size))
                put("skipped", JsonPrimitive(skipped))
                put("written_files", buildJsonArray { writtenForResult.forEach { add(it.toJson()) } })
                if (truncated) put("written_files_truncated", JsonPrimitive(true))
                if (outRel != null) put("out", JsonPrimitive(outRel))
            }

        val artifacts =
            if (outRel != null) {
                val artifact = writeOutJson(outRel = outRel, json = fullFetchOutJson(folder, limit, messages, written, skipped, outRel))
                listOf(artifact)
            } else {
                emptyList()
            }

        val stdout = "qqmail fetch: folder=$folder fetched=${written.size} skipped=$skipped"
        return TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result, artifacts = artifacts)
    }

    private suspend fun handleSend(
        argv: List<String>,
        stdin: String?,
    ): TerminalCommandOutput {
        requireConfirm(argv, extraMessage = "qqmail send requires --confirm")

        val to = requireFlagValue(argv, "--to").trim()
        val subject = requireFlagValue(argv, "--subject")
        val bodyArg = optionalFlagValue(argv, "--body")
        val bodyFromStdin = argv.any { it == "--body-stdin" }
        if (bodyFromStdin && bodyArg != null) throw IllegalArgumentException("use either --body or --body-stdin, not both")
        val body =
            when {
                bodyFromStdin -> stdin ?: throw IllegalArgumentException("--body-stdin requires stdin")
                bodyArg != null -> bodyArg
                else -> throw IllegalArgumentException("missing --body or --body-stdin")
            }

        val outRel = optionalFlagValue(argv, "--out")?.trim()?.takeIf { it.isNotBlank() }

        val secrets = loadSecrets()
        val smtp = QqMailCommandTestHooks.getSmtpOrNull() ?: JavaMailQqMailSmtpClient(secrets)
        val sendResult = smtp.send(QqMailSendRequest(to = to, subject = subject, bodyText = body))

        val sentDir = resolveWithinAgents(agentsRoot, "workspace/qqmail/sent")
        if (!sentDir.exists()) sentDir.mkdirs()
        val savedFile = fileForSent(sentDir, to = to, subject = subject, messageId = sendResult.messageId)
        savedFile.writeText(
            renderSentMarkdown(to = to, subject = subject, messageId = sendResult.messageId, bodyText = body),
            Charsets.UTF_8,
        )
        val savedPath = ".agents/" + relPath(agentsRoot, savedFile)

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("qqmail send"))
                put("to", JsonPrimitive(to))
                put("subject", JsonPrimitive(subject))
                put("saved_path", JsonPrimitive(savedPath))
                if (sendResult.messageId != null) put("message_id", JsonPrimitive(sendResult.messageId))
                if (outRel != null) put("out", JsonPrimitive(outRel))
            }

        val artifacts =
            if (outRel != null) {
                val json =
                    buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("command", JsonPrimitive("qqmail send"))
                        put("to", JsonPrimitive(to))
                        put("subject", JsonPrimitive(subject))
                        put("saved_path", JsonPrimitive(savedPath))
                        if (sendResult.messageId != null) put("message_id", JsonPrimitive(sendResult.messageId))
                        put("out", JsonPrimitive(outRel))
                    }
                listOf(writeOutJson(outRel = outRel, json = json))
            } else {
                emptyList()
            }

        val stdout = "qqmail send: to=$to subject=${subject.take(80)}"
        return TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result, artifacts = artifacts)
    }

    private fun loadSecrets(): QqMailSecrets {
        val envRel = "skills/qqmail-cli/secrets/.env"
        val envFile = resolveWithinAgents(agentsRoot, envRel)
        val map = DotEnv.load(envFile)
        val email = map["EMAIL_ADDRESS"].orEmpty().trim()
        val pass = map["EMAIL_PASSWORD"].orEmpty().trim()
        if (email.isBlank() || pass.isBlank()) {
            throw MissingCredentials("Missing EMAIL_ADDRESS/EMAIL_PASSWORD in .agents/$envRel")
        }
        val smtpServer = map["SMTP_SERVER"]?.trim().takeIf { !it.isNullOrBlank() } ?: "smtp.qq.com"
        val smtpPort = map["SMTP_PORT"]?.trim()?.toIntOrNull()?.coerceIn(1, 65535) ?: 465
        val imapServer = map["IMAP_SERVER"]?.trim().takeIf { !it.isNullOrBlank() } ?: "imap.qq.com"
        val imapPort = map["IMAP_PORT"]?.trim()?.toIntOrNull()?.coerceIn(1, 65535) ?: 993
        return QqMailSecrets(
            emailAddress = email,
            emailPassword = pass,
            smtpServer = smtpServer,
            smtpPort = smtpPort,
            imapServer = imapServer,
            imapPort = imapPort,
        )
    }

    private fun rejectSensitiveArgv(argv: List<String>) {
        val sensitiveFlagNames =
            listOf(
                "password",
                "passwd",
                "pass",
                "auth",
                "token",
                "secret",
                "apikey",
                "api-key",
                "api_key",
                "email_password",
                "email-password",
            )

        for (a in argv) {
            val t = a.trim()
            if (t.isEmpty()) continue

            if (t.contains("EMAIL_PASSWORD=", ignoreCase = true)) {
                throw SensitiveArgv("Do not pass EMAIL_PASSWORD via argv; use .env only.")
            }

            if (!t.startsWith("--")) continue

            val flagName =
                t
                    .removePrefix("--")
                    .substringBefore('=')
                    .lowercase()
            if (sensitiveFlagNames.any { flagName.contains(it) }) {
                throw SensitiveArgv("Sensitive flag is not allowed in argv: --$flagName")
            }
        }
    }

    private data class QqMailWritten(
        val path: String,
        val messageId: String?,
        val subject: String,
        val from: String,
        val dateMs: Long,
    ) {
        fun toJson(): JsonElement {
            return buildJsonObject {
                put("path", JsonPrimitive(path))
                if (messageId != null) put("message_id", JsonPrimitive(messageId))
                put("subject", JsonPrimitive(subject))
                put("from", JsonPrimitive(from))
                put("date_ms", JsonPrimitive(dateMs))
            }
        }
    }

    private fun fullFetchOutJson(
        folder: String,
        limit: Int,
        messages: List<QqMailMessage>,
        written: List<QqMailWritten>,
        skipped: Int,
        outRel: String,
    ): JsonElement {
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("command", JsonPrimitive("qqmail fetch"))
            put("folder", JsonPrimitive(folder))
            put("limit", JsonPrimitive(limit))
            put("out", JsonPrimitive(outRel))
            put("count_total", JsonPrimitive(messages.size))
            put("fetched", JsonPrimitive(written.size))
            put("skipped", JsonPrimitive(skipped))
            put("written_files", buildJsonArray { written.forEach { add(it.toJson()) } })
        }
    }

    private fun writeOutJson(
        outRel: String,
        json: JsonElement,
    ): TerminalArtifact {
        val outFile = resolveWithinAgents(agentsRoot, outRel)
        val parent = outFile.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        outFile.writeText(json.toString() + "\n", Charsets.UTF_8)
        return TerminalArtifact(
            path = ".agents/" + relPath(agentsRoot, outFile),
            mime = "application/json",
            description = "qqmail full output (may be large).",
        )
    }

    private fun fileForMessage(
        dir: File,
        m: QqMailMessage,
    ): File {
        val ts = formatTs(m.dateMs)
        val subj = safeName(m.subject, maxLen = 60)
        val idHash = sha256Hex(m.messageId ?: "${m.from}|${m.subject}|${m.dateMs}").take(12)
        val base = listOf(ts, subj, idHash).filter { it.isNotBlank() }.joinToString("_").ifBlank { "msg_$idHash" }
        return File(dir, "$base.md")
    }

    private fun fileForSent(
        dir: File,
        to: String,
        subject: String,
        messageId: String?,
    ): File {
        val ts = formatTs(System.currentTimeMillis())
        val subj = safeName(subject, maxLen = 60)
        val idHash = sha256Hex(messageId ?: "$to|$subject|$ts").take(12)
        val base = listOf("sent", ts, subj, idHash).filter { it.isNotBlank() }.joinToString("_")
        return File(dir, "$base.md")
    }

    private fun formatTs(ms: Long): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return fmt.format(Date(ms))
    }

    private fun safeName(
        raw: String,
        maxLen: Int,
    ): String {
        val cleaned =
            raw
                .trim()
                .replace('\u0000', ' ')
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .replace(Regex("\\s+"), " ")
                .trim()
        val short = if (cleaned.length > maxLen) cleaned.take(maxLen).trim() else cleaned
        return short.replace(' ', '_')
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { b -> ((b.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
    }

    private fun renderMessageMarkdown(m: QqMailMessage): String {
        val dateIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date(m.dateMs))
        fun q(v: String): String = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        val yaml =
            buildString {
                appendLine("---")
                appendLine("folder: ${q(m.folder)}")
                if (!m.messageId.isNullOrBlank()) appendLine("message_id: ${q(m.messageId)}")
                appendLine("subject: ${q(m.subject)}")
                appendLine("from: ${q(m.from)}")
                appendLine("to: ${q(m.to)}")
                appendLine("date: ${q(dateIso)}")
                appendLine("---")
            }
        return yaml + "\n" + m.bodyText.trimEnd() + "\n"
    }

    private fun renderSentMarkdown(
        to: String,
        subject: String,
        messageId: String?,
        bodyText: String,
    ): String {
        val dateIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
        fun q(v: String): String = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        val yaml =
            buildString {
                appendLine("---")
                appendLine("to: ${q(to)}")
                appendLine("subject: ${q(subject)}")
                appendLine("date: ${q(dateIso)}")
                if (!messageId.isNullOrBlank()) appendLine("message_id: ${q(messageId)}")
                appendLine("---")
            }
        return yaml + "\n" + bodyText.trimEnd() + "\n"
    }

    private fun invalidArgs(message: String): TerminalCommandOutput {
        return TerminalCommandOutput(
            exitCode = 2,
            stdout = "",
            stderr = message,
            errorCode = "InvalidArgs",
            errorMessage = message,
        )
    }
}
