package com.lsl.kotlin_agent_app.ui.qqmail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.agent.tools.mail.DotEnv
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.qqmail.QqMailCommand
import com.lsl.kotlin_agent_app.ui.env_editor.EnvEditorActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class QqMailUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val hasCredentials: Boolean = false,
    val selectedMailbox: QqMailMailbox = QqMailMailbox.Inbox,
    val inbox: List<QqMailLocalItem> = emptyList(),
    val sent: List<QqMailLocalItem> = emptyList(),
    val openMessage: QqMailLocalMessage? = null,
)

internal class QqMailViewModel(
    appContext: Context,
) : ViewModel() {
    private val ctx = appContext.applicationContext
    private val ws = AgentsWorkspace(ctx)
    private val agentsRoot = File(ctx.filesDir, ".agents").canonicalFile
    private val cmd = QqMailCommand(ctx)

    private val _uiState = MutableStateFlow(QqMailUiState())
    val uiState: StateFlow<QqMailUiState> = _uiState

    fun setMailbox(mb: QqMailMailbox) {
        _uiState.update { it.copy(selectedMailbox = mb) }
    }

    fun closeMessage() {
        _uiState.update { it.copy(openMessage = null) }
    }

    fun openCredentialsEditor() {
        val intent =
            EnvEditorActivity.intentOf(
                context = ctx,
                agentsPath = ".agents/skills/qqmail-cli/secrets/.env",
                displayName = "qqmail .env",
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    fun refreshLocal() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                withContext(Dispatchers.IO) {
                    ws.ensureInitialized()
                    ws.mkdir(".agents/workspace/qqmail")
                    ws.mkdir(".agents/workspace/qqmail/inbox")
                    ws.mkdir(".agents/workspace/qqmail/sent")
                }

                val hasCreds = hasCredentials()
                val inbox = withContext(Dispatchers.IO) { listMailbox(QqMailMailbox.Inbox) }
                val sent = withContext(Dispatchers.IO) { listMailbox(QqMailMailbox.Sent) }
                _uiState.update { it.copy(isLoading = false, hasCredentials = hasCreds, inbox = inbox, sent = sent) }
            } catch (t: Throwable) {
                _uiState.update { it.copy(isLoading = false, errorMessage = t.message ?: "QQMail refresh error") }
            }
        }
    }

    fun openMessage(item: QqMailLocalItem) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val msg =
                    withContext(Dispatchers.IO) {
                        val f = ws.toFile(item.agentsPath)
                        val text = f.readText(Charsets.UTF_8)
                        val parsed = QqMailMarkdown.parse(text)
                        QqMailLocalMessage(item = item, frontMatter = parsed.frontMatter, bodyMarkdown = parsed.bodyMarkdown)
                    }
                _uiState.update { it.copy(isLoading = false, openMessage = msg) }
            } catch (t: Throwable) {
                _uiState.update { it.copy(isLoading = false, errorMessage = t.message ?: "open message error") }
            }
        }
    }

    fun fetch(
        folder: String,
        limit: Int,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val argv = listOf("qqmail", "fetch", "--folder", folder, "--limit", limit.toString())
                val out =
                    withContext(Dispatchers.IO) {
                        cmd.run(argv = argv, stdin = null)
                    }
                if (out.exitCode != 0) {
                    throw IllegalStateException(out.errorMessage ?: out.stderr.ifBlank { "qqmail fetch failed" })
                }
                refreshLocal()
            } catch (t: Throwable) {
                _uiState.update { it.copy(isLoading = false, errorMessage = t.message ?: "qqmail fetch error") }
            }
        }
    }

    fun send(
        to: String,
        subject: String,
        body: String,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val argv =
                    listOf(
                        "qqmail",
                        "send",
                        "--to",
                        to,
                        "--subject",
                        subject,
                        "--body-stdin",
                        "--confirm",
                    )
                val out =
                    withContext(Dispatchers.IO) {
                        cmd.run(argv = argv, stdin = body)
                    }
                if (out.exitCode != 0) {
                    throw IllegalStateException(out.errorMessage ?: out.stderr.ifBlank { "qqmail send failed" })
                }
                refreshLocal()
            } catch (t: Throwable) {
                _uiState.update { it.copy(isLoading = false, errorMessage = t.message ?: "qqmail send error") }
            }
        }
    }

    private suspend fun hasCredentials(): Boolean {
        return withContext(Dispatchers.IO) {
            val envFile = File(agentsRoot, "skills/qqmail-cli/secrets/.env")
            if (!envFile.exists() || !envFile.isFile) return@withContext false
            val map = DotEnv.load(envFile)
            map["EMAIL_ADDRESS"].orEmpty().trim().isNotBlank() && map["EMAIL_PASSWORD"].orEmpty().trim().isNotBlank()
        }
    }

    private fun listMailbox(mailbox: QqMailMailbox): List<QqMailLocalItem> {
        val dirRel =
            when (mailbox) {
                QqMailMailbox.Inbox -> "workspace/qqmail/inbox"
                QqMailMailbox.Sent -> "workspace/qqmail/sent"
            }
        val dir = File(agentsRoot, dirRel)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        val files = dir.listFiles()?.filter { it.isFile && it.extension.lowercase() == "md" }.orEmpty()
        return files
            .mapNotNull { f ->
                runCatching {
                    val text = f.readText(Charsets.UTF_8)
                    val parsed = QqMailMarkdown.parse(text)
                    val fm = parsed.frontMatter
                    val subject = fm["subject"].orEmpty().ifBlank { f.nameWithoutExtension }
                    val peer =
                        when (mailbox) {
                            QqMailMailbox.Inbox -> fm["from"].orEmpty().ifBlank { fm["to"].orEmpty() }
                            QqMailMailbox.Sent -> fm["to"].orEmpty().ifBlank { fm["from"].orEmpty() }
                        }
                    val bodyPreview =
                        parsed.bodyMarkdown
                            .trim()
                            .lineSequence()
                            .firstOrNull { it.isNotBlank() }
                            .orEmpty()
                            .take(80)
                    val agentsPath = ".agents/" + relPath(agentsRoot, f)
                    QqMailLocalItem(
                        agentsPath = agentsPath,
                        mailbox = mailbox,
                        subject = subject,
                        peer = peer,
                        dateText = fmt.format(java.util.Date(f.lastModified())),
                        preview = bodyPreview,
                        sortKey = f.lastModified(),
                    )
                }.getOrNull()
            }
            .sortedByDescending { it.sortKey }
    }

    private fun relPath(
        root: File,
        file: File,
    ): String {
        val rp = file.canonicalFile.absolutePath.removePrefix(root.canonicalFile.absolutePath).trimStart('\\', '/')
        return rp.replace('\\', '/')
    }

    internal class Factory(
        private val appContext: Context,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(QqMailViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return QqMailViewModel(appContext = appContext) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
        }
    }
}
