package com.lsl.kotlin_agent_app.agent.tools.ssh

internal object SshClientTestHooks {
    @Volatile private var clientOverride: SshClient? = null

    fun install(client: SshClient) {
        clientOverride = client
    }

    fun clear() {
        clientOverride = null
    }

    internal fun getClientOrNull(): SshClient? = clientOverride
}

internal data class SshExecRequest(
    val host: String,
    val port: Int,
    val user: String,
    val command: String,
    val timeoutMs: Long,
    val password: String? = null,
    val privateKeyFilePath: String? = null,
    val privateKeyPassphrase: String? = null,
)

internal data class SshExecResponse(
    val stdout: String,
    val stderr: String,
    val remoteExitStatus: Int,
    val hostKeyFingerprint: String,
    val durationMs: Long,
)

internal interface SshClient {
    fun exec(request: SshExecRequest): SshExecResponse
}

