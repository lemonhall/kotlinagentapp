package com.lsl.kotlin_agent_app.ui.dashboard

import com.lsl.kotlin_agent_app.agent.AgentsDirEntry
import com.lsl.kotlin_agent_app.agent.AgentsDirEntryType

object DashboardInstantTranslationRules {
    fun isInstantTranslationDirAtWorkspaceRoot(
        cwd: String,
        entry: AgentsDirEntry,
    ): Boolean {
        if (entry.type != AgentsDirEntryType.Dir) return false
        val normalizedCwd = cwd.replace('\\', '/').trim().trimEnd('/')
        if (normalizedCwd != ".agents/workspace") return false
        return entry.name.trim() == "instant_translation"
    }

    fun instantTranslationDirLongClickActions(): Array<String> {
        return arrayOf(
            "\u8fdb\u5165\u76ee\u5f55",
            "\u6253\u5f00\u56de\u6eaf\u76ee\u5f55",
            "\u6253\u5f00\u5373\u65f6\u7ffb\u8bd1",
            "\u526a\u5207",
            "\u590d\u5236",
            "\u91cd\u547d\u540d",
            "\u5220\u9664",
            "\u590d\u5236\u8def\u5f84",
        )
    }
}
