package com.lsl.kotlin_agent_app.agent.tools.terminal

import android.content.Context
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.HelloCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.cal.CalCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.git.GitCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.irc.IrcCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.ledger.LedgerCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.qqmail.QqMailCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.rss.RssCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.ssh.SshCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.stock.StockCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.tar.TarCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.zip.ZipCommand

internal object TerminalCommands {
    fun defaultRegistry(appContext: Context): TerminalCommandRegistry {
        return TerminalCommandRegistry(
            listOf(
                HelloCommand,
                CalCommand(appContext),
                GitCommand(appContext),
                QqMailCommand(appContext),
                LedgerCommand(appContext),
                ZipCommand(appContext),
                TarCommand(appContext),
                StockCommand(appContext),
                RssCommand(appContext),
                SshCommand(appContext),
                IrcCommand(appContext),
            ),
        )
    }
}
