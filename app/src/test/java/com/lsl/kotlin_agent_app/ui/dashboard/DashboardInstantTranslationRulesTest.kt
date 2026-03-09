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
    fun instantTranslationDirLongClickActions_containsArchiveAndOpenActions() {
        val actions = DashboardInstantTranslationRules.instantTranslationDirLongClickActions().toList()
        assertTrue(actions.contains("\u8fdb\u5165\u76ee\u5f55"))
        assertTrue(actions.contains("\u6253\u5f00\u56de\u6eaf\u76ee\u5f55"))
        assertTrue(actions.contains("\u6253\u5f00\u5373\u65f6\u7ffb\u8bd1"))
    }
}
