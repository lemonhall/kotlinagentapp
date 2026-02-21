package com.lsl.kotlin_agent_app.ui.dashboard

import com.lsl.kotlin_agent_app.agent.AgentsDirEntry
import com.lsl.kotlin_agent_app.agent.AgentsDirEntryType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardIrcRulesTest {

    @Test
    fun isIrcDirAtWorkspaceRoot_matchesOnlyWorkspaceIrcDir() {
        assertTrue(
            DashboardIrcRules.isIrcDirAtWorkspaceRoot(
                cwd = ".agents/workspace",
                entry = AgentsDirEntry(name = "irc", type = AgentsDirEntryType.Dir),
            ),
        )
        assertFalse(
            DashboardIrcRules.isIrcDirAtWorkspaceRoot(
                cwd = ".agents",
                entry = AgentsDirEntry(name = "irc", type = AgentsDirEntryType.Dir),
            ),
        )
        assertFalse(
            DashboardIrcRules.isIrcDirAtWorkspaceRoot(
                cwd = ".agents/workspace",
                entry = AgentsDirEntry(name = "irc", type = AgentsDirEntryType.File),
            ),
        )
        assertFalse(
            DashboardIrcRules.isIrcDirAtWorkspaceRoot(
                cwd = ".agents/workspace/irc",
                entry = AgentsDirEntry(name = "sessions", type = AgentsDirEntryType.Dir),
            ),
        )
    }

    @Test
    fun ircDirLongClickActions_containsEnterDir() {
        val actions = DashboardIrcRules.ircDirLongClickActions().toList()
        assertTrue(actions.isNotEmpty())
        assertTrue(actions.contains("进入目录"))
        assertTrue(actions.contains("打开 IRC"))
    }
}

