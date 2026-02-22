package com.lsl.kotlin_agent_app.ui.ssh

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.agent.tools.mail.DotEnv
import com.lsl.kotlin_agent_app.agent.tools.ssh.SshKnownHostsStore
import com.lsl.kotlin_agent_app.agent.tools.ssh.SshSecrets
import com.lsl.kotlin_agent_app.agent.tools.ssh.SshSecretsLoader
import com.lsl.kotlin_agent_app.ui.env_editor.EnvEditorActivity
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SshTrustPrompt(
    val host: String,
    val port: Int,
    val fingerprint: String,
)

internal data class SshUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val hasCredentials: Boolean = false,
    val host: String = "",
    val portText: String = "22",
    val user: String = "",
    val pendingTrust: SshTrustPrompt? = null,
    val autoConnectRequested: Boolean = false,
)

internal class SshViewModel(
    appContext: Context,
) : ViewModel() {
    private val ctx = appContext.applicationContext
    private val ws = AgentsWorkspace(ctx)
    private val agentsRoot = File(ctx.filesDir, ".agents").canonicalFile
    private val secretsEnvFile = File(agentsRoot, "skills/${SshSecretsLoader.skillName}/secrets/.env")
    private val lastStore = SshLastConnectionStore(File(agentsRoot, "workspace/ssh/last.json"))

    private val _uiState = MutableStateFlow(SshUiState())
    val uiState: StateFlow<SshUiState> = _uiState

    private val _connector = MutableStateFlow<SshTerminalConnector?>(null)
    val connector: StateFlow<SshTerminalConnector?> = _connector

    private var connectJob: Job? = null

    fun start() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ws.ensureInitialized()
                ws.mkdir(".agents/workspace/ssh")
            }
            refreshConfig()
        }
    }

    fun openCredentialsEditor() {
        val intent =
            EnvEditorActivity.intentOf(
                context = ctx,
                agentsPath = ".agents/skills/${SshSecretsLoader.skillName}/secrets/.env",
                displayName = "ssh .env",
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    fun updateHost(text: String) {
        _uiState.update { it.copy(host = text, errorMessage = null) }
    }

    fun updatePortText(text: String) {
        _uiState.update { it.copy(portText = text, errorMessage = null) }
    }

    fun updateUser(text: String) {
        _uiState.update { it.copy(user = text, errorMessage = null) }
    }

    fun consumeAutoConnectRequest() {
        _uiState.update { it.copy(autoConnectRequested = false) }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        val old = _connector.value
        _connector.value = null
        runCatching { old?.close() }
        _uiState.update { it.copy(isLoading = false, pendingTrust = null) }
    }

    fun connect(
        columns: Int,
        rows: Int,
        trustOnFirstUse: Boolean,
    ) {
        val host = _uiState.value.host.trim()
        val user = _uiState.value.user.trim()
        val port = _uiState.value.portText.trim().toIntOrNull() ?: 22

        if (host.isBlank() || user.isBlank()) {
            _uiState.update { it.copy(errorMessage = "请先填写 host / user") }
            return
        }
        if (port !in 1..65535) {
            _uiState.update { it.copy(errorMessage = "端口号不合法") }
            return
        }

        disconnect()
        connectJob =
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, errorMessage = null, pendingTrust = null) }

                try {
                    val secrets =
                        withContext(Dispatchers.IO) {
                            SshSecretsLoader.loadFromAgentsRoot(agentsRoot)
                        } ?: run {
                            _uiState.update { it.copy(isLoading = false, hasCredentials = false, errorMessage = "ssh-cli .env 未配置认证信息") }
                            return@launch
                        }

                    val conn =
                        withContext(Dispatchers.IO) {
                            val knownHostsFile = resolveWithinAgents(secrets.knownHostsPath.ifBlank { "workspace/ssh/known_hosts" })
                            val store = SshKnownHostsStore(file = knownHostsFile)
                            val password = secrets.password.takeIf { it.isNotBlank() }
                            val keyAbs =
                                secrets.privateKeyPath
                                    .trim()
                                    .takeIf { it.isNotBlank() }
                                    ?.let { resolveWithinAgents(it).absolutePath }
                            val passphrase = secrets.privateKeyPassphrase.takeIf { it.isNotBlank() }

                            SshTerminalConnector(
                                host = host,
                                port = port,
                                username = user,
                                password = password,
                                privateKeyAbsolutePath = keyAbs,
                                passphrase = passphrase,
                                knownHostsStore = store,
                                trustOnFirstUse = trustOnFirstUse,
                            ).also { it.connect(columns = columns, rows = rows) }
                        }

                    _connector.value = conn
                    _uiState.update { it.copy(isLoading = false, errorMessage = null, pendingTrust = null) }
                    withContext(Dispatchers.IO) {
                        lastStore.write(SshLastConnection(host = host, port = port, user = user))
                    }
                } catch (t: SshHostKeyUntrusted) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            pendingTrust = SshTrustPrompt(host = host, port = port, fingerprint = t.fingerprint),
                            errorMessage = "首次连接需要信任主机指纹",
                        )
                    }
                } catch (t: SshHostKeyMismatch) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "主机指纹不匹配：可能遭到中间人攻击或服务器重装",
                        )
                    }
                } catch (t: IOException) {
                    _uiState.update { it.copy(isLoading = false, errorMessage = (t.message ?: "连接失败")) }
                } catch (t: Throwable) {
                    _uiState.update { it.copy(isLoading = false, errorMessage = (t.message ?: "连接失败")) }
                }
            }
    }

    private fun resolveWithinAgents(relPath: String): File {
        val rel = relPath.replace('\\', '/').trim().trimStart('/').trim()
        if (rel.isBlank()) throw IOException("empty path")
        if (rel.split('/').any { it == ".." }) throw IOException("path traversal is not allowed")
        val target = File(agentsRoot, rel).canonicalFile
        if (!target.path.startsWith(agentsRoot.path)) throw IOException("path escapes .agents")
        return target
    }

    private fun refreshConfig() {
        viewModelScope.launch {
            val loaded =
                withContext(Dispatchers.IO) {
                    val secrets: SshSecrets? = SshSecretsLoader.loadFromAgentsRoot(agentsRoot)
                    val last = lastStore.readOrNull()
                    val env = DotEnv.load(secretsEnvFile)
                    val envHost =
                        env["SSH_HOST"]?.trim().orEmpty().ifBlank {
                            env["SSH_DEFAULT_HOST"]?.trim().orEmpty()
                        }
                    val envUser =
                        env["SSH_USER"]?.trim().orEmpty().ifBlank {
                            env["SSH_DEFAULT_USER"]?.trim().orEmpty()
                        }
                    val envPort =
                        env["SSH_PORT"]?.trim().orEmpty().ifBlank {
                            env["SSH_DEFAULT_PORT"]?.trim().orEmpty()
                        }
                    Triple(secrets, last, Triple(envHost, envPort, envUser))
                }

            val secrets = loaded.first
            val last = loaded.second
            val (envHost, envPort, envUser) = loaded.third

            val host =
                when {
                    envHost.isNotBlank() -> envHost
                    last != null -> last.host
                    else -> ""
                }
            val portText =
                when {
                    envPort.toIntOrNull() in 1..65535 -> envPort
                    last != null -> last.port.toString()
                    else -> "22"
                }
            val user =
                when {
                    envUser.isNotBlank() -> envUser
                    last != null -> last.user
                    else -> ""
                }

            val hasCreds = secrets != null
            _uiState.update { prev ->
                prev.copy(
                    hasCredentials = hasCreds,
                    host = host.ifBlank { prev.host },
                    portText = portText.ifBlank { prev.portText },
                    user = user.ifBlank { prev.user },
                    autoConnectRequested = hasCreds && host.isNotBlank() && user.isNotBlank(),
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }

    internal class Factory(
        private val appContext: Context,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SshViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return SshViewModel(appContext = appContext) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
        }
    }
}
