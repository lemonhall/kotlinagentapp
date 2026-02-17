package com.lsl.kotlin_agent_app.agent.tools.terminal

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

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

internal object HelloCommand : TerminalCommand {
    override val name: String = "hello"
    override val description: String = "Print HELLO ASCII art and a lemonhall signature."

    override suspend fun run(
        argv: List<String>,
        stdin: String?,
    ): TerminalCommandOutput {
        val art =
            """
            H   H  EEEEE  L      L       OOO
            H   H  E      L      L      O   O
            HHHHH  EEEE   L      L      O   O
            H   H  E      L      L      O   O
            H   H  EEEEE  LLLLL  LLLLL   OOO
            """.trimIndent()

        val stdout =
            buildString {
                appendLine("HELLO")
                appendLine()
                appendLine(art)
                appendLine()
                appendLine("lemonhall")
            }

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive(name))
                put("signature", JsonPrimitive("lemonhall"))
                put("stdin_used", JsonPrimitive(!stdin.isNullOrEmpty()))
                put("argv", JsonPrimitive(argv.joinToString(" ")))
                put("note", JsonNull)
            }

        return TerminalCommandOutput(exitCode = 0, stdout = stdout, stderr = "", result = result)
    }
}
