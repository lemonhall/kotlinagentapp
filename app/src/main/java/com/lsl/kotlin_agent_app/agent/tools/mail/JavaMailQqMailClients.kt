package com.lsl.kotlin_agent_app.agent.tools.mail

import java.util.Properties
import javax.mail.Authenticator
import javax.mail.BodyPart
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

internal class JavaMailQqMailImapClient(
    private val secrets: QqMailSecrets,
) : QqMailImapClient {
    override suspend fun fetchLatest(
        folder: String,
        limit: Int,
    ): List<QqMailMessage> {
        val props =
            Properties().apply {
                put("mail.store.protocol", "imaps")
                put("mail.imaps.host", secrets.imapServer)
                put("mail.imaps.port", secrets.imapPort.toString())
                put("mail.imaps.ssl.enable", "true")
                put("mail.imaps.connectiontimeout", "15000")
                put("mail.imaps.timeout", "15000")
            }

        val session =
            Session.getInstance(
                props,
                object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication {
                        return PasswordAuthentication(secrets.emailAddress, secrets.emailPassword)
                    }
                },
            )

        val store = session.getStore("imaps")
        try {
            store.connect(secrets.imapServer, secrets.imapPort, secrets.emailAddress, secrets.emailPassword)
            val f = store.getFolder(folder)
            try {
                f.open(javax.mail.Folder.READ_ONLY)
                val total = f.messageCount.coerceAtLeast(0)
                if (total == 0) return emptyList()
                val n = limit.coerceAtLeast(0).coerceAtMost(200)
                if (n == 0) return emptyList()
                val start = (total - n + 1).coerceAtLeast(1)
                val messages = f.getMessages(start, total)
                return messages
                    .asSequence()
                    .map { msg ->
                        val messageId = msg.getHeader("Message-ID")?.firstOrNull()
                        val subject = msg.subject.orEmpty()
                        val from =
                            msg.from
                                ?.mapNotNull { it as? InternetAddress }
                                ?.joinToString(",") { it.address.orEmpty() }
                                ?: msg.from?.joinToString(",") { it.toString() }.orEmpty()
                        val to =
                            msg.getRecipients(Message.RecipientType.TO)
                                ?.mapNotNull { it as? InternetAddress }
                                ?.joinToString(",") { it.address.orEmpty() }
                                ?: msg.getRecipients(Message.RecipientType.TO)?.joinToString(",") { it.toString() }.orEmpty()
                        val dateMs = (msg.sentDate ?: msg.receivedDate)?.time ?: System.currentTimeMillis()
                        val body = extractText(msg).orEmpty()
                        QqMailMessage(
                            folder = folder,
                            messageId = messageId,
                            subject = subject,
                            from = from,
                            to = to,
                            dateMs = dateMs,
                            bodyText = body,
                        )
                    }.toList()
                    .reversed() // newest-first -> stable oldest-first output
            } finally {
                try {
                    f.close(false)
                } catch (_: Throwable) {
                }
            }
        } finally {
            try {
                store.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun extractText(part: Part): String? {
        return try {
            when {
                part.isMimeType("text/plain") -> part.content as? String
                part.isMimeType("text/*") -> part.content as? String
                part.isMimeType("multipart/alternative") -> {
                    val mp = part.content as? Multipart ?: return null
                    // Prefer text/plain over other representations.
                    (0 until mp.count)
                        .asSequence()
                        .mapNotNull { idx -> mp.getBodyPart(idx) }
                        .mapNotNull { bp ->
                            if (bp.isMimeType("text/plain")) (bp.content as? String) else null
                        }.firstOrNull()
                        ?: extractTextFromMultipart(mp)
                }
                part.isMimeType("multipart/*") -> {
                    val mp = part.content as? Multipart ?: return null
                    extractTextFromMultipart(mp)
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractTextFromMultipart(mp: Multipart): String? {
        for (i in 0 until mp.count) {
            val bp: BodyPart = mp.getBodyPart(i)
            val txt = extractText(bp)
            if (!txt.isNullOrBlank()) return txt
        }
        return null
    }
}

internal class JavaMailQqMailSmtpClient(
    private val secrets: QqMailSecrets,
) : QqMailSmtpClient {
    override suspend fun send(req: QqMailSendRequest): QqMailSendResult {
        val props =
            Properties().apply {
                put("mail.transport.protocol", "smtps")
                put("mail.smtps.host", secrets.smtpServer)
                put("mail.smtps.port", secrets.smtpPort.toString())
                put("mail.smtps.auth", "true")
                put("mail.smtps.ssl.enable", "true")
                put("mail.smtps.connectiontimeout", "15000")
                put("mail.smtps.timeout", "15000")
            }

        val session =
            Session.getInstance(
                props,
                object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication {
                        return PasswordAuthentication(secrets.emailAddress, secrets.emailPassword)
                    }
                },
            )

        val msg =
            MimeMessage(session).apply {
                setFrom(InternetAddress(secrets.emailAddress))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(req.to, false))
                setSubject(req.subject, "UTF-8")
                setText(req.bodyText, "UTF-8")
            }

        Transport.send(msg)
        return QqMailSendResult(messageId = msg.messageID)
    }
}
