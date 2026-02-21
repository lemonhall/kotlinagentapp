package com.lsl.kotlin_agent_app.ui.dashboard

import com.lsl.kotlin_agent_app.agent.AgentsDirEntry
import com.lsl.kotlin_agent_app.agent.AgentsDirEntryType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardLedgerRulesTest {

    @Test
    fun isLedgerDirAtWorkspaceRoot_matchesOnlyWorkspaceLedgerDir() {
        assertTrue(
            DashboardLedgerRules.isLedgerDirAtWorkspaceRoot(
                cwd = ".agents/workspace",
                entry = AgentsDirEntry(name = "ledger", type = AgentsDirEntryType.Dir),
            ),
        )
        assertFalse(
            DashboardLedgerRules.isLedgerDirAtWorkspaceRoot(
                cwd = ".agents",
                entry = AgentsDirEntry(name = "ledger", type = AgentsDirEntryType.Dir),
            ),
        )
        assertFalse(
            DashboardLedgerRules.isLedgerDirAtWorkspaceRoot(
                cwd = ".agents/workspace",
                entry = AgentsDirEntry(name = "ledger", type = AgentsDirEntryType.File),
            ),
        )
        assertFalse(
            DashboardLedgerRules.isLedgerDirAtWorkspaceRoot(
                cwd = ".agents/workspace/ledger",
                entry = AgentsDirEntry(name = "transactions.jsonl", type = AgentsDirEntryType.File),
            ),
        )
    }

    @Test
    fun ledgerDirLongClickActions_containsEnterDir() {
        val actions = DashboardLedgerRules.ledgerDirLongClickActions().toList()
        assertTrue(actions.isNotEmpty())
        assertTrue(actions.contains("进入目录"))
        assertTrue(actions.contains("打开账本"))
    }
}

