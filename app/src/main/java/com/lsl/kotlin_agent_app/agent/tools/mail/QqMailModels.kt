package com.lsl.kotlin_agent_app.agent.tools.mail

internal data class QqMailSecrets(
    val emailAddress: String,
    val emailPassword: String,
    val smtpServer: String,
    val smtpPort: Int,
    val imapServer: String,
    val imapPort: Int,
)

internal data class QqMailMessage(
    val folder: String,
    val messageId: String?,
    val subject: String,
    val from: String,
    val to: String,
    val dateMs: Long,
    val bodyText: String,
)

internal data class QqMailSendRequest(
    val to: String,
    val subject: String,
    val bodyText: String,
)

internal data class QqMailSendResult(
    val messageId: String?,
)

internal interface QqMailImapClient {
    suspend fun fetchLatest(
        folder: String,
        limit: Int,
    ): List<QqMailMessage>
}

internal interface QqMailSmtpClient {
    suspend fun send(req: QqMailSendRequest): QqMailSendResult
}

