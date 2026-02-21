package com.lsl.kotlin_agent_app.ui.irc

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.agent.tools.irc.IrcConfigLoader
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.irc.IrcCommand
import com.lsl.kotlin_agent_app.ui.env_editor.EnvEditorActivity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class IrcChatLine(
    val key: String,
    val tsMs: Long,
    val channel: String,
    val nick: String,
    val text: String,
    val direction: String,
)

internal data class IrcUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val hasCredentials: Boolean = false,
    val state: String = "not_initialized",
    val nick: String = "",
    val defaultChannel: String = "",
    val selectedChannel: String = "",
    val messages: List<IrcChatLine> = emptyList(),
)

internal class IrcViewModel(
    appContext: Context,
) : ViewModel() {
    private val ctx = appContext.applicationContext
    private val ws = AgentsWorkspace(ctx)
    private val agentsRoot = File(ctx.filesDir, ".agents").canonicalFile
    private val cmd = IrcCommand(ctx)

    private val _uiState = MutableStateFlow(IrcUiState())
    val uiState: StateFlow<IrcUiState> = _uiState

    private var tickerJob: Job? = null

    fun start() {
        if (tickerJob?.isActive == true) return
        tickerJob =
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    ws.ensureInitialized()
                    ws.mkdir(".agents/workspace/irc")
                }

                refreshCredentials()
                refreshStatus()
                if ((_uiState.value.hasCredentials)) {
                    connect()
                }

                while (isActive) {
                    try {
                        refreshCredentials()
                        val st = _uiState.value
                        if (st.hasCredentials) {
                            refreshStatus()
                            val now = _uiState.value
                            if (now.state == "joined" || now.state == "connected") {
                                val ch = now.selectedChannel.ifBlank { now.defaultChannel }
                                if (ch.isNotBlank()) {
                                    pullOnce(channel = ch, limit = 30)
                                }
                            }
                        }
                    } catch (_: Throwable) {
                        // best-effort; keep ticker running
                    }
                    delay(1_200)
                }
            }
    }

    fun setSelectedChannel(channel: String) {
        val ch = channel.trim()
        _uiState.update { prev ->
            prev.copy(selectedChannel = ch)
        }
    }

    fun openCredentialsEditor() {
        val intent =
            EnvEditorActivity.intentOf(
                context = ctx,
                agentsPath = ".agents/skills/irc-cli/secrets/.env",
                displayName = "irc .env",
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    fun connect(force: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val argv =
                    buildList {
                        add("irc")
                        add("connect")
                        if (force) add("--force")
                    }
                val out =
                    withContext(Dispatchers.IO) {
                        cmd.run(argv = argv, stdin = null)
                    }
                if (out.exitCode != 0) {
                    throw IllegalStateException(out.errorMessage ?: out.stderr.ifBlank { "irc connect failed" })
                }
                _uiState.update { it.copy(isLoading = false) }
            } catch (t: Throwable) {
                _uiState.update { it.copy(isLoading = false, errorMessage = t.message ?: "irc connect error") }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val out =
                    withContext(Dispatchers.IO) {
                        cmd.run(argv = listOf("irc", "disconnect"), stdin = null)
                    }
                if (out.exitCode != 0) {
                    throw IllegalStateException(out.errorMessage ?: out.stderr.ifBlank { "irc disconnect failed" })
                }
                _uiState.update { it.copy(isLoading = false, state = "not_initialized") }
            } catch (t: Throwable) {
                _uiState.update { it.copy(isLoading = false, errorMessage = t.message ?: "irc disconnect error") }
            }
        }
    }

    fun pullNow() {
        val st = _uiState.value
        val ch = st.selectedChannel.ifBlank { st.defaultChannel }
        if (ch.isBlank()) return
        viewModelScope.launch {
            try {
                pullOnce(channel = ch, limit = 50)
            } catch (t: Throwable) {
                _uiState.update { it.copy(errorMessage = t.message ?: "irc pull error") }
            }
        }
    }

    fun send(
        to: String,
        text: String,
        confirmNonDefault: Boolean,
    ) {
        val trimmedText = text.replace("\u0000", "").trim()
        if (trimmedText.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val target = to.trim().ifBlank { _uiState.value.defaultChannel }
                val argv =
                    buildList {
                        add("irc")
                        add("send")
                        if (target.isNotBlank()) {
                            add("--to")
                            add(target)
                        }
                        add("--text-stdin")
                        if (confirmNonDefault) add("--confirm")
                    }
                val out =
                    withContext(Dispatchers.IO) {
                        cmd.run(argv = argv, stdin = trimmedText)
                    }
                if (out.exitCode != 0) {
                    throw IllegalStateException(out.errorMessage ?: out.stderr.ifBlank { "irc send failed" })
                }
                appendLocalOutgoing(channel = target, text = trimmedText)
                _uiState.update { it.copy(isLoading = false) }
            } catch (t: Throwable) {
                _uiState.update { it.copy(isLoading = false, errorMessage = t.message ?: "irc send error") }
            }
        }
    }

    private suspend fun refreshCredentials() {
        val hasCreds =
            withContext(Dispatchers.IO) {
                IrcConfigLoader.loadFromAgentsRoot(agentsRoot) != null
            }
        _uiState.update { it.copy(hasCredentials = hasCreds) }
    }

    private suspend fun refreshStatus() {
        val out =
            withContext(Dispatchers.IO) {
                cmd.run(argv = listOf("irc", "status"), stdin = null)
            }
        if (out.exitCode != 0) {
            val msg = out.errorMessage ?: out.stderr.ifBlank { "irc status failed" }
            _uiState.update { it.copy(errorMessage = msg, state = "error") }
            return
        }
        val obj = out.result?.jsonObject ?: return
        val state = obj["state"]?.jsonPrimitive?.content?.trim().orEmpty()
        val nick = obj["nick"]?.jsonPrimitive?.content?.trim().orEmpty()
        val channel = obj["channel"]?.jsonPrimitive?.content?.trim().orEmpty()
        _uiState.update { prev ->
            val selected = prev.selectedChannel.ifBlank { channel }
            prev.copy(
                isLoading = false,
                errorMessage = null,
                state = state.ifBlank { prev.state },
                nick = nick,
                defaultChannel = channel,
                selectedChannel = selected,
            )
        }
    }

    private suspend fun pullOnce(
        channel: String,
        limit: Int,
    ) {
        val out =
            withContext(Dispatchers.IO) {
                cmd.run(argv = listOf("irc", "pull", "--from", channel, "--limit", limit.toString()), stdin = null)
            }
        if (out.exitCode != 0) {
            throw IllegalStateException(out.errorMessage ?: out.stderr.ifBlank { "irc pull failed" })
        }
        val msgs = out.result?.jsonObject?.get("messages")?.jsonArray?.mapNotNull { el ->
            val obj = el.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content?.trim().orEmpty()
            val ts = obj["ts"]?.jsonPrimitive?.content?.toLongOrNull() ?: System.currentTimeMillis()
            val nick = obj["nick"]?.jsonPrimitive?.content?.trim().orEmpty()
            val text = obj["text"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (id.isBlank()) return@mapNotNull null
            IrcChatLine(
                key = "in:$channel:$id",
                tsMs = ts,
                channel = channel,
                nick = nick,
                text = text,
                direction = "in",
            )
        }.orEmpty()

        if (msgs.isEmpty()) return
        _uiState.update { prev ->
            val existingKeys = prev.messages.asSequence().map { it.key }.toHashSet()
            val merged =
                (prev.messages + msgs.filter { it.key !in existingKeys })
                    .sortedBy { it.tsMs }
                    .takeLast(240)
            prev.copy(messages = merged)
        }
    }

    private fun appendLocalOutgoing(
        channel: String,
        text: String,
    ) {
        val now = System.currentTimeMillis()
        val nick = _uiState.value.nick.ifBlank { "me" }
        val line =
            IrcChatLine(
                key = "out:$channel:$now",
                tsMs = now,
                channel = channel,
                nick = nick,
                text = text,
                direction = "out",
            )
        _uiState.update { prev ->
            val merged = (prev.messages + line).sortedBy { it.tsMs }.takeLast(240)
            prev.copy(messages = merged)
        }
    }

    override fun onCleared() {
        super.onCleared()
        tickerJob?.cancel()
        tickerJob = null
    }

    internal class Factory(
        private val appContext: Context,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(IrcViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return IrcViewModel(appContext = appContext) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
        }
    }
}
