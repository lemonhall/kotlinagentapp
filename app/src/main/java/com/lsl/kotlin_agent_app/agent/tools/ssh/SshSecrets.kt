package com.lsl.kotlin_agent_app.agent.tools.ssh

import com.lsl.kotlin_agent_app.agent.tools.mail.DotEnv
import java.io.File

internal data class SshSecrets(
    val password: String,
    val privateKeyPath: String,
    val privateKeyPassphrase: String,
    val knownHostsPath: String,
)

internal object SshSecretsLoader {
    const val skillName: String = "ssh-cli"
    private const val defaultKnownHostsPath: String = "workspace/ssh/known_hosts"

    fun loadFromAgentsRoot(agentsRoot: File): SshSecrets? {
        val env = File(agentsRoot, "skills/$skillName/secrets/.env")
        val values = DotEnv.load(env)

        val password = values["SSH_PASSWORD"]?.trim().orEmpty()
        val privateKeyPath = values["SSH_PRIVATE_KEY_PATH"]?.trim().orEmpty()
        val privateKeyPassphrase = values["SSH_PRIVATE_KEY_PASSPHRASE"]?.trim().orEmpty()
        val knownHostsPath = values["SSH_KNOWN_HOSTS_PATH"]?.trim().takeIf { !it.isNullOrBlank() } ?: defaultKnownHostsPath

        if (password.isBlank() && privateKeyPath.isBlank()) return null
        return SshSecrets(
            password = password,
            privateKeyPath = privateKeyPath,
            privateKeyPassphrase = privateKeyPassphrase,
            knownHostsPath = knownHostsPath,
        )
    }
}

