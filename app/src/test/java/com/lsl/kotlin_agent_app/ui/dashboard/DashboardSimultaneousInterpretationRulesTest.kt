package com.lsl.kotlin_agent_app.ui.dashboard

import com.lsl.kotlin_agent_app.agent.AgentsDirEntry
import com.lsl.kotlin_agent_app.agent.AgentsDirEntryType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardSimultaneousInterpretationRulesTest {
    @Test
    fun isSimultaneousInterpretationDirAtWorkspaceRoot_matchesOnlyWorkspaceDir() {
        assertTrue(
            DashboardSimultaneousInterpretationRules.isSimultaneousInterpretationDirAtWorkspaceRoot(
                cwd = ".agents/workspace",
                entry = AgentsDirEntry(name = "simultaneous_interpretation", type = AgentsDirEntryType.Dir),
            ),
        )
        assertFalse(
            DashboardSimultaneousInterpretationRules.isSimultaneousInterpretationDirAtWorkspaceRoot(
                cwd = ".agents",
                entry = AgentsDirEntry(name = "simultaneous_interpretation", type = AgentsDirEntryType.Dir),
            ),
        )
        assertFalse(
            DashboardSimultaneousInterpretationRules.isSimultaneousInterpretationDirAtWorkspaceRoot(
                cwd = ".agents/workspace",
                entry = AgentsDirEntry(name = "simultaneous_interpretation", type = AgentsDirEntryType.File),
            ),
        )
    }

    @Test
    fun simultaneousInterpretationDirLongClickActions_containsArchiveAndOpenActions() {
        val actions = DashboardSimultaneousInterpretationRules.simultaneousInterpretationDirLongClickActions().toList()
        assertTrue(actions.contains("进入目录"))
        assertTrue(actions.contains("打开回溯目录"))
        assertTrue(actions.contains("打开同声传译"))
    }
}