package com.lsl.kotlin_agent_app.ui.dashboard

import com.lsl.kotlin_agent_app.agent.AgentsDirEntry
import com.lsl.kotlin_agent_app.agent.AgentsDirEntryType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardQqMailRulesTest {

    @Test
    fun isQqMailDirAtWorkspaceRoot_matchesOnlyWorkspaceQqMailDir() {
        assertTrue(
            DashboardQqMailRules.isQqMailDirAtWorkspaceRoot(
                cwd = ".agents/workspace",
                entry = AgentsDirEntry(name = "qqmail", type = AgentsDirEntryType.Dir),
            ),
        )
        assertFalse(
            DashboardQqMailRules.isQqMailDirAtWorkspaceRoot(
                cwd = ".agents",
                entry = AgentsDirEntry(name = "qqmail", type = AgentsDirEntryType.Dir),
            ),
        )
        assertFalse(
            DashboardQqMailRules.isQqMailDirAtWorkspaceRoot(
                cwd = ".agents/workspace",
                entry = AgentsDirEntry(name = "qqmail", type = AgentsDirEntryType.File),
            ),
        )
        assertFalse(
            DashboardQqMailRules.isQqMailDirAtWorkspaceRoot(
                cwd = ".agents/workspace/qqmail",
                entry = AgentsDirEntry(name = "inbox", type = AgentsDirEntryType.Dir),
            ),
        )
    }

    @Test
    fun qqmailDirLongClickActions_containsEnterDir() {
        val actions = DashboardQqMailRules.qqmailDirLongClickActions().toList()
        assertTrue(actions.isNotEmpty())
        assertTrue(actions.contains("进入目录"))
        assertTrue(actions.contains("打开邮箱"))
    }
}

