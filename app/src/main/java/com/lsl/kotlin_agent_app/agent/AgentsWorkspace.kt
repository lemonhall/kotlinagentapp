package com.lsl.kotlin_agent_app.agent

import android.content.Context
import com.lsl.kotlin_agent_app.BuildConfig
import java.io.File

data class AgentsDirEntry(
    val name: String,
    val type: AgentsDirEntryType,
    val displayName: String? = null,
    val subtitle: String? = null,
    val sortKey: Long? = null,
)

enum class AgentsDirEntryType {
    File,
    Dir,
}

class AgentsWorkspace(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val filesRoot: File = appContext.filesDir
    private val agentsRoot: File = File(filesRoot, ".agents")

    fun ensureInitialized() {
        mkdirsIfMissing(".agents")
        mkdirsIfMissing(".agents/skills")
        mkdirsIfMissing(".agents/sessions")

        // Best-effort: missing asset must not break the rest.
        // In Debug builds, keep bundled skills synced to avoid stale/partial copies across app reinstalls.
        val overwrite = BuildConfig.DEBUG
        installBundledSkillDir(name = "hello-world", assetDir = "builtin_skills/hello-world", overwrite = overwrite)
        installBundledSkillDir(name = "skill-creator", assetDir = "builtin_skills/skill-creator", overwrite = overwrite)
        installBundledSkillDir(name = "brainstorming", assetDir = "builtin_skills/brainstorming", overwrite = overwrite)
        installBundledSkillDir(name = "find-skills", assetDir = "builtin_skills/find-skills", overwrite = overwrite)
        installBundledSkillDir(name = "deep-research", assetDir = "builtin_skills/deep-research", overwrite = overwrite)

        installBundledFile(targetPath = ".agents/sessions/README.md", assetPath = "builtin_sessions/README.md", overwrite = overwrite)
    }

    fun listDir(path: String): List<AgentsDirEntry> {
        val dir = resolveAgentsPath(path)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val children = dir.listFiles().orEmpty()
        return children
            .map { child ->
                AgentsDirEntry(
                    name = child.name,
                    type = if (child.isDirectory) AgentsDirEntryType.Dir else AgentsDirEntryType.File,
                )
            }
            .sortedWith(compareBy<AgentsDirEntry>({ it.type != AgentsDirEntryType.Dir }, { it.name.lowercase() }))
    }

    fun exists(path: String): Boolean {
        val f = resolveAgentsPath(path)
        return f.exists()
    }

    fun lastModified(path: String): Long? {
        val f = resolveAgentsPath(path)
        if (!f.exists()) return null
        return f.lastModified().takeIf { it > 0L }
    }

    fun readTextFile(path: String, maxBytes: Long = 256 * 1024): String {
        val f = resolveAgentsPath(path)
        if (!f.exists() || !f.isFile) error("Not a file: $path")
        val len = f.length()
        if (len > maxBytes) error("File too large (${len}B), max ${maxBytes}B")
        return f.readText(Charsets.UTF_8)
    }

    fun writeTextFile(path: String, content: String) {
        val f = resolveAgentsPath(path)
        val parent = f.parentFile ?: error("Invalid path: $path")
        if (!parent.exists()) parent.mkdirs()
        f.writeText(content, Charsets.UTF_8)
    }

    fun mkdir(path: String) {
        val f = resolveAgentsPath(path)
        if (!f.exists()) f.mkdirs()
    }

    fun deletePath(path: String, recursive: Boolean) {
        val f = resolveAgentsPath(path)
        if (!f.exists()) return
        if (f.isFile) {
            f.delete()
            return
        }
        if (!recursive) error("Refusing to delete directory without recursive=true")
        f.deleteRecursively()
    }

    fun parentDir(path: String): String? {
        val normalized = normalizeAgentsPath(path)
        if (normalized == ".agents") return null
        val idx = normalized.lastIndexOf('/')
        return if (idx <= 0) ".agents" else normalized.substring(0, idx)
    }

    fun joinPath(dir: String, name: String): String {
        val d = normalizeAgentsPath(dir)
        val n = name.replace('\\', '/').trim().trim('/')
        if (n.isEmpty()) return d
        return if (d == ".agents") "$d/$n" else "$d/$n"
    }

    private fun mkdirsIfMissing(path: String) {
        val f = resolveAgentsPath(path)
        if (!f.exists()) f.mkdirs()
    }

    private fun installBundledSkillDir(
        name: String,
        assetDir: String,
        overwrite: Boolean,
    ) {
        val skillDir = ".agents/skills/$name"
        mkdirsIfMissing(skillDir)
        installBundledDir(targetDir = skillDir, assetDir = assetDir, overwrite = overwrite)
    }

    private fun installBundledFile(
        targetPath: String,
        assetPath: String,
        overwrite: Boolean,
    ) {
        val target = resolveAgentsPath(targetPath)
        if (!overwrite && target.exists() && target.isFile) return
        try {
            val parent = target.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            appContext.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (_: Throwable) {
            // best-effort
        }
    }

    private fun installBundledDir(
        targetDir: String,
        assetDir: String,
        overwrite: Boolean,
    ) {
        try {
            copyAssetDirRecursive(assetDir = assetDir.trim('/'), targetDir = targetDir.trimEnd('/'), overwrite = overwrite)
        } catch (_: Throwable) {
            // best-effort
        }
    }

    private fun copyAssetDirRecursive(
        assetDir: String,
        targetDir: String,
        overwrite: Boolean,
    ) {
        val items = appContext.assets.list(assetDir).orEmpty()
        for (name in items) {
            if (name.isBlank()) continue
            val childAsset = "$assetDir/$name"
            val childTarget = "$targetDir/$name"
            val children = appContext.assets.list(childAsset).orEmpty()
            if (children.isNotEmpty()) {
                mkdirsIfMissing(childTarget)
                copyAssetDirRecursive(assetDir = childAsset, targetDir = childTarget, overwrite = overwrite)
            } else {
                val isFile =
                    try {
                        appContext.assets.open(childAsset).use { }
                        true
                    } catch (_: Throwable) {
                        false
                    }
                if (isFile) {
                    installBundledFile(targetPath = childTarget, assetPath = childAsset, overwrite = overwrite)
                } else {
                    // Some asset packs contain empty directories; preserve them best-effort.
                    mkdirsIfMissing(childTarget)
                }
            }
        }
    }

    private fun normalizeAgentsPath(path: String): String {
        val p0 = path.replace('\\', '/').trim()
        val p1 = p0.trimStart('/').trimEnd('/')
        val p = if (p1.isEmpty()) ".agents" else p1
        if (p != ".agents" && !p.startsWith(".agents/")) error("Path must be within .agents: $path")
        if (p.split('/').any { it == ".." }) error("Path traversal is not allowed: $path")
        return p
    }

    private fun resolveAgentsPath(path: String): File {
        val normalized = normalizeAgentsPath(path)
        val agentsCanonical = agentsRoot.canonicalFile
        val target = File(filesRoot, normalized).canonicalFile
        if (!target.path.startsWith(agentsCanonical.path)) error("Path escapes .agents root: $path")
        return target
    }
}
