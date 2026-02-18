package com.lsl.kotlin_agent_app.agent.tools.irc

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal interface IrcClientListener {
    fun onPrivmsg(
        channel: String,
        nick: String,
        text: String,
        tsMs: Long,
    )

    fun onDisconnected(message: String?)

    fun onDebugEvent(
        kind: String,
        message: String,
    ) {
    }
}

internal interface IrcClient {
    val isConnected: Boolean

    suspend fun connect()

    suspend fun disconnect(message: String? = null)

    suspend fun join(
        channel: String,
        key: String? = null,
    )

    suspend fun privmsg(
        target: String,
        text: String,
    )
}

internal object IrcClientTestHooks {
    @Volatile private var overrideFactory: ((IrcConfig, CoroutineScope, IrcClientListener) -> IrcClient)? = null

    fun install(factory: (IrcConfig, CoroutineScope, IrcClientListener) -> IrcClient) {
        overrideFactory = factory
    }

    fun clear() {
        overrideFactory = null
    }

    internal fun createOrNull(
        config: IrcConfig,
        scope: CoroutineScope,
        listener: IrcClientListener,
    ): IrcClient? = overrideFactory?.invoke(config, scope, listener)
}

internal object IrcClientFactory {
    fun create(
        config: IrcConfig,
        scope: CoroutineScope,
        listener: IrcClientListener,
    ): IrcClient {
        return IrcClientTestHooks.createOrNull(config, scope, listener)
            ?: BasicIrcClient(config = config, scope = scope, listener = listener)
    }
}

internal class BasicIrcClient(
    private val config: IrcConfig,
    private val scope: CoroutineScope,
    private val listener: IrcClientListener,
) : IrcClient {
    internal class IrcProtocolException(
        val errorCode: String,
        message: String,
    ) : RuntimeException(message)

    private val connected = AtomicBoolean(false)
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var readJob: Job? = null
    private var connectReady: CompletableDeferred<Unit>? = null
    private val joinWaiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private var currentNick: String = config.nick
    private var registeredNick: String? = null
    private var nickInUseRetries: Int = 0

    override val isConnected: Boolean get() = connected.get()

    override suspend fun connect() {
        if (connected.get()) return
        // Reset any half-open state before reconnecting.
        if (socket != null || writer != null || readJob != null) {
            disconnect(message = "reconnect")
        }
        withContext(Dispatchers.IO) {
            val s =
                if (config.tls) {
                    (SSLSocketFactory.getDefault().createSocket(config.server, config.port) as SSLSocket).apply {
                        enabledProtocols = enabledProtocols
                        soTimeout = 0
                        keepAlive = true
                        tcpNoDelay = true
                        startHandshake()
                    }
                } else {
                    Socket(config.server, config.port).apply {
                        soTimeout = 0
                        keepAlive = true
                        tcpNoDelay = true
                    }
                }
            socket = s
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
        }

        connectReady = CompletableDeferred()
        currentNick = config.nick
        registeredNick = null
        nickInUseRetries = 0
        if (readJob == null) {
            readJob = startReadLoop()
        }

        try {
            sendLineIfNotBlank("PASS", config.serverPassword)
            sendLine("NICK $currentNick")
            sendLine("USER $currentNick 0 * :$currentNick")

            config.nickServPassword?.let { pass ->
                // Best-effort: NickServ identify after connect. Do not log.
                sendLine("PRIVMSG NickServ :IDENTIFY $pass")
            }

            withTimeout(30_000) {
                connectReady?.await()
            }
            connected.set(true)
            listener.onDebugEvent("state", "registered")
        } catch (t: Throwable) {
            connected.set(false)
            // Close resources so the next attempt starts cleanly.
            runCatching { disconnect(message = "connect failed") }
            throw t
        }
    }

    override suspend fun disconnect(message: String?) {
        try {
            if (connected.get()) {
                runCatching { sendLine("QUIT :${message ?: "bye"}") }
            }
        } finally {
            connected.set(false)
            val job = readJob
            readJob = null
            socket?.close()
            socket = null
            writer = null
            if (job != null) runCatching { job.cancelAndJoin() }
        }
    }

    override suspend fun join(
        channel: String,
        key: String?,
    ) {
        if (!connected.get()) error("not connected")
        val target = channel.trim()
        if (target.isBlank()) error("channel is blank")
        val waiter = CompletableDeferred<Unit>()
        joinWaiters[target] = waiter
        if (key.isNullOrBlank()) {
            sendLine("JOIN $channel")
        } else {
            sendLine("JOIN $channel $key")
        }
        try {
            withTimeout(30_000) {
                waiter.await()
            }
        } finally {
            joinWaiters.remove(target, waiter)
        }
    }

    override suspend fun privmsg(
        target: String,
        text: String,
    ) {
        if (!connected.get()) error("not connected")
        // Keep it bounded; this is still user-provided text.
        val safe = text.replace("\r\n", "\n").take(4000)
        sendLine("PRIVMSG $target :$safe")
    }

    private suspend fun sendLineIfNotBlank(
        verb: String,
        value: String?,
    ) {
        val v = value?.trim().orEmpty()
        if (v.isBlank()) return
        sendLine("$verb $v")
    }

    private suspend fun sendLine(line: String) {
        withContext(Dispatchers.IO) {
            val w = writer ?: error("not connected")
            w.write(line)
            w.write("\r\n")
            w.flush()
        }
    }

    private fun startReadLoop(): Job {
        return scope.launch(Dispatchers.IO) {
            val s = socket ?: return@launch
            val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            var disconnectedReason: String? = null
            try {
                while (true) {
                    val raw = input.readLine() ?: break
                    val line = raw.trimEnd('\r', '\n')
                    if (line.isBlank()) continue

                    val parsed = parseLine(line) ?: continue
                    val cmd = parsed.command

                    if (cmd.equals("PING", ignoreCase = true)) {
                        val token = parsed.trailing?.trim()?.trimStart(':')
                            ?: parsed.params.firstOrNull()?.trim()?.trimStart(':')
                            ?: ""
                        sendLine("PONG :$token")
                        continue
                    }

                    if (cmd == "ERROR") {
                        val msg = parsed.trailing?.trim().orEmpty()
                        disconnectedReason = "ERROR: ${msg.ifBlank { "server error" }}"
                        break
                    }

                    if (cmd.length == 3 && cmd.all { it.isDigit() }) {
                        handleNumeric(parsed)
                        continue
                    }

                    if (cmd == "JOIN") {
                        val nick = parsed.prefixNick().orEmpty()
                        val ch = parsed.trailing?.trim()?.takeIf { it.isNotBlank() } ?: parsed.params.firstOrNull().orEmpty()
                        if (nick == effectiveNick() && ch.isNotBlank()) {
                            joinWaiters[ch]?.complete(Unit)
                            listener.onDebugEvent("join", "confirmed $ch")
                        }
                        continue
                    }

                    if (cmd == "NICK") {
                        val old = parsed.prefixNick().orEmpty()
                        val newNick = parsed.trailing?.trim()?.ifBlank { null }
                        if (newNick != null && old == effectiveNick()) {
                            currentNick = newNick
                            registeredNick = newNick
                            listener.onDebugEvent("nick", "changed -> $newNick")
                        }
                        continue
                    }

                    if (cmd == "PRIVMSG") {
                        val nick = parsed.prefixNick().orEmpty()
                        val target = parsed.params.firstOrNull().orEmpty()
                        val text = parsed.trailing?.orEmpty() ?: ""
                        if (nick.isNotBlank() && target.isNotBlank()) {
                            listener.onPrivmsg(channel = target, nick = nick, text = text, tsMs = System.currentTimeMillis())
                        }
                        continue
                    }
                }
            } catch (t: Throwable) {
                disconnectedReason = "${t::class.java.simpleName}: ${t.message ?: "unknown"}"
            } finally {
                connected.set(false)
                val msg = disconnectedReason ?: "socket closed"
                listener.onDisconnected(msg)
                connectReady?.completeExceptionally(IrcProtocolException("Disconnected", msg))
                joinWaiters.values.toList().forEach { it.completeExceptionally(IrcProtocolException("Disconnected", msg)) }
                joinWaiters.clear()
                runCatching { socket?.close() }
                socket = null
                writer = null
                readJob = null
            }
        }
    }

    private fun effectiveNick(): String = registeredNick?.takeIf { it.isNotBlank() } ?: currentNick

    private fun nextNickAfterInUse(prev: String): String {
        // Keep it bounded. Store layer currently enforces <= 9, so keep it within 9 here too.
        val maxLen = 9
        val candidate = "${prev}_"
        return if (candidate.length <= maxLen) {
            candidate
        } else {
            candidate.take(maxLen - 1) + "_"
        }
    }

    private suspend fun handleNumeric(p: ParsedLine) {
        val code = p.command
        val params = p.params
        val msg = p.trailing?.trim().orEmpty()

        // Params typically: <me> <channel> ...
        when (code) {
            "001", "376", "422" -> {
                params.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let { registeredNick = it }
                connectReady?.complete(Unit)
            }
            "433" -> {
                val ready = connectReady
                if (ready == null || ready.isCompleted) return
                if (nickInUseRetries < 3) {
                    nickInUseRetries += 1
                    currentNick = nextNickAfterInUse(currentNick)
                    sendLine("NICK $currentNick")
                    listener.onDebugEvent("nick", "in-use retry=$nickInUseRetries nick=$currentNick")
                } else {
                    ready.completeExceptionally(IrcProtocolException("NickInUse", msg.ifBlank { "nickname in use" }))
                }
            }
            "464" -> connectReady?.completeExceptionally(IrcProtocolException("PassIncorrect", msg.ifBlank { "password incorrect" }))
            "366" -> {
                val channel = params.getOrNull(1)?.trim().orEmpty()
                if (channel.isNotBlank()) {
                    joinWaiters[channel]?.complete(Unit)
                    listener.onDebugEvent("join", "names-end $channel")
                }
            }
            "403" -> completeJoinError(params, IrcProtocolException("NoSuchChannel", msg.ifBlank { "no such channel" }))
            "471" -> completeJoinError(params, IrcProtocolException("ChannelIsFull", msg.ifBlank { "channel is full" }))
            "473" -> completeJoinError(params, IrcProtocolException("InviteOnly", msg.ifBlank { "invite only" }))
            "474" -> completeJoinError(params, IrcProtocolException("BannedFromChannel", msg.ifBlank { "banned" }))
            "475" -> completeJoinError(params, IrcProtocolException("BadChannelKey", msg.ifBlank { "bad channel key" }))
        }

        // Debug (avoid leaking secrets: we never print PASS/key/password).
        val brief =
            buildString {
                append(code)
                if (msg.isNotBlank()) append(": ").append(msg.take(200))
            }
        listener.onDebugEvent("num", brief)
    }

    private fun completeJoinError(
        params: List<String>,
        ex: IrcProtocolException,
    ) {
        val channel = params.getOrNull(1)?.trim().orEmpty()
        if (channel.isBlank()) return
        joinWaiters[channel]?.completeExceptionally(ex)
    }

    private fun parsePrivmsg(line: String): Triple<String, String, String>? {
        // Example: :nick!user@host PRIVMSG #chan :hello
        if (!line.contains(" PRIVMSG ")) return null
        val prefixEnd = line.indexOf(' ')
        if (prefixEnd <= 1) return null
        val prefix = line.substring(1, prefixEnd)
        val nick = prefix.substringBefore('!').trim()
        val rest = line.substring(prefixEnd + 1)
        val parts = rest.split(' ')
        if (parts.size < 3) return null
        if (parts[0] != "PRIVMSG") return null
        val channel = parts[1]
        val idx = line.indexOf(" :")
        if (idx < 0) return null
        val text = line.substring(idx + 2)
        if (nick.isBlank() || channel.isBlank()) return null
        return Triple(channel, nick, text)
    }

    private data class ParsedLine(
        val prefix: String?,
        val command: String,
        val params: List<String>,
        val trailing: String?,
    ) {
        fun prefixNick(): String? {
            val p = prefix?.trim().orEmpty()
            if (p.isBlank()) return null
            return p.substringBefore('!').trim().ifBlank { null }
        }
    }

    private fun parseLine(line: String): ParsedLine? {
        var s = line.trimEnd()
        if (s.isBlank()) return null

        var prefix: String? = null
        if (s.startsWith(":")) {
            val idx = s.indexOf(' ')
            if (idx <= 1) return null
            prefix = s.substring(1, idx)
            s = s.substring(idx + 1).trimStart()
        }

        var trailing: String? = null
        val trailingIdx = s.indexOf(" :")
        if (trailingIdx >= 0) {
            trailing = s.substring(trailingIdx + 2)
            s = s.substring(0, trailingIdx)
        }

        val tokens = s.split(' ').mapNotNull { it.trim().ifBlank { null } }
        if (tokens.isEmpty()) return null
        val command = tokens[0]
        val params = if (tokens.size > 1) tokens.drop(1) else emptyList()
        return ParsedLine(prefix = prefix, command = command, params = params, trailing = trailing)
    }
}
