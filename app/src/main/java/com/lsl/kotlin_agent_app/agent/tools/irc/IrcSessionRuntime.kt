package com.lsl.kotlin_agent_app.agent.tools.irc

import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class IrcSessionRuntime(
    private val agentsRoot: File,
    private val sessionKey: String,
    private val config: IrcConfig,
    private val scope: CoroutineScope,
    private val statusFlow: kotlinx.coroutines.flow.MutableStateFlow<IrcStatusSnapshot>,
    private val onInbound: (IrcInboundMessage) -> Unit,
    private val onLog: (String) -> Unit,
) : IrcClientListener {
    private val mutex = Mutex()
    private val buffer = ArrayDeque<IrcInboundMessage>()
    private val bufferCap = 500
    private val seqByChannel = ConcurrentHashMap<String, Long>()
    private val cursors: MutableMap<String, String> = IrcCursorStore.loadCursors(agentsRoot, sessionKey)
    private var droppedTotal: Long = 0L
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var reconnectJob: Job? = null
    private val reconnectBackoffMs = longArrayOf(5_000L, 15_000L, 30_000L)

    private val client: IrcClient =
        IrcClientFactory.create(
            config = config,
            scope = scope,
            listener = this,
        )

    init {
        cursors.forEach { (channel, cursor) ->
            val seq = cursor.toLongOrNull() ?: 0L
            if (seq > 0L) seqByChannel[channel] = seq
        }
        // NOTE: cursor only tracks "last pulled seq", not "last received seq".
        // If the app restarts and new messages arrive, seq must continue from the last inbound seq to avoid
        // duplicates and cursor regressions.
        loadMaxSeqByChannelFromInboundJsonl().forEach { (channel, maxSeq) ->
            val current = seqByChannel[channel] ?: 0L
            if (maxSeq > current) seqByChannel[channel] = maxSeq
        }
        statusFlow.value =
            IrcStatusSnapshot(
                state = IrcConnectionState.NotInitialized,
                server = config.server,
                port = config.port,
                tls = config.tls,
                nick = config.nick,
                channel = config.channel,
                autoForwardToAgent = config.autoForwardToAgent,
            )
    }

    private fun loadMaxSeqByChannelFromInboundJsonl(): Map<String, Long> {
        val safe = sessionKey.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val f = File(agentsRoot, "workspace/irc/sessions/$safe/inbound.jsonl")
        if (!f.exists() || !f.isFile) return emptyMap()

        val maxTailLines = 5000
        val tail = ArrayDeque<String>(maxTailLines + 1)
        try {
            f.forEachLine(Charsets.UTF_8) { line ->
                val s = line.trim()
                if (s.isBlank()) return@forEachLine
                if (tail.size >= maxTailLines) tail.removeFirst()
                tail.addLast(s)
            }
        } catch (_: Throwable) {
            return emptyMap()
        }

        val maxByChannel = linkedMapOf<String, Long>()
        for (line in tail) {
            val obj =
                try {
                    json.parseToJsonElement(line).jsonObject
                } catch (_: Throwable) {
                    continue
                }
            val channel = obj["channel"]?.jsonPrimitive?.content?.trim().orEmpty()
            val id = obj["id"]?.jsonPrimitive?.content?.trim().orEmpty()
            val seq = id.toLongOrNull() ?: continue
            if (channel.isBlank()) continue
            val prev = maxByChannel[channel] ?: 0L
            if (seq > prev) maxByChannel[channel] = seq
        }
        return maxByChannel
    }

    suspend fun ensureConnectedAndJoinedDefault(): Unit =
        mutex.withLock {
            val st = statusFlow.value
            if (st.state == IrcConnectionState.Joined) return@withLock
            statusFlow.value = st.copy(state = IrcConnectionState.Connecting, lastError = null)
            try {
                if (!client.isConnected) {
                    onLog("connect()")
                    withContext(Dispatchers.IO) { client.connect() }
                }
                if (!client.isConnected) error("connect returned but client is not connected")
                statusFlow.value = statusFlow.value.copy(state = IrcConnectionState.Connected)
                onLog("join(default)")
                withContext(Dispatchers.IO) { client.join(channel = config.channel, key = config.channelKey) }
                statusFlow.value = statusFlow.value.copy(state = IrcConnectionState.Joined)
            } catch (t: CancellationException) {
                onLog("connect/join cancelled: ${t.message ?: "cancelled"}")
                throw t
            } catch (t: Throwable) {
                onLog("connect/join failed(${t::class.java.simpleName}): ${t.message ?: "unknown"}")
                val (errCode, errMsg) =
                    (t as? com.lsl.kotlin_agent_app.agent.tools.irc.BasicIrcClient.IrcProtocolException)?.let { ex ->
                        ex.errorCode to (ex.message ?: "protocol error")
                    } ?: ("NetworkError" to (t.message ?: "network error"))
                statusFlow.value =
                    statusFlow.value.copy(
                        state = IrcConnectionState.Error,
                        lastError = IrcLastError(errorCode = errCode, message = errMsg),
                    )
                maybeScheduleReconnect(t, reason = "connect/join failed")
                throw t
            }
        }

    private fun maybeScheduleReconnect(
        t: Throwable,
        reason: String,
    ) {
        val code = (t as? com.lsl.kotlin_agent_app.agent.tools.irc.BasicIrcClient.IrcProtocolException)?.errorCode
        val retryable =
            when (code) {
                "PassIncorrect",
                "NickInUse",
                "BadChannelKey",
                "InviteOnly",
                "BannedFromChannel",
                -> false

                else -> true
            }
        if (!retryable) return
        scheduleReconnect(reason)
    }

    private fun scheduleReconnect(reason: String) {
        val existing = reconnectJob
        if (existing?.isActive == true) return
        reconnectJob =
            scope.launch(Dispatchers.IO) {
                onLog("auto-reconnect: $reason")
                var attempt = 0
                while (true) {
                    val delayMs = reconnectBackoffMs[minOf(attempt, reconnectBackoffMs.size - 1)]
                    attempt += 1
                    delay(delayMs)
                    try {
                        statusFlow.value = statusFlow.value.copy(state = IrcConnectionState.Reconnecting)
                        ensureConnectedAndJoinedDefault()
                        onLog("auto-reconnect: ok")
                        return@launch
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Throwable) {
                        onLog("auto-reconnect failed(${t::class.java.simpleName}): ${t.message ?: "unknown"}")
                    }
                }
            }
    }

    suspend fun sendPrivmsg(
        to: String,
        text: String,
    ) {
        try {
            mutex.withLock {
                if (!client.isConnected) {
                    statusFlow.value = statusFlow.value.copy(state = IrcConnectionState.Reconnecting)
                    onLog("reconnect()")
                    withContext(Dispatchers.IO) { client.connect() }
                    if (!client.isConnected) error("connect returned but client is not connected")
                    statusFlow.value = statusFlow.value.copy(state = IrcConnectionState.Connected)
                }
            }
            withContext(Dispatchers.IO) { client.privmsg(target = to, text = text) }
        } catch (t: CancellationException) {
            onLog("send cancelled: ${t.message ?: "cancelled"}")
            throw t
        } catch (t: Throwable) {
            onLog("send failed(${t::class.java.simpleName}): ${t.message ?: "unknown"}")
            statusFlow.value =
                statusFlow.value.copy(
                    state = IrcConnectionState.Error,
                    lastError = IrcLastError(errorCode = "SendFailed", message = t.message ?: "send failed"),
                )
            throw t
        }
    }

    data class PullResult(
        val cursorBefore: String?,
        val cursorAfter: String?,
        val returned: Int,
        val messages: List<IrcInboundMessage>,
        val truncated: Boolean,
        val droppedCount: Long,
    )

    suspend fun pull(
        from: String,
        limit: Int,
        peek: Boolean,
    ): PullResult =
        mutex.withLock {
            val cursorBefore = cursors[from]
            val beforeSeq = cursorBefore?.toLongOrNull() ?: 0L
            val snapshot: List<IrcInboundMessage>
            val droppedSnapshot: Long
            synchronized(buffer) {
                snapshot = buffer.toList()
                droppedSnapshot = droppedTotal
            }

            val candidates =
                snapshot
                    .asSequence()
                    .filter { it.channel == from && it.seq > beforeSeq }
                    .sortedBy { it.seq }
                    .take(limit.coerceAtLeast(0))
                    .map { it.copy(text = IrcTruncation.truncateMessageText(it.text)) }
                    .toList()

            val afterSeq = candidates.lastOrNull()?.seq
            val cursorAfter = afterSeq?.toString()
            val batch = IrcTruncation.truncateBatch(candidates)

            if (!peek && cursorAfter != null) {
                cursors[from] = cursorAfter
                IrcCursorStore.saveCursors(agentsRoot, sessionKey, cursors)
            }

            PullResult(
                cursorBefore = cursorBefore,
                cursorAfter = if (!peek) cursorAfter else cursorBefore,
                returned = candidates.size,
                messages = batch.messages,
                truncated = batch.truncated,
                droppedCount = droppedSnapshot,
            )
        }

    fun close() {
        runCatching {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(1_000) {
                    client.disconnect("session closed")
                }
            }
        }
        runCatching { scope.cancel("session closed") }
    }

    override fun onPrivmsg(
        channel: String,
        nick: String,
        text: String,
        tsMs: Long,
    ) {
        val safeText = text.replace("\u0000", "").replace("\r\n", "\n").take(16_000)
        val seq = seqByChannel.compute(channel) { _, v -> (v ?: 0L) + 1L } ?: 1L
        val id = seq.toString()

        val msg =
            IrcInboundMessage(
                id = id,
                tsMs = tsMs,
                channel = channel,
                nick = nick.take(64),
                text = safeText,
                seq = seq,
            )

        synchronized(buffer) {
            buffer.addLast(msg)
            while (buffer.size > bufferCap) {
                buffer.removeFirst()
                droppedTotal += 1L
            }
        }

        appendInboundJsonl(msg)
        onInbound(msg)
    }

    override fun onDisconnected(message: String?) {
        onLog("disconnected: ${message ?: "unknown"}")
        val st = statusFlow.value
        statusFlow.value =
            st.copy(
                state = IrcConnectionState.Disconnected,
                lastError = message?.takeIf { it.isNotBlank() }?.let { IrcLastError("Disconnected", it) } ?: st.lastError,
            )
        scheduleReconnect("disconnected")
    }

    override fun onDebugEvent(
        kind: String,
        message: String,
    ) {
        val k = kind.trim().ifBlank { "debug" }
        val m = message.trim().ifBlank { return }
        onLog("$k: $m")
    }

    private fun appendInboundJsonl(msg: IrcInboundMessage) {
        try {
            val safe = sessionKey.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val dir = File(agentsRoot, "workspace/irc/sessions/$safe")
            dir.mkdirs()
            val f = File(dir, "inbound.jsonl")
            val json =
                """{"id":"${msg.id}","ts_ms":${msg.tsMs},"channel":${escapeJson(msg.channel)},"nick":${escapeJson(msg.nick)},"text":${escapeJson(msg.text)}}"""
            f.appendText(json + "\n", Charsets.UTF_8)
        } catch (_: Throwable) {
            // best-effort
        }
    }

    private fun escapeJson(s: String): String {
        val escaped =
            s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
        return "\"$escaped\""
    }
}
