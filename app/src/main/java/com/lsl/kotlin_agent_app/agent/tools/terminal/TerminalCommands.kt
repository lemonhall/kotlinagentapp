package com.lsl.kotlin_agent_app.agent.tools.terminal

import android.content.Context
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.HelloCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.cal.CalCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.git.GitCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.tar.TarCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.zip.ZipCommand

internal object TerminalCommands {
    fun defaultRegistry(appContext: Context): TerminalCommandRegistry {
        return TerminalCommandRegistry(
            listOf(
                HelloCommand,
                CalCommand(appContext),
                GitCommand(appContext),
                ZipCommand(appContext),
                TarCommand(appContext),
            ),
        )
    }
}
