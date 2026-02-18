package com.lsl.kotlin_agent_app.agent.tools.terminal.commands.ssh

import android.content.Context
import com.lsl.kotlin_agent_app.agent.tools.ssh.JschSshClient
import com.lsl.kotlin_agent_app.agent.tools.ssh.SshAuthFailedException
import com.lsl.kotlin_agent_app.agent.tools.ssh.SshClientTestHooks
import com.lsl.kotlin_agent_app.agent.tools.ssh.SshExecRequest
import com.lsl.kotlin_agent_app.agent.tools.ssh.SshKnownHostsStore
import com.lsl.kotlin_agent_app.agent.tools.ssh.SshNetworkException
import com.lsl.kotlin_agent_app.agent.tools.ssh.SshSecretsLoader
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalArtifact
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommandOutput
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.PathEscapesAgentsRoot
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.hasFlag
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.optionalFlagValue
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.parseLongFlag
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.relPath
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.requireFlagValue
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.resolveWithinAgents
import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal class SensitiveArgv(
    message: String,
) : IllegalArgumentException(message)

internal class MissingCredentials(
    message: String,
) : IllegalArgumentException(message)

internal class HostKeyUntrusted(
    message: String,
) : IllegalArgumentException(message)

internal class HostKeyMismatch(
    message: String,
) : IllegalArgumentException(message)

internal class SshCommand(
    appContext: Context,
) : TerminalCommand {
    private val ctx = appContext.applicationContext
    private val agentsRoot = File(ctx.filesDir, ".agents").canonicalFile

    override val name: String = "ssh"
    override val description: String = "SSH client CLI (ssh exec) with stdin-only remote command, known_hosts guardrails, dotenv secrets, and artifact-based outputs."

    override suspend fun run(
        argv: List<String>,
        stdin: String?,
    ): TerminalCommandOutput {
        if (argv.size < 2) return invalidArgs("missing subcommand")
        return try {
            rejectSensitiveArgv(argv)
            when (argv[1].lowercase()) {
                "exec" -> handleExec(argv, stdin)
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
        } catch (t: HostKeyUntrusted) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "unknown host key"),
                errorCode = "HostKeyUntrusted",
                errorMessage = t.message,
            )
        } catch (t: HostKeyMismatch) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "host key mismatch"),
                errorCode = "HostKeyMismatch",
                errorMessage = t.message,
            )
        } catch (t: PathEscapesAgentsRoot) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "path escapes .agents root"),
                errorCode = "PathEscapesAgentsRoot",
                errorMessage = t.message,
            )
        } catch (t: SshAuthFailedException) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "authentication failed"),
                errorCode = "AuthFailed",
                errorMessage = t.message,
            )
        } catch (t: SshNetworkException) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "network error"),
                errorCode = "NetworkError",
                errorMessage = t.message,
            )
        } catch (t: IllegalArgumentException) {
            invalidArgs(t.message ?: "invalid args")
        } catch (t: Throwable) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "ssh error"),
                errorCode = "SshError",
                errorMessage = t.message,
            )
        }
    }

    private fun handleExec(
        argv: List<String>,
        stdin: String?,
    ): TerminalCommandOutput {
        val commandText = stdin?.trimEnd().orEmpty()
        if (commandText.isBlank()) throw IllegalArgumentException("stdin is required for ssh exec")

        val host = requireFlagValue(argv, "--host").trim()
        val port = optionalFlagValue(argv, "--port")?.trim()?.toIntOrNull() ?: 22
        val user = requireFlagValue(argv, "--user").trim()
        val trustHostKey = hasFlag(argv, "--trust-host-key")
        val timeoutMs = parseLongFlag(argv, "--timeout-ms", defaultValue = 15_000L).coerceIn(1_000L, 120_000L)

        if (host.isBlank()) throw IllegalArgumentException("host is empty")
        if (user.isBlank()) throw IllegalArgumentException("user is empty")
        if (port <= 0 || port > 65535) throw IllegalArgumentException("invalid port: $port")

        val outRel = optionalFlagValue(argv, "--out")?.trim()?.takeIf { it.isNotBlank() }
        val outFile = outRel?.let { resolveWithinAgents(agentsRoot, it) }

        val secrets = SshSecretsLoader.loadFromAgentsRoot(agentsRoot) ?: throw MissingCredentials("missing ssh credentials (.env)")
        val password = secrets.password.trim().takeIf { it.isNotBlank() }

        val privateKeyFilePath =
            secrets.privateKeyPath
                .trim()
                .takeIf { it.isNotBlank() }
                ?.let { resolveWithinAgents(agentsRoot, it).absolutePath }
        val privateKeyPassphrase = secrets.privateKeyPassphrase.trim().takeIf { it.isNotBlank() }

        val knownHostsFile = resolveWithinAgents(agentsRoot, secrets.knownHostsPath)
        val store = SshKnownHostsStore(file = knownHostsFile)
        store.load()
        val existingFingerprint = store.getFingerprint(host = host, port = port)
        if (existingFingerprint == null && !trustHostKey) {
            throw HostKeyUntrusted("unknown host; pass --trust-host-key to trust-on-first-use")
        }

        val client = SshClientTestHooks.getClientOrNull() ?: JschSshClient()
        val resp =
            client.exec(
                SshExecRequest(
                    host = host,
                    port = port,
                    user = user,
                    command = commandText,
                    timeoutMs = timeoutMs,
                    password = password,
                    privateKeyFilePath = privateKeyFilePath,
                    privateKeyPassphrase = privateKeyPassphrase,
                ),
            )

        val actualFingerprint = resp.hostKeyFingerprint.trim()
        if (existingFingerprint != null && existingFingerprint != actualFingerprint) {
            throw HostKeyMismatch("host key mismatch for $host:$port")
        }
        if (existingFingerprint == null && trustHostKey && actualFingerprint.isNotBlank()) {
            store.putFingerprint(host = host, port = port, fingerprint = actualFingerprint)
            store.write()
        }

        val stdoutPreview = resp.stdout.take(2000)
        val stderrPreview = resp.stderr.take(2000)

        val artifacts = mutableListOf<TerminalArtifact>()
        if (outFile != null) {
            val parent = outFile.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()

            val stdoutFile = File(outFile.parentFile, outFile.name + ".stdout.txt")
            val stderrFile = File(outFile.parentFile, outFile.name + ".stderr.txt")
            stdoutFile.writeText(resp.stdout, Charsets.UTF_8)
            stderrFile.writeText(resp.stderr, Charsets.UTF_8)

            val outJson =
                buildJsonObject {
                    put("ok", JsonPrimitive(resp.remoteExitStatus == 0))
                    put("command", JsonPrimitive("ssh exec"))
                    put("host", JsonPrimitive(host))
                    put("port", JsonPrimitive(port))
                    put("user", JsonPrimitive(user))
                    put("duration_ms", JsonPrimitive(resp.durationMs))
                    put("remote_exit_status", JsonPrimitive(resp.remoteExitStatus))
                    put("host_key_fingerprint", JsonPrimitive(actualFingerprint))
                    put("out", JsonPrimitive(outRel))
                    put("stdout_preview", JsonPrimitive(stdoutPreview))
                    put("stderr_preview", JsonPrimitive(stderrPreview))
                    put("stdout_path", JsonPrimitive(relPath(agentsRoot, stdoutFile)))
                    put("stderr_path", JsonPrimitive(relPath(agentsRoot, stderrFile)))
                }
            outFile.writeText(outJson.toString() + "\n", Charsets.UTF_8)

            val relOut = relPath(agentsRoot, outFile)
            val relStdout = relPath(agentsRoot, stdoutFile)
            val relStderr = relPath(agentsRoot, stderrFile)
            artifacts.add(TerminalArtifact(path = ".agents/$relOut", mime = "application/json", description = "ssh exec output (structured)"))
            artifacts.add(TerminalArtifact(path = ".agents/$relStdout", mime = "text/plain", description = "ssh exec stdout (full)"))
            artifacts.add(TerminalArtifact(path = ".agents/$relStderr", mime = "text/plain", description = "ssh exec stderr (full)"))
        }

        val ok = resp.remoteExitStatus == 0
        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(ok))
                put("command", JsonPrimitive("ssh exec"))
                put("host", JsonPrimitive(host))
                put("port", JsonPrimitive(port))
                put("user", JsonPrimitive(user))
                put("duration_ms", JsonPrimitive(resp.durationMs))
                put("remote_exit_status", JsonPrimitive(resp.remoteExitStatus))
                put("stdout_preview", JsonPrimitive(stdoutPreview))
                put("stderr_preview", JsonPrimitive(stderrPreview))
                if (outRel != null) put("out", JsonPrimitive(outRel))
            }

        if (!ok) {
            return TerminalCommandOutput(
                exitCode = 1,
                stdout = "",
                stderr = "remote non-zero exit: ${resp.remoteExitStatus}",
                errorCode = "RemoteNonZeroExit",
                errorMessage = "remote non-zero exit: ${resp.remoteExitStatus}",
                result = result,
                artifacts = artifacts,
            )
        }

        return TerminalCommandOutput(
            exitCode = 0,
            stdout = "ssh exec: ok ($host:$port as $user)",
            stderr = "",
            result = result,
            artifacts = artifacts,
        )
    }

    private fun rejectSensitiveArgv(argv: List<String>) {
        val lower = argv.joinToString(" ") { it.lowercase() }
        val bannedFlags = listOf("--password", "--passphrase", "--key", "--secret")
        for (f in bannedFlags) {
            if (argv.any { it.lowercase() == f }) throw SensitiveArgv("sensitive flag is not allowed: $f")
        }
        val bannedKv = listOf("ssh_password=", "ssh_private_key=", "ssh_private_key_path=", "ssh_private_key_passphrase=")
        for (p in bannedKv) {
            if (lower.contains(p)) throw SensitiveArgv("sensitive argv is not allowed")
        }
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
