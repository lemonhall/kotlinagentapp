package com.lsl.kotlin_agent_app.agent

import android.content.Context
import java.io.File

data class AgentsDirEntry(
    val name: String,
    val type: AgentsDirEntryType,
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
        installBundledSkillIfMissing("hello-world", "builtin_skills/hello-world/SKILL.md")
        installBundledSkillIfMissing("skill-creator", "builtin_skills/skill-creator/SKILL.md")
        installBundledSkillIfMissing("brainstorming", "builtin_skills/brainstorming/SKILL.md")
        installBundledSkillIfMissing("find-skills", "builtin_skills/find-skills/SKILL.md")
        installBundledSkillIfMissing("deep-research", "builtin_skills/deep-research/SKILL.md")

        installBundledFileIfMissing(".agents/sessions/README.md", "builtin_sessions/README.md")
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

    private fun installBundledSkillIfMissing(name: String, assetPath: String) {
        val skillDir = ".agents/skills/$name"
        val skillFile = "$skillDir/SKILL.md"
        mkdirsIfMissing(skillDir)
        installBundledFileIfMissing(skillFile, assetPath)
    }

    private fun installBundledFileIfMissing(targetPath: String, assetPath: String) {
        val target = resolveAgentsPath(targetPath)
        if (target.exists() && target.isFile) return
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

