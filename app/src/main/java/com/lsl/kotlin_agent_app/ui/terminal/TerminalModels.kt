package com.lsl.kotlin_agent_app.ui.terminal

data class TerminalRunSummary(
    val runId: String,
    val timestampMs: Long,
    val command: String,
    val exitCode: Int,
    val durationMs: Long,
    val stdout: String,
    val stderr: String,
    val errorCode: String?,
    val errorMessage: String?,
)

data class TerminalRunResult(
    val summary: TerminalRunSummary,
    val artifacts: List<TerminalRunArtifact> = emptyList(),
)

data class TerminalRunArtifact(
    val path: String,
    val mime: String,
    val description: String,
)

