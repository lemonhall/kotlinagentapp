package com.lsl.kotlin_agent_app.agent.tools.terminal

import kotlinx.serialization.json.JsonElement

internal data class TerminalArtifact(
    val path: String,
    val mime: String,
    val description: String,
)

internal data class TerminalCommandOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String = "",
    val result: JsonElement? = null,
    val artifacts: List<TerminalArtifact> = emptyList(),
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

internal interface TerminalCommand {
    val name: String
    val description: String

    suspend fun run(
        argv: List<String>,
        stdin: String?,
    ): TerminalCommandOutput
}

internal class TerminalCommandRegistry(
    commands: Iterable<TerminalCommand>,
) {
    private val byName = linkedMapOf<String, TerminalCommand>()

    init {
        for (c in commands) register(c)
    }

    fun register(command: TerminalCommand) {
        val key = command.name.trim().lowercase()
        require(key.isNotEmpty()) { "command name must be non-empty" }
        byName[key] = command
    }

    fun get(name: String): TerminalCommand? = byName[name.trim().lowercase()]
}

