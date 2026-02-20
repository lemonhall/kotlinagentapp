package com.lsl.kotlin_agent_app.smb_media

object SmbMediaAgentsPath {

    data class NasSmbFilePath(
        val mountName: String,
        val relPath: String,
    )

    fun parseNasSmbFile(path: String): NasSmbFilePath? {
        val normalized =
            path
                .replace('\\', '/')
                .trim()
                .trimEnd('/')

        if (!normalized.startsWith(".agents/nas_smb/")) return null

        val segs = normalized.split('/').filter { it.isNotBlank() }
        if (segs.size < 4) return null
        val mountName = segs[2]
        if (mountName == "secrets") return null
        if (segs.last() == ".mount.json") return null
        val rel = segs.subList(3, segs.size).joinToString("/")
        if (rel.isBlank()) return null
        return NasSmbFilePath(mountName = mountName, relPath = rel)
    }
}

