package com.lsl.kotlin_agent_app.ui.dashboard

import com.lsl.kotlin_agent_app.agent.AgentsDirEntry

data class FilesUiState(
    val cwd: String = ".agents",
    val entries: List<AgentsDirEntry> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val openFilePath: String? = null,
    val openFileText: String? = null,
)
