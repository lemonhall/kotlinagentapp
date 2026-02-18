package com.lsl.kotlin_agent_app.agent.tools.terminal

import android.content.Context
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.HelloCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.git.GitCommand

internal object TerminalCommands {
    fun defaultRegistry(appContext: Context): TerminalCommandRegistry {
        return TerminalCommandRegistry(
            listOf(
                HelloCommand,
                GitCommand(appContext),
            ),
        )
    }
}

