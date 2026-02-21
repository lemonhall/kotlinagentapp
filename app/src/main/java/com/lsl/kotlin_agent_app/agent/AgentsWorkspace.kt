package com.lsl.kotlin_agent_app.agent

import android.content.Context
import com.lsl.kotlin_agent_app.BuildConfig
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.FileNasSmbMountsProvider
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.NasSmbClient
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.NasSmbMountConfig
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.NasSmbMountConfigLoader
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.NasSmbVfs
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.NasSmbVfsException
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.SmbjNasSmbClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

data class AgentsDirEntry(
    val name: String,
    val type: AgentsDirEntryType,
    val displayName: String? = null,
    val subtitle: String? = null,
    val sortKey: Long? = null,
    val iconEmoji: String? = null,
)

enum class AgentsDirEntryType {
    File,
    Dir,
}

class AgentsWorkspace(
    context: Context,
    private val nasSmbClient: NasSmbClient = SmbjNasSmbClient(),
) {
    private val appContext = context.applicationContext
    private val filesRoot: File = appContext.filesDir
    private val agentsRoot: File = File(filesRoot, ".agents")

    private val nasSmbEnvFile: File = File(filesRoot, ".agents/nas_smb/secrets/.env")
    private val nasSmbMountLoader = NasSmbMountConfigLoader()
    private val nasSmbVfs =
        NasSmbVfs(
            mountsProvider = FileNasSmbMountsProvider(envFile = nasSmbEnvFile, loader = nasSmbMountLoader),
            client = nasSmbClient,
        )

    fun ensureInitialized() {
        mkdirsIfMissing(".agents")
        mkdirsIfMissing(".agents/skills")
        mkdirsIfMissing(".agents/sessions")
        mkdirsIfMissing(".agents/workspace")
        mkdirsIfMissing(".agents/workspace/inbox")
        mkdirsIfMissing(".agents/workspace/musics")
        mkdirsIfMissing(".agents/workspace/radios")
        mkdirsIfMissing(".agents/workspace/radios/favorites")
        mkdirsIfMissing(".agents/workspace/radio_recordings")
        mkdirsIfMissing(".agents/workspace/recordings")

        // PRD-0034: root placeholders (Everything is FileSystem).
        ensureTextFileIfMissing(
            path = ".agents/workspace/radio_recordings/_STATUS.md",
            content =
                buildString {
                    appendLine("# radio_recordings 状态")
                    appendLine()
                    appendLine("- ok: true")
                    appendLine("- at: ${(System.currentTimeMillis() / 1000L).coerceAtLeast(0L)}")
                    appendLine("- note: ready")
                    appendLine()
                    appendLine("提示：录制产物按会话目录落盘。")
                },
        )
        // PRD-0035: mic recordings root placeholders (Everything is FileSystem).
        ensureTextFileIfMissing(
            path = ".agents/workspace/recordings/_STATUS.md",
            content =
                buildString {
                    appendLine("# recordings 状态")
                    appendLine()
                    appendLine("- ok: true")
                    appendLine("- at: ${(System.currentTimeMillis() / 1000L).coerceAtLeast(0L)}")
                    appendLine("- note: ready")
                    appendLine()
                    appendLine("提示：录音产物按会话目录落盘。")
                },
        )
        ensureTextFileIfMissing(
            path = ".agents/workspace/radio_recordings/.recordings.index.json",
            content =
                run {
                    val prettyJson = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true }
                    val obj =
                        buildJsonObject {
                            put("schema", JsonPrimitive("kotlin-agent-app/radio-recordings-index@v1"))
                            put("generatedAtSec", JsonPrimitive((System.currentTimeMillis() / 1000L).coerceAtLeast(0L)))
                            put("sessions", buildJsonArray { })
                        }
                    prettyJson.encodeToString(JsonObject.serializer(), obj) + "\n"
                },
        )
        ensureBundledRadioRecordingsEnvFileIfMissing(assetEnvExamplePath = "builtin_radio_recordings/env.example")
        ensureBundledRecordingsEnvFileIfMissing(assetEnvExamplePath = "builtin_radio_recordings/env.example")

        // PRD-0033: nas_smb VFS mount (App-internal mount).
        mkdirsIfMissing(".agents/nas_smb")
        mkdirsIfMissing(".agents/nas_smb/secrets")

        // Best-effort: missing asset must not break the rest.
        // In Debug builds, keep bundled skills synced to avoid stale/partial copies across app reinstalls.
        val overwrite = BuildConfig.DEBUG
        installBundledSkillDir(name = "hello-world", assetDir = "builtin_skills/hello-world", overwrite = overwrite)
        installBundledSkillDir(name = "skill-creator", assetDir = "builtin_skills/skill-creator", overwrite = overwrite)
        installBundledSkillDir(name = "brainstorming", assetDir = "builtin_skills/brainstorming", overwrite = overwrite)
        installBundledSkillDir(name = "find-skills", assetDir = "builtin_skills/find-skills", overwrite = overwrite)
        installBundledSkillDir(name = "deep-research", assetDir = "builtin_skills/deep-research", overwrite = overwrite)
        installBundledSkillDir(name = "jgit-cli", assetDir = "builtin_skills/jgit-cli", overwrite = overwrite)
        installBundledSkillDir(name = "jgit-remote", assetDir = "builtin_skills/jgit-remote", overwrite = overwrite)
        installBundledSkillDir(name = "archive-zip", assetDir = "builtin_skills/archive-zip", overwrite = overwrite)
        installBundledSkillDir(name = "archive-tar", assetDir = "builtin_skills/archive-tar", overwrite = overwrite)
        installBundledSkillDir(name = "calendar-cli", assetDir = "builtin_skills/calendar-cli", overwrite = overwrite)
        installBundledSkillDir(name = "qqmail-cli", assetDir = "builtin_skills/qqmail-cli", overwrite = overwrite)
        installBundledSkillDir(name = "ledger-cli", assetDir = "builtin_skills/ledger-cli", overwrite = overwrite)
        installBundledSkillDir(name = "stock-cli", assetDir = "builtin_skills/stock-cli", overwrite = overwrite)
        installBundledSkillDir(name = "exchange-rate-cli", assetDir = "builtin_skills/exchange-rate-cli", overwrite = overwrite)
        installBundledSkillDir(name = "rss-cli", assetDir = "builtin_skills/rss-cli", overwrite = overwrite)
        installBundledSkillDir(name = "wapo-rss", assetDir = "builtin_skills/wapo-rss", overwrite = overwrite)
        installBundledSkillDir(name = "ssh-cli", assetDir = "builtin_skills/ssh-cli", overwrite = overwrite)
        installBundledSkillDir(name = "irc-cli", assetDir = "builtin_skills/irc-cli", overwrite = overwrite)
        installBundledSkillDir(name = "music-cli", assetDir = "builtin_skills/music-cli", overwrite = overwrite)
        installBundledSkillDir(name = "radio-cli", assetDir = "builtin_skills/radio-cli", overwrite = overwrite)
        installBundledSkillDir(name = "android-tts", assetDir = "builtin_skills/android-tts", overwrite = overwrite)

        // Create secret template file once (never overwrite user's local secrets).
        ensureBundledSecretEnvFileIfMissing(
            skillName = "qqmail-cli",
            assetEnvExamplePath = "builtin_skills/qqmail-cli/secrets/env.example",
        )
        ensureBundledSecretEnvFileIfMissing(
            skillName = "stock-cli",
            assetEnvExamplePath = "builtin_skills/stock-cli/secrets/env.example",
        )
        ensureBundledSecretEnvFileIfMissing(
            skillName = "ssh-cli",
            assetEnvExamplePath = "builtin_skills/ssh-cli/secrets/env.example",
        )
        ensureBundledSecretEnvFileIfMissing(
            skillName = "irc-cli",
            assetEnvExamplePath = "builtin_skills/irc-cli/secrets/env.example",
        )

        // Create NAS SMB secrets template file once (never overwrite user's local secrets).
        ensureBundledNasSmbEnvFileIfMissing(
            assetEnvExamplePath = "builtin_nas_smb/secrets/env.example",
        )

        // Best-effort: materialize mount placeholder directories based on secrets/.env.
        syncNasSmbMountPlaceholders()

        installBundledFile(targetPath = ".agents/sessions/README.md", assetPath = "builtin_sessions/README.md", overwrite = overwrite)
    }

    fun toFile(path: String): File {
        return resolveAgentsPath(path)
    }

    private fun ensureBundledSecretEnvFileIfMissing(
        skillName: String,
        assetEnvExamplePath: String,
    ) {
        val secretsDir = ".agents/skills/$skillName/secrets"
        mkdirsIfMissing(secretsDir)
        val envPath = "$secretsDir/.env"
        val env = resolveAgentsPath(envPath)
        if (env.exists() && env.isFile) return
        // Best-effort: if missing, seed .env from bundled example (so the user can edit in Files tab).
        // Note: avoid dot-files in assets (aapt may ignore them), so we use env.example and copy it to .env.
        installBundledFile(targetPath = envPath, assetPath = assetEnvExamplePath, overwrite = true)
    }

    private fun ensureBundledNasSmbEnvFileIfMissing(
        assetEnvExamplePath: String,
    ) {
        val envPath = ".agents/nas_smb/secrets/.env"
        val env = resolveAgentsPath(envPath)
        if (env.exists() && env.isFile) return
        // Best-effort: seed .env from bundled example (so the user can edit in Files tab).
        installBundledFile(targetPath = envPath, assetPath = assetEnvExamplePath, overwrite = true)
    }

    private fun ensureBundledRadioRecordingsEnvFileIfMissing(
        assetEnvExamplePath: String,
    ) {
        val envPath = ".agents/workspace/radio_recordings/.env"
        val env = resolveAgentsPath(envPath)
        if (env.exists() && env.isFile) return
        // Best-effort: seed .env from bundled example (so the user can edit in Files tab).
        installBundledFile(targetPath = envPath, assetPath = assetEnvExamplePath, overwrite = true)
    }

    private fun ensureBundledRecordingsEnvFileIfMissing(
        assetEnvExamplePath: String,
    ) {
        val envPath = ".agents/workspace/recordings/.env"
        val env = resolveAgentsPath(envPath)
        if (env.exists() && env.isFile) return
        installBundledFile(targetPath = envPath, assetPath = assetEnvExamplePath, overwrite = true)
    }

    private data class NasSmbResolvedDir(
        val mountName: String,
        val relDir: String,
    )

    private data class NasSmbResolvedFile(
        val mountName: String,
        val relPath: String,
    )

    private fun resolveNasSmbDirOrNull(normalized: String): NasSmbResolvedDir? {
        if (normalized == ".agents/nas_smb") return null
        if (!normalized.startsWith(".agents/nas_smb/")) return null
        val segs = normalized.split('/')
        if (segs.size < 3) return null
        val mountName = segs[2]
        if (mountName == "secrets") return null
        val rel = if (segs.size <= 3) "" else segs.subList(3, segs.size).joinToString("/")
        return NasSmbResolvedDir(mountName = mountName, relDir = rel)
    }

    private fun resolveNasSmbFileOrNull(normalized: String): NasSmbResolvedFile? {
        if (!normalized.startsWith(".agents/nas_smb/")) return null
        val segs = normalized.split('/')
        if (segs.size < 4) return null
        val mountName = segs[2]
        if (mountName == "secrets") return null
        // Treat marker metadata file as local.
        if (segs.last() == ".mount.json") return null
        val rel = segs.subList(3, segs.size).joinToString("/")
        if (rel.isBlank()) return null
        return NasSmbResolvedFile(mountName = mountName, relPath = rel)
    }

    private fun resolveNasSmbAnyPathOrNull(normalized: String): NasSmbResolvedFile? {
        if (!normalized.startsWith(".agents/nas_smb/")) return null
        val segs = normalized.split('/')
        if (segs.size < 3) return null
        val mountName = segs[2]
        if (mountName == "secrets") return null
        if (segs.last() == ".mount.json") return null
        val rel = if (segs.size <= 3) "" else segs.subList(3, segs.size).joinToString("/")
        return NasSmbResolvedFile(mountName = mountName, relPath = rel)
    }

    private fun syncNasSmbMountPlaceholders() {
        val nasDir = ".agents/nas_smb"
        try {
            val mounts = nasSmbVfs.mountsByName().values.toList().sortedBy { it.mountName }
            for (m in mounts) {
                val mountDirPath = "$nasDir/${m.mountName}"
                mkdirsIfMissing(mountDirPath)
                val metaPath = "$mountDirPath/.mount.json"
                val meta = renderMountMeta(m)
                try {
                    val f = resolveAgentsPath(metaPath)
                    if (!f.exists() || f.readText(Charsets.UTF_8) != meta) {
                        f.writeText(meta, Charsets.UTF_8)
                    }
                } catch (_: Throwable) {
                }
            }
            try {
                deletePath("$nasDir/config_error.txt", recursive = false)
            } catch (_: Throwable) {
            }
        } catch (t: Throwable) {
            val msg =
                when (t) {
                    is NasSmbVfsException -> "${t.errorCode}: ${t.message}"
                    else -> (t.message ?: "Unknown error")
                }
            try {
                writeTextFile("$nasDir/config_error.txt", "NAS SMB config error:\n$msg\n")
            } catch (_: Throwable) {
            }
        }
    }

    private fun renderMountMeta(mount: NasSmbMountConfig): String {
        val remoteDir = mount.remoteDir.ifBlank { "/" }
        val ro = mount.readOnly
        val id = mount.id
        val name = mount.mountName
        return """
            {
              "version": 1,
              "id": "${escapeJson(id)}",
              "mount_name": "${escapeJson(name)}",
              "share": "${escapeJson(mount.share)}",
              "remote_dir": "${escapeJson(remoteDir)}",
              "read_only": $ro
            }
        """.trimIndent() + "\n"
    }

    private fun escapeJson(s: String): String {
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    fun listDir(path: String): List<AgentsDirEntry> {
        val normalized = normalizeAgentsPath(path)
        val nas = resolveNasSmbDirOrNull(normalized)
        if (nas != null) {
            val entries = nasSmbVfs.listDir(nas.mountName, nas.relDir)
            return entries
                .map { e ->
                    AgentsDirEntry(
                        name = e.name,
                        type = if (e.isDirectory) AgentsDirEntryType.Dir else AgentsDirEntryType.File,
                    )
                }
                .sortedWith(compareBy<AgentsDirEntry>({ it.type != AgentsDirEntryType.Dir }, { it.name.lowercase() }))
        }
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
        val normalized = normalizeAgentsPath(path)
        val nas = resolveNasSmbFileOrNull(normalized)
        if (nas != null) {
            return nasSmbVfs.readTextFile(nas.mountName, nas.relPath, maxBytes = maxBytes)
        }
        val f = resolveAgentsPath(path)
        if (!f.exists() || !f.isFile) error("Not a file: $path")
        val len = f.length()
        if (len > maxBytes) error("File too large (${len}B), max ${maxBytes}B")
        return f.readText(Charsets.UTF_8)
    }

    fun readTextFileHead(path: String, maxBytes: Int = 64 * 1024): String {
        val f = resolveAgentsPath(path)
        if (!f.exists() || !f.isFile) error("Not a file: $path")
        val cap = maxBytes.coerceAtLeast(0)
        if (cap == 0) return ""
        FileInputStream(f).use { input ->
            val buf = ByteArray(cap)
            val n = input.read(buf)
            if (n <= 0) return ""
            return buf.copyOf(n).toString(Charsets.UTF_8)
        }
    }

    fun writeTextFile(path: String, content: String) {
        val normalized = normalizeAgentsPath(path)
        val nas = resolveNasSmbFileOrNull(normalized)
        if (nas != null) {
            nasSmbVfs.writeTextFile(nas.mountName, nas.relPath, content)
            return
        }
        val f = resolveAgentsPath(path)
        val parent = f.parentFile ?: error("Invalid path: $path")
        if (!parent.exists()) parent.mkdirs()
        f.writeText(content, Charsets.UTF_8)
    }

    fun writeTextFileAtomic(path: String, content: String) {
        val normalized = normalizeAgentsPath(path)
        val nas = resolveNasSmbFileOrNull(normalized)
        if (nas != null) {
            // Best-effort: remote VFS may not support atomic rename; fall back to direct write.
            nasSmbVfs.writeTextFile(nas.mountName, nas.relPath, content)
            return
        }

        val f = resolveAgentsPath(path)
        val parent = f.parentFile ?: error("Invalid path: $path")
        if (!parent.exists()) parent.mkdirs()

        val tmp = File(parent, ".tmp_${f.name}_${System.currentTimeMillis()}")
        try {
            tmp.writeText(content, Charsets.UTF_8)

            if (f.exists()) {
                // On Windows, renameTo() cannot overwrite an existing file.
                if (!f.delete()) error("Atomic write failed: unable to delete target: $path")
            }

            if (tmp.renameTo(f)) return

            // Fallback: copy bytes then delete tmp.
            FileInputStream(tmp).use { input ->
                FileOutputStream(f).use { output ->
                    input.copyTo(output)
                }
            }
            tmp.delete()
        } finally {
            try {
                if (tmp.exists()) tmp.delete()
            } catch (_: Throwable) {
            }
        }
    }

    fun mkdir(path: String) {
        val normalized = normalizeAgentsPath(path)
        val nas = resolveNasSmbDirOrNull(normalized)
        if (nas != null) {
            nasSmbVfs.mkdirs(nas.mountName, nas.relDir)
            return
        }
        val f = resolveAgentsPath(path)
        if (!f.exists()) f.mkdirs()
    }

    fun deletePath(path: String, recursive: Boolean) {
        val normalized = normalizeAgentsPath(path)
        val nas = resolveNasSmbAnyPathOrNull(normalized)
        if (nas != null) {
            nasSmbVfs.delete(nas.mountName, nas.relPath, recursive = recursive)
            return
        }
        val f = resolveAgentsPath(path)
        if (!f.exists()) return
        if (f.isFile) {
            f.delete()
            return
        }
        if (!recursive) error("Refusing to delete directory without recursive=true")
        f.deleteRecursively()
    }

    fun movePath(
        from: String,
        to: String,
        overwrite: Boolean,
    ) {
        val fromNormalized = normalizeAgentsPath(from)
        val toNormalized = normalizeAgentsPath(to)
        val fromNas = resolveNasSmbAnyPathOrNull(fromNormalized)
        val toNas = resolveNasSmbAnyPathOrNull(toNormalized)
        if (fromNas != null || toNas != null) {
            if (fromNas == null || toNas == null) {
                error("Refusing to move between local and NAS SMB: $from -> $to")
            }
            if (fromNas.mountName != toNas.mountName) {
                error("Refusing to move between different NAS SMB mounts: ${fromNas.mountName} -> ${toNas.mountName}")
            }
            nasSmbVfs.move(
                mountName = fromNas.mountName,
                fromRelPath = fromNas.relPath,
                toRelPath = toNas.relPath,
                overwrite = overwrite,
            )
            return
        }

        val src = resolveAgentsPath(fromNormalized)
        val dst = resolveAgentsPath(toNormalized)
        if (!src.exists()) error("Not found: $from")

        if (dst.exists()) {
            if (!overwrite) error("Target exists: $to")
            deletePath(to, recursive = dst.isDirectory)
        }

        if (src.isDirectory) {
            val srcPath = src.canonicalFile.path.trimEnd(File.separatorChar) + File.separator
            val dstPath = dst.canonicalFile.path
            if (dstPath.startsWith(srcPath)) error("Refusing to move directory into itself: $to")
        }

        val parent = dst.parentFile ?: error("Invalid target path: $to")
        if (!parent.exists()) parent.mkdirs()

        if (src.renameTo(dst)) return

        if (src.isFile) {
            FileInputStream(src).use { input ->
                FileOutputStream(dst).use { output ->
                    input.copyTo(output)
                }
            }
            if (!src.delete()) error("Move failed: unable to delete source: $from")
            return
        }

        error("Move failed: $from -> $to")
    }

    fun copyPath(
        from: String,
        to: String,
        overwrite: Boolean,
    ) {
        val fromNormalized = normalizeAgentsPath(from)
        val toNormalized = normalizeAgentsPath(to)
        if (fromNormalized == toNormalized) return

        val fromNas = resolveNasSmbAnyPathOrNull(fromNormalized)
        val toNas = resolveNasSmbAnyPathOrNull(toNormalized)
        if ((fromNas != null) != (toNas != null)) {
            error("Refusing to copy between NAS SMB and local workspace")
        }

        if (fromNas != null && toNas != null) {
            if (fromNas.mountName != toNas.mountName) error("Refusing to copy between different mounts")
            val meta = nasSmbVfs.metadataOrNull(fromNas.mountName, fromNas.relPath) ?: error("Not found: $from")
            if (!overwrite) {
                val existing = nasSmbVfs.metadataOrNull(toNas.mountName, toNas.relPath)
                if (existing != null) error("Target exists: $to")
            } else {
                val existing = nasSmbVfs.metadataOrNull(toNas.mountName, toNas.relPath)
                if (existing != null) {
                    nasSmbVfs.delete(toNas.mountName, toNas.relPath, recursive = existing.isDirectory)
                }
            }

            if (meta.isDirectory) {
                copyNasDirRecursive(
                    mountName = fromNas.mountName,
                    fromRelDir = fromNas.relPath,
                    toRelDir = toNas.relPath,
                    overwrite = overwrite,
                )
            } else {
                nasSmbVfs.copyFile(
                    mountName = fromNas.mountName,
                    fromRelPath = fromNas.relPath,
                    toRelPath = toNas.relPath,
                    overwrite = overwrite,
                )
            }
            return
        }

        val src = resolveAgentsPath(fromNormalized)
        val dst = resolveAgentsPath(toNormalized)
        if (!src.exists()) error("Not found: $from")

        if (dst.exists()) {
            if (!overwrite) error("Target exists: $to")
            deletePath(to, recursive = dst.isDirectory)
        }

        if (src.isDirectory) {
            val srcPath = src.canonicalFile.path.trimEnd(File.separatorChar) + File.separator
            val dstPath = dst.canonicalFile.path
            if (dstPath.startsWith(srcPath)) error("Refusing to copy directory into itself: $to")
        }

        val parent = dst.parentFile ?: error("Invalid target path: $to")
        if (!parent.exists()) parent.mkdirs()

        if (src.isFile) {
            FileInputStream(src).use { input ->
                FileOutputStream(dst).use { output ->
                    input.copyTo(output)
                }
            }
            return
        }

        if (src.isDirectory) {
            copyLocalDirRecursive(src, dst)
            return
        }

        error("Copy failed: $from -> $to")
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

    private fun copyLocalDirRecursive(
        srcDir: File,
        dstDir: File,
    ) {
        if (!dstDir.exists()) dstDir.mkdirs()
        val children = srcDir.listFiles().orEmpty()
        for (child in children) {
            val target = File(dstDir, child.name)
            if (child.isDirectory) {
                copyLocalDirRecursive(child, target)
            } else {
                val parent = target.parentFile
                if (parent != null && !parent.exists()) parent.mkdirs()
                FileInputStream(child).use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun copyNasDirRecursive(
        mountName: String,
        fromRelDir: String,
        toRelDir: String,
        overwrite: Boolean,
    ) {
        nasSmbVfs.mkdirs(mountName, toRelDir)
        val entries = nasSmbVfs.listDir(mountName, fromRelDir)
        for (e in entries) {
            val fromChild = if (fromRelDir.isBlank()) e.name else "$fromRelDir/${e.name}"
            val toChild = if (toRelDir.isBlank()) e.name else "$toRelDir/${e.name}"
            if (e.isDirectory) {
                copyNasDirRecursive(mountName, fromChild, toChild, overwrite = overwrite)
            } else {
                val existing = nasSmbVfs.metadataOrNull(mountName, toChild)
                if (existing != null) {
                    if (!overwrite) error("Target exists: .agents/nas_smb/$mountName/$toChild")
                    nasSmbVfs.delete(mountName, toChild, recursive = existing.isDirectory)
                }
                nasSmbVfs.copyFile(mountName, fromChild, toChild, overwrite = overwrite)
            }
        }
    }

    private fun mkdirsIfMissing(path: String) {
        val f = resolveAgentsPath(path)
        if (!f.exists()) f.mkdirs()
    }

    private fun ensureTextFileIfMissing(
        path: String,
        content: String,
    ) {
        val f = resolveAgentsPath(path)
        if (f.exists() && f.isFile) return
        val parent = f.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        f.writeText(content.trimEnd() + "\n", Charsets.UTF_8)
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
