package com.lsl.kotlin_agent_app.ui.dashboard

import com.lsl.kotlin_agent_app.agent.AgentsDirEntry
import com.lsl.kotlin_agent_app.agent.AgentsDirEntryType

object DashboardSshRules {

    fun isSshDirAtWorkspaceRoot(
        cwd: String,
        entry: AgentsDirEntry,
    ): Boolean {
        if (entry.type != AgentsDirEntryType.Dir) return false
        val normalizedCwd = cwd.replace('\\', '/').trim().trimEnd('/')
        if (normalizedCwd != ".agents/workspace") return false
        return entry.name.trim() == "ssh"
    }

    fun sshDirLongClickActions(): Array<String> {
        return arrayOf("进入目录", "打开 SSH", "剪切", "复制", "重命名", "删除", "复制路径")
    }
}

