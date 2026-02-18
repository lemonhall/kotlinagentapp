package com.lsl.kotlin_agent_app.agent.tools.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import java.io.ByteArrayOutputStream
import java.util.Properties

internal class SshAuthFailedException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class SshNetworkException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class JschSshClient : SshClient {
    override fun exec(request: SshExecRequest): SshExecResponse {
        val started = System.currentTimeMillis()
        val jsch = JSch()

        val keyPath = request.privateKeyFilePath?.trim().orEmpty()
        if (keyPath.isNotBlank()) {
            try {
                val pass = request.privateKeyPassphrase?.takeIf { it.isNotBlank() }
                jsch.addIdentity(keyPath, pass)
            } catch (t: Throwable) {
                throw SshAuthFailedException("invalid private key", t)
            }
        }

        val session =
            try {
                jsch.getSession(request.user, request.host, request.port)
            } catch (t: Throwable) {
                throw SshNetworkException("session error", t)
            }

        val config = Properties()
        config["StrictHostKeyChecking"] = "no"
        session.setConfig(config)

        val password = request.password?.trim().orEmpty()
        if (password.isNotBlank()) {
            session.setPassword(password)
        }

        try {
            val connectTimeout = request.timeoutMs.coerceIn(1_000L, 120_000L).toInt()
            session.connect(connectTimeout)

            val hk = session.hostKey
            val fingerprint = hk?.getFingerPrint(jsch).orEmpty()

            val channel = (session.openChannel("exec") as ChannelExec)
            channel.setCommand(request.command)
            channel.inputStream = null

            val stdoutBytes = ByteArrayOutputStream()
            val stderrBytes = ByteArrayOutputStream()
            channel.setErrStream(stderrBytes, true)
            val stdoutStream = channel.inputStream

            channel.connect(connectTimeout)

            val buf = ByteArray(8192)
            while (true) {
                val n = stdoutStream.read(buf)
                if (n <= 0) break
                stdoutBytes.write(buf, 0, n)
            }

            while (!channel.isClosed) {
                val elapsed = System.currentTimeMillis() - started
                if (elapsed > request.timeoutMs.coerceAtLeast(1_000L)) {
                    throw SshNetworkException("timeout")
                }
                Thread.sleep(10)
            }

            val exit = channel.exitStatus
            channel.disconnect()
            session.disconnect()

            val duration = System.currentTimeMillis() - started
            val stdout = stdoutBytes.toString(Charsets.UTF_8.name())
            val stderr = stderrBytes.toString(Charsets.UTF_8.name())
            return SshExecResponse(
                stdout = stdout,
                stderr = stderr,
                remoteExitStatus = exit,
                hostKeyFingerprint = fingerprint,
                durationMs = duration,
            )
        } catch (t: SshNetworkException) {
            runCatching { session.disconnect() }
            throw t
        } catch (t: JSchException) {
            runCatching { session.disconnect() }
            val msg = t.message?.lowercase().orEmpty()
            if (msg.contains("auth fail") || msg.contains("authentication")) {
                throw SshAuthFailedException("authentication failed", t)
            }
            throw SshNetworkException("network error", t)
        } catch (t: Throwable) {
            runCatching { session.disconnect() }
            throw SshNetworkException(t.message ?: "ssh error", t)
        }
    }
}

