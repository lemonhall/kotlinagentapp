package com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive

import java.io.File

internal class PathEscapesAgentsRoot(
    message: String,
) : IllegalArgumentException(message)

internal class ConfirmRequired(
    message: String,
) : IllegalArgumentException(message)

internal fun requireFlagValue(argv: List<String>, flag: String): String {
    val idx = argv.indexOf(flag)
    if (idx < 0 || idx + 1 >= argv.size) throw IllegalArgumentException("missing required flag: $flag")
    return argv[idx + 1]
}

internal fun optionalFlagValue(argv: List<String>, flag: String): String? {
    val idx = argv.indexOf(flag)
    if (idx < 0 || idx + 1 >= argv.size) return null
    return argv[idx + 1]
}

internal fun parseIntFlag(
    argv: List<String>,
    flag: String,
    defaultValue: Int,
): Int {
    val raw = optionalFlagValue(argv, flag) ?: return defaultValue
    val v = raw.toIntOrNull() ?: throw IllegalArgumentException("invalid int for $flag: $raw")
    return v
}

internal fun parseLongFlag(
    argv: List<String>,
    flag: String,
    defaultValue: Long,
): Long {
    val raw = optionalFlagValue(argv, flag) ?: return defaultValue
    val v = raw.toLongOrNull() ?: throw IllegalArgumentException("invalid long for $flag: $raw")
    return v
}

internal fun hasFlag(argv: List<String>, flag: String): Boolean = argv.any { it == flag }

internal fun requireConfirm(
    argv: List<String>,
    extraMessage: String? = null,
) {
    if (!hasFlag(argv, "--confirm")) {
        val msg = extraMessage?.takeIf { it.isNotBlank() } ?: "missing --confirm"
        throw ConfirmRequired(msg)
    }
}

internal fun resolveWithinAgents(
    agentsRoot: File,
    rel: String,
): File {
    val raw = rel.replace('\\', '/').trim()
    if (raw.isEmpty()) throw IllegalArgumentException("path is empty")
    if (raw.startsWith("/") || raw.contains(":")) throw IllegalArgumentException("absolute paths are not allowed")
    if (raw.split('/').any { it == ".." }) throw PathEscapesAgentsRoot("path escapes .agents root: $rel")

    val root = agentsRoot.canonicalFile
    val target = File(root, raw.trimStart('/')).canonicalFile
    val rootPath = root.path
    val rootPrefix = rootPath.trimEnd(File.separatorChar) + File.separator
    if (target.path != rootPath && !target.path.startsWith(rootPrefix)) {
        throw PathEscapesAgentsRoot("path escapes .agents root: $rel")
    }
    return target
}

internal fun relPath(
    agentsRoot: File,
    file: File,
): String {
    val f = file.canonicalFile
    val root = agentsRoot.canonicalFile
    val rootPath = root.path.trimEnd(File.separatorChar) + File.separator
    val p = f.path
    val rel =
        when {
            p == root.path -> ""
            p.startsWith(rootPath) -> p.substring(rootPath.length)
            else -> p
        }
    return rel.replace('\\', '/').trimStart('/')
}

internal fun normalizeArchiveEntryName(name: String): String {
    return name.replace('\\', '/').trim().trimStart('/')
}

internal fun isUnsafeArchiveEntryName(name: String): Boolean {
    val n = normalizeArchiveEntryName(name)
    if (n.isEmpty()) return true
    if (n.startsWith("/")) return true
    if (n.contains(":")) return true
    if (n.contains('\u0000')) return true
    if (n.split('/').any { it == ".." }) return true
    return false
}

internal fun resolveExtractTarget(
    destRoot: File,
    entryName: String,
): File? {
    val n = normalizeArchiveEntryName(entryName)
    if (isUnsafeArchiveEntryName(n)) return null

    val dest = destRoot.canonicalFile
    val target = File(dest, n).canonicalFile
    val destPath = dest.path
    val destPrefix = destPath.trimEnd(File.separatorChar) + File.separator
    if (target.path != destPath && !target.path.startsWith(destPrefix)) return null
    return target
}

