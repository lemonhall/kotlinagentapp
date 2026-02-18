package com.lsl.kotlin_agent_app.ui.terminal

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalExecTool
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okio.FileSystem
import okio.Path.Companion.toPath
import me.lemonhall.openagentic.sdk.tools.ToolContext
import me.lemonhall.openagentic.sdk.tools.ToolOutput

data class TerminalUiState(
    val runs: List<TerminalRunResult> = emptyList(),
    val error: String? = null,
    val isRunning: Boolean = false,
)

class TerminalViewModel(
    context: Context,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val workspace = AgentsWorkspace(appContext)
    private val tool = TerminalExecTool(appContext = appContext)
    private val repo = TerminalRunsRepository(appContext)

    private val toolContext: ToolContext =
        ToolContext(
            fileSystem = FileSystem.SYSTEM,
            cwd = File(appContext.filesDir, ".agents").absolutePath.replace('\\', '/').toPath(),
        )

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            workspace.ensureInitialized()
            val runs = repo.listRecentRuns()
            _state.value = _state.value.copy(runs = runs, error = null)
        }
    }

    fun runCommand(
        command: String,
        stdin: String?,
        onCompleted: (TerminalRunResult) -> Unit,
    ) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(isRunning = true, error = null)
            workspace.ensureInitialized()

            val input =
                buildJsonObject {
                    put("command", JsonPrimitive(cmd))
                    val s = stdin?.takeIf { it.isNotBlank() }
                    if (s != null) put("stdin", JsonPrimitive(s))
                }

            val out = tool.run(input, toolContext)
            val summary =
                when (out) {
                    is ToolOutput.Json -> parseToolOutput(out.value?.jsonObject, command = cmd)
                    else -> TerminalRunSummary(
                        runId = "",
                        timestampMs = System.currentTimeMillis(),
                        command = cmd,
                        exitCode = 2,
                        durationMs = 0,
                        stdout = "",
                        stderr = "unexpected tool output",
                        errorCode = "ToolOutputUnexpected",
                        errorMessage = "unexpected tool output",
                    )
                }

            val newRuns = repo.listRecentRuns()
            _state.value = _state.value.copy(runs = newRuns, isRunning = false, error = null)
            withContext(Dispatchers.Main) {
                val full = newRuns.firstOrNull { it.summary.runId == summary.runId }
                onCompleted(full ?: TerminalRunResult(summary = summary))
            }
        }
    }

    private fun parseToolOutput(
        obj: JsonObject?,
        command: String,
    ): TerminalRunSummary {
        val o = obj ?: return TerminalRunSummary(
            runId = "",
            timestampMs = System.currentTimeMillis(),
            command = command,
            exitCode = 2,
            durationMs = 0,
            stdout = "",
            stderr = "null tool output",
            errorCode = "ToolOutputNull",
            errorMessage = "null tool output",
        )

        fun str(key: String): String = (o[key] as? JsonPrimitive)?.content ?: ""
        fun int(key: String): Int = (o[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        fun long(key: String): Long = (o[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        fun optStr(key: String): String? = (o[key] as? JsonPrimitive)?.content

        return TerminalRunSummary(
            runId = str("run_id"),
            timestampMs = System.currentTimeMillis(),
            command = command,
            exitCode = int("exit_code"),
            durationMs = long("duration_ms"),
            stdout = str("stdout"),
            stderr = str("stderr"),
            errorCode = optStr("error_code"),
            errorMessage = optStr("error_message"),
        )
    }

    class Factory(
        private val context: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TerminalViewModel(context = context) as T
        }
    }
}
