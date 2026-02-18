package com.lsl.kotlin_agent_app.agent.tools.irc

internal data class IrcHumanQuestion(
    val inboundId: String,
    val tsMs: Long,
    val channel: String,
    val nick: String,
    val inboundText: String,
    val question: String,
)
