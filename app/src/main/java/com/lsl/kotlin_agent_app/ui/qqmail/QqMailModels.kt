package com.lsl.kotlin_agent_app.ui.qqmail

internal enum class QqMailMailbox {
    Inbox,
    Sent,
}

internal data class QqMailLocalItem(
    val agentsPath: String,
    val mailbox: QqMailMailbox,
    val subject: String,
    val peer: String,
    val dateText: String,
    val preview: String,
    val sortKey: Long,
)

internal data class QqMailLocalMessage(
    val item: QqMailLocalItem,
    val frontMatter: Map<String, String>,
    val bodyMarkdown: String,
)

