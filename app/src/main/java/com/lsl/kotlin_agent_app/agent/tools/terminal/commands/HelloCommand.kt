package com.lsl.kotlin_agent_app.agent.tools.terminal.commands

import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommandOutput
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

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

