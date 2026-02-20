package com.lsl.kotlin_agent_app.smb_media

import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.NasSmbMountConfig

object SmbMediaRemotePath {

    fun toRemotePath(
        mount: NasSmbMountConfig,
        relPath: String,
    ): String {
        val rel = normalizeRel(relPath)
        val base = mount.remoteDir.trim().trim('/').ifBlank { "" }
        return when {
            base.isBlank() && rel.isBlank() -> ""
            base.isBlank() -> rel
            rel.isBlank() -> base
            else -> "$base/$rel"
        }
    }

    fun toSharePath(remotePath: String): String {
        return remotePath.trim().replace('\\', '/').trim('/').replace('/', '\\')
    }

    private fun normalizeRel(rel: String): String {
        val raw = rel.replace('\\', '/').trim().trimStart('/').trimEnd('/')
        if (raw.isBlank()) return ""
        val segs = raw.split('/').filter { it.isNotBlank() }
        if (segs.any { it == "." || it == ".." }) throw IllegalArgumentException("Path traversal is not allowed")
        return segs.joinToString("/")
    }
}

