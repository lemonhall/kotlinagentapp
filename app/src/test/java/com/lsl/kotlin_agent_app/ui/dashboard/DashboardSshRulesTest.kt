package com.lsl.kotlin_agent_app.ui.dashboard

import com.lsl.kotlin_agent_app.agent.AgentsDirEntry
import com.lsl.kotlin_agent_app.agent.AgentsDirEntryType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardSshRulesTest {

    @Test
    fun isSshDirAtWorkspaceRoot_matchesOnlyWorkspaceSshDir() {
        assertTrue(
            DashboardSshRules.isSshDirAtWorkspaceRoot(
                cwd = ".agents/workspace",
                entry = AgentsDirEntry(name = "ssh", type = AgentsDirEntryType.Dir),
            ),
        )
        assertFalse(
            DashboardSshRules.isSshDirAtWorkspaceRoot(
                cwd = ".agents",
                entry = AgentsDirEntry(name = "ssh", type = AgentsDirEntryType.Dir),
            ),
        )
        assertFalse(
            DashboardSshRules.isSshDirAtWorkspaceRoot(
                cwd = ".agents/workspace",
                entry = AgentsDirEntry(name = "ssh", type = AgentsDirEntryType.File),
            ),
        )
        assertFalse(
            DashboardSshRules.isSshDirAtWorkspaceRoot(
                cwd = ".agents/workspace/ssh",
                entry = AgentsDirEntry(name = "sessions", type = AgentsDirEntryType.Dir),
            ),
        )
    }

    @Test
    fun sshDirLongClickActions_containsEnterDir() {
        val actions = DashboardSshRules.sshDirLongClickActions().toList()
        assertTrue(actions.isNotEmpty())
        assertTrue(actions.contains("进入目录"))
        assertTrue(actions.contains("打开 SSH"))
    }
}

