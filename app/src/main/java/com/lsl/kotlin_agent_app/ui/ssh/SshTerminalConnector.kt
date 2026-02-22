package com.lsl.kotlin_agent_app.ui.ssh

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jediterm.terminal.TtyConnector
import com.lsl.kotlin_agent_app.agent.tools.ssh.SshKnownHostsStore
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets

internal class SshHostKeyUntrusted(
    val fingerprint: String,
    message: String,
) : RuntimeException(message)

internal class SshHostKeyMismatch(
    val expectedFingerprint: String,
    val actualFingerprint: String,
    message: String,
) : RuntimeException(message)

internal class SshTerminalConnector(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String?,
    private val privateKeyAbsolutePath: String?,
    private val passphrase: String?,
    private val knownHostsStore: SshKnownHostsStore,
    private val trustOnFirstUse: Boolean,
) : TtyConnector {

    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var bufferedInput: BufferedInputStream? = null
    private var outputStream: OutputStream? = null
    private var reader: InputStreamReader? = null

    fun connect(
        columns: Int = 80,
        rows: Int = 24,
    ) {
        knownHostsStore.load()

        val jsch = JSch()
        if (!privateKeyAbsolutePath.isNullOrBlank()) {
            try {
                if (!passphrase.isNullOrBlank()) {
                    jsch.addIdentity(privateKeyAbsolutePath, passphrase)
                } else {
                    jsch.addIdentity(privateKeyAbsolutePath)
                }
            } catch (t: Throwable) {
                throw IOException("invalid private key", t)
            }
        }

        val sshSession =
            try {
                jsch.getSession(username, host, port).apply {
                    if (!password.isNullOrBlank()) {
                        setPassword(password)
                    }
                    setConfig("StrictHostKeyChecking", "no")
                    connect(12_000)
                }
            } catch (t: JSchException) {
                throw IOException("ssh session connect failed", t)
            }

        try {
            val hk = sshSession.hostKey
            val fingerprint = hk?.getFingerPrint(jsch).orEmpty().trim()

            val existing = knownHostsStore.getFingerprint(host = host, port = port)
            if (!existing.isNullOrBlank() && existing != fingerprint) {
                throw SshHostKeyMismatch(
                    expectedFingerprint = existing,
                    actualFingerprint = fingerprint,
                    message = "host key mismatch for $host:$port",
                )
            }
            if (existing.isNullOrBlank() && fingerprint.isNotBlank() && !trustOnFirstUse) {
                throw SshHostKeyUntrusted(
                    fingerprint = fingerprint,
                    message = "unknown host key for $host:$port",
                )
            }
            if (existing.isNullOrBlank() && fingerprint.isNotBlank() && trustOnFirstUse) {
                knownHostsStore.putFingerprint(host = host, port = port, fingerprint = fingerprint)
                knownHostsStore.write()
            }

            val sshChannel = (sshSession.openChannel("shell") as ChannelShell).apply {
                setPtyType("xterm-256color", columns, rows, columns * 8, rows * 16)
            }

            val input = sshChannel.inputStream
            val output = sshChannel.outputStream

            sshChannel.connect(12_000)

            session = sshSession
            channel = sshChannel
            outputStream = output

            val buffered = BufferedInputStream(input)
            bufferedInput = buffered
            reader = InputStreamReader(buffered, StandardCharsets.UTF_8)

            val initCmd = "export LANG=en_US.UTF-8; export LC_ALL=en_US.UTF-8; stty iutf8\n"
            outputStream!!.write(initCmd.toByteArray(StandardCharsets.UTF_8))
            outputStream!!.flush()
        } catch (t: Throwable) {
            runCatching { sshSession.disconnect() }
            throw t
        }
    }

    override fun read(
        buf: CharArray,
        offset: Int,
        length: Int,
    ): Int {
        val localReader = reader ?: throw IOException("SSH channel not connected")
        return localReader.read(buf, offset, length)
    }

    override fun write(bytes: ByteArray) {
        val out = outputStream ?: throw IOException("SSH channel not connected")
        out.write(bytes)
        out.flush()
    }

    override fun write(string: String) {
        val bytes = string.toByteArray(StandardCharsets.UTF_8)
        write(bytes)
    }

    override fun isConnected(): Boolean {
        val ch = channel
        return ch != null && ch.isConnected && !ch.isClosed
    }

    override fun ready(): Boolean {
        val input = bufferedInput ?: return false
        return input.available() > 0
    }

    override fun getName(): String = "ssh [$username@$host:$port]"

    override fun close() {
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
    }

    fun resizePty(columns: Int, rows: Int) {
        val ch = channel ?: return
        if (ch.isConnected && !ch.isClosed) {
            ch.setPtySize(columns, rows, columns * 8, rows * 16)
        }
    }
}

