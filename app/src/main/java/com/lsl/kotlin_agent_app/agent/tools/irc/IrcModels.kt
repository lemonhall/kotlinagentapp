package com.lsl.kotlin_agent_app.agent.tools.irc

internal enum class IrcConnectionState {
    NotInitialized,
    Connecting,
    Connected,
    Joined,
    Reconnecting,
    Disconnected,
    Error,
}

internal data class IrcLastError(
    val errorCode: String,
    val message: String,
)

internal data class IrcStatusSnapshot(
    val state: IrcConnectionState,
    val server: String? = null,
    val port: Int? = null,
    val tls: Boolean? = null,
    val nick: String? = null,
    val channel: String? = null,
    val autoForwardToAgent: Boolean = false,
    val lastError: IrcLastError? = null,
) {
    companion object {
        fun notInitialized(): IrcStatusSnapshot = IrcStatusSnapshot(state = IrcConnectionState.NotInitialized)
    }
}

internal data class IrcInboundMessage(
    val id: String,
    val tsMs: Long,
    val channel: String,
    val nick: String,
    val text: String,
    val seq: Long,
)

