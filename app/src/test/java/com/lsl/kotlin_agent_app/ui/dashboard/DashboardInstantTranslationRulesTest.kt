package com.lsl.kotlin_agent_app.ui.dashboard

import com.lsl.kotlin_agent_app.agent.AgentsDirEntry
import com.lsl.kotlin_agent_app.agent.AgentsDirEntryType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardInstantTranslationRulesTest {
    @Test
    fun isInstantTranslationDirAtWorkspaceRoot_matchesOnlyWorkspaceDir() {
        assertTrue(
            DashboardInstantTranslationRules.isInstantTranslationDirAtWorkspaceRoot(
                cwd = ".agents/workspace",
                entry = AgentsDirEntry(name = "instant_translation", type = AgentsDirEntryType.Dir),
            ),
        )
        assertFalse(
            DashboardInstantTranslationRules.isInstantTranslationDirAtWorkspaceRoot(
                cwd = ".agents",
                entry = AgentsDirEntry(name = "instant_translation", type = AgentsDirEntryType.Dir),
            ),
        )
        assertFalse(
            DashboardInstantTranslationRules.isInstantTranslationDirAtWorkspaceRoot(
                cwd = ".agents/workspace",
                entry = AgentsDirEntry(name = "instant_translation", type = AgentsDirEntryType.File),
            ),
        )
    }

    @Test
    fun instantTranslationDirLongClickActions_containsOpenAction() {
        val actions = DashboardInstantTranslationRules.instantTranslationDirLongClickActions().toList()
        assertTrue(actions.contains("进入目录"))
        assertTrue(actions.contains("打开即时翻译"))
    }
}
