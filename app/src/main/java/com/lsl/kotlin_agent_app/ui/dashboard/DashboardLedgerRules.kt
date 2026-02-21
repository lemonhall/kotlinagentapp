package com.lsl.kotlin_agent_app.ui.dashboard

import com.lsl.kotlin_agent_app.agent.AgentsDirEntry
import com.lsl.kotlin_agent_app.agent.AgentsDirEntryType

object DashboardLedgerRules {

    fun isLedgerDirAtWorkspaceRoot(
        cwd: String,
        entry: AgentsDirEntry,
    ): Boolean {
        if (entry.type != AgentsDirEntryType.Dir) return false
        val normalizedCwd = cwd.replace('\\', '/').trim().trimEnd('/')
        if (normalizedCwd != ".agents/workspace") return false
        return entry.name.trim() == "ledger"
    }

    fun ledgerDirLongClickActions(): Array<String> {
        return arrayOf("进入目录", "打开账本", "剪切", "复制", "重命名", "删除", "复制路径")
    }
}
