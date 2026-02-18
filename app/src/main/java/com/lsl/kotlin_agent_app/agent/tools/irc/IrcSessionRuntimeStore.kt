package com.lsl.kotlin_agent_app.agent.tools.irc

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object IrcSessionRuntimeStore {
    private const val maxLogLines: Int = 200
    private const val connectThrottleMs: Long = 15_000L
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class Entry(
        val status: MutableStateFlow<IrcStatusSnapshot> = MutableStateFlow(IrcStatusSnapshot.notInitialized()),
        var runtime: IrcSessionRuntime? = null,
        val mutex: Mutex = Mutex(),
        val logs: MutableStateFlow<List<String>> = MutableStateFlow(emptyList()),
        var lastConnectAttemptMs: Long = 0L,
        var activeConnectJob: Job? = null,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun statusFlow(sessionKey: String): StateFlow<IrcStatusSnapshot> {
        val e = entries.getOrPut(sessionKey) { Entry() }
        return e.status.asStateFlow()
    }

    fun logsFlow(sessionKey: String): StateFlow<List<String>> {
        val e = entries.getOrPut(sessionKey) { Entry() }
        return e.logs.asStateFlow()
    }

    fun requestConnect(
        agentsRoot: File,
        sessionKey: String,
        force: Boolean,
    ) {
        val e = entries.getOrPut(sessionKey) { Entry() }
        if (e.activeConnectJob?.isActive == true) return
        e.activeConnectJob =
            backgroundScope.launch {
                try {
                    ensureConnectedAndJoinedIfPossibleInternal(agentsRoot = agentsRoot, sessionKey = sessionKey, force = force)
                } finally {
                    e.activeConnectJob = null
                }
            }
    }

    private suspend fun ensureConnectedAndJoinedIfPossibleInternal(
        agentsRoot: File,
        sessionKey: String,
        force: Boolean,
    ) {
        val e = entries.getOrPut(sessionKey) { Entry() }
        val now = System.currentTimeMillis()
        if (!force && (now - e.lastConnectAttemptMs) < connectThrottleMs) return
        e.lastConnectAttemptMs = now

        val config =
            IrcConfigLoader.loadFromAgentsRoot(agentsRoot)
                ?: run {
                    appendLog(e, "MissingCredentials: .agents/skills/${IrcConfigLoader.skillName}/secrets/.env incomplete")
                    e.status.value =
                        e.status.value.copy(
                            state = IrcConnectionState.Error,
                            lastError = IrcLastError(errorCode = "MissingCredentials", message = "IRC .env 未配置或字段不完整"),
                        )
                    return
                }
        if (config.nick.length > 9) {
            appendLog(e, "NickTooLong: IRC_NICK length=${config.nick.length} (>9)")
            e.status.value =
                e.status.value.copy(
                    state = IrcConnectionState.Error,
                    lastError = IrcLastError(errorCode = "NickTooLong", message = "IRC_NICK 长度必须 <= 9"),
                )
            return
        }

        val rt = getOrCreateRuntime(agentsRoot = agentsRoot, sessionKey = sessionKey, config = config)
        appendLog(e, "Connecting…")
        try {
            rt.ensureConnectedAndJoinedDefault()
            appendLog(e, "Joined.")
        } catch (t: CancellationException) {
            appendLog(e, "Cancelled: ${t.message ?: "composition left"}")
        } catch (t: Throwable) {
            appendLog(e, "Error(${t::class.java.simpleName}): ${t.message ?: "unknown"}")
        }
    }

    suspend fun getOrCreateRuntime(
        agentsRoot: File,
        sessionKey: String,
        config: IrcConfig,
    ): IrcSessionRuntime {
        val e = entries.getOrPut(sessionKey) { Entry() }
        return e.mutex.withLock {
            val existing = e.runtime
            if (existing != null) return@withLock existing
            val parentJob = backgroundScope.coroutineContext[Job]
            val scope = CoroutineScope(SupervisorJob(parentJob) + Dispatchers.IO)
            val rt =
                IrcSessionRuntime(
                    agentsRoot = agentsRoot,
                    sessionKey = sessionKey,
                    config = config,
                    scope = scope,
                    statusFlow = e.status,
                    onLog = { msg -> appendLog(e, msg) },
                )
            e.runtime = rt
            rt
        }
    }

    fun closeSession(sessionKey: String) {
        val e = entries[sessionKey] ?: return
        val rt = e.runtime
        e.runtime = null
        rt?.close()
        e.status.value = IrcStatusSnapshot.notInitialized()
        e.logs.value = emptyList()
    }

    fun clearForTest() {
        entries.keys.toList().forEach { closeSession(it) }
        entries.clear()
    }

    private fun appendLog(
        entry: Entry,
        message: String,
    ) {
        val ts = System.currentTimeMillis()
        val line = "$ts $message"
        val prev = entry.logs.value
        val next =
            if (prev.size < maxLogLines) {
                prev + line
            } else {
                prev.drop(prev.size - (maxLogLines - 1)) + line
            }
        entry.logs.value = next
    }
}
