package com.lsl.kotlin_agent_app.agent.tools.irc

import com.lsl.kotlin_agent_app.agent.tools.mail.DotEnv
import java.io.File

internal data class IrcConfig(
    val server: String,
    val port: Int,
    val tls: Boolean,
    val channel: String,
    val nick: String,
    val serverPassword: String?,
    val channelKey: String?,
    val nickServPassword: String?,
    val autoForwardToAgent: Boolean,
)

internal object IrcConfigLoader {
    const val skillName: String = "irc-cli"

    fun loadFromAgentsRoot(agentsRoot: File): IrcConfig? {
        val env = File(agentsRoot, "skills/$skillName/secrets/.env")
        val values = DotEnv.load(env)

        val server = values["IRC_SERVER"]?.trim().orEmpty()
        val port = values["IRC_PORT"]?.trim()?.toIntOrNull()
        val channel = values["IRC_CHANNEL"]?.trim().orEmpty()
        val nick = values["IRC_NICK"]?.trim().orEmpty()
        if (server.isBlank() || port == null || channel.isBlank() || nick.isBlank()) return null

        val tls = parseBool(values["IRC_TLS"])
        val serverPassword = values["IRC_SERVER_PASSWORD"]?.trim()?.takeIf { it.isNotBlank() }
        val channelKey = values["IRC_CHANNEL_KEY"]?.trim()?.takeIf { it.isNotBlank() }
        val nickServPassword = values["IRC_NICKSERV_PASSWORD"]?.trim()?.takeIf { it.isNotBlank() }
        val autoForwardToAgent = parseBool(values["IRC_AUTO_FORWARD_TO_AGENT"])

        return IrcConfig(
            server = server,
            port = port,
            tls = tls,
            channel = channel,
            nick = nick,
            serverPassword = serverPassword,
            channelKey = channelKey,
            nickServPassword = nickServPassword,
            autoForwardToAgent = autoForwardToAgent,
        )
    }

    private fun parseBool(raw: String?): Boolean {
        val v = raw?.trim()?.lowercase().orEmpty()
        return v == "1" || v == "true" || v == "yes" || v == "on"
    }
}

