package com.lsl.kotlin_agent_app.agent.tools.terminal.commands.zip

import android.content.Context
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalArtifact
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommandOutput
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.ConfirmRequired
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.PathEscapesAgentsRoot
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.hasFlag
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.isUnsafeArchiveEntryName
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.optionalFlagValue
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.parseIntFlag
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.parseLongFlag
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.relPath
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.requireConfirm
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.requireFlagValue
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.resolveExtractTarget
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.resolveWithinAgents
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile

internal class ZipCommand(
    appContext: Context,
) : TerminalCommand {
    private val agentsRoot = File(appContext.applicationContext.filesDir, ".agents").canonicalFile

    override val name: String = "zip"
    override val description: String = "A pure-Java zip archive CLI (list/extract/create) with safety guardrails."

    override suspend fun run(
        argv: List<String>,
        stdin: String?,
    ): TerminalCommandOutput {
        if (argv.size < 2) return invalidArgs("missing subcommand")
        return try {
            when (argv[1].lowercase()) {
                "list" -> handleList(argv)
                "extract" -> handleExtract(argv)
                "create" -> handleCreate(argv)
                else -> invalidArgs("unknown subcommand: ${argv[1]}")
            }
        } catch (t: PathEscapesAgentsRoot) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "path escapes .agents root"),
                errorCode = "PathEscapesAgentsRoot",
                errorMessage = t.message,
            )
        } catch (t: ConfirmRequired) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "missing --confirm"),
                errorCode = "ConfirmRequired",
                errorMessage = t.message,
            )
        } catch (t: IllegalArgumentException) {
            val msg = t.message.orEmpty()
            if (
                msg.contains("MALFORMED", ignoreCase = true) ||
                    msg.contains("malformed", ignoreCase = true) ||
                    msg.contains("unmappable", ignoreCase = true)
            ) {
                TerminalCommandOutput(
                    exitCode = 2,
                    stdout = "",
                    stderr = msg.ifBlank { "zip entry name decode error" },
                    errorCode = "ZipBadEncoding",
                    errorMessage = t.message,
                )
            } else {
                invalidArgs(t.message ?: "invalid args")
            }
        } catch (t: Throwable) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "zip error"),
                errorCode = "ZipError",
                errorMessage = t.message,
            )
        }
    }

    private fun handleList(argv: List<String>): TerminalCommandOutput {
        val inRel = requireFlagValue(argv, "--in")
        val zipFile = resolveWithinAgents(agentsRoot, inRel)
        if (!zipFile.exists() || !zipFile.isFile) return invalidArgs("zip not found: $inRel")

        val max = parseIntFlag(argv, "--max", defaultValue = 200).coerceAtLeast(0)
        val encodingRaw = optionalFlagValue(argv, "--encoding")?.trim()?.takeIf { it.isNotBlank() }
        val outRel = optionalFlagValue(argv, "--out")?.trim()?.takeIf { it.isNotBlank() }
        val outFile = outRel?.let { resolveWithinAgents(agentsRoot, it) }

        val entries = mutableListOf<JsonElement>()
        val entriesAll = mutableListOf<JsonElement>()
        var total = 0

        val open = openZipForRead(zipFile, encodingRaw)
        open.zip.use { zf ->
            val it = zf.entries
            while (it.hasMoreElements()) {
                val e = it.nextElement()
                total += 1
                val el = zipEntryJson(
                    name = e.name.orEmpty(),
                    size = e.size,
                    csize = e.compressedSize,
                    isDir = e.isDirectory,
                    time = e.lastModifiedDate?.time ?: 0L,
                )
                if (entries.size < max) entries.add(el)
                if (outFile != null) entriesAll.add(el)
            }
        }

        val truncated = total > entries.size
        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("zip list"))
                put("in", JsonPrimitive(inRel))
                put("encoding_requested", JsonPrimitive(open.encodingRequested))
                put("encoding_used", JsonPrimitive(open.encodingUsed))
                put("count_total", JsonPrimitive(total))
                put("count_emitted", JsonPrimitive(entries.size))
                put("truncated", JsonPrimitive(truncated))
                if (outRel != null) put("out", JsonPrimitive(outRel))
                put("entries", buildJsonArray { entries.forEach { add(it) } })
            }

        val artifacts =
            if (outFile != null) {
                val parent = outFile.parentFile
                if (parent != null && !parent.exists()) parent.mkdirs()
                val fullJson =
                    buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("command", JsonPrimitive("zip list"))
                        put("in", JsonPrimitive(inRel))
                        put("encoding_requested", JsonPrimitive(open.encodingRequested))
                        put("encoding_used", JsonPrimitive(open.encodingUsed))
                        put("count_total", JsonPrimitive(total))
                        put("entries", buildJsonArray { entriesAll.forEach { add(it) } })
                    }
                outFile.writeText(fullJson.toString() + "\n", Charsets.UTF_8)
                listOf(
                    TerminalArtifact(
                        path = ".agents/" + relPath(agentsRoot, outFile),
                        mime = "application/json",
                        description = "Full zip list output (may be large).",
                    ),
                )
            } else {
                emptyList()
            }

        val stdout =
            buildString {
                appendLine("zip list: $total entries" + if (truncated) " (showing ${entries.size})" else "")
                if (open.encodingRequested != "auto" || open.encodingUsed != "UTF-8") {
                    appendLine("encoding: ${open.encodingUsed} (requested: ${open.encodingRequested})")
                }
                if (outRel != null) appendLine("full list written: $outRel")
                for (el in entries) {
                    val obj = el as? kotlinx.serialization.json.JsonObject ?: continue
                    val name = (obj["name"] as? JsonPrimitive)?.content ?: ""
                    val size = (obj["uncompressed_bytes"] as? JsonPrimitive)?.content ?: ""
                    appendLine("$size\t$name")
                }
            }.trimEnd()

        return TerminalCommandOutput(
            exitCode = 0,
            stdout = stdout,
            result = result,
            artifacts = artifacts,
        )
    }

    private fun handleExtract(argv: List<String>): TerminalCommandOutput {
        val inRel = requireFlagValue(argv, "--in")
        val destRel = requireFlagValue(argv, "--dest")
        val overwrite = hasFlag(argv, "--overwrite")
        if (overwrite && !hasFlag(argv, "--confirm")) {
            requireConfirm(argv, extraMessage = "--overwrite requires --confirm")
        }
        requireConfirm(argv)

        val zipFile = resolveWithinAgents(agentsRoot, inRel)
        if (!zipFile.exists() || !zipFile.isFile) return invalidArgs("zip not found: $inRel")

        val destDir = resolveWithinAgents(agentsRoot, destRel)
        if (!destDir.exists()) destDir.mkdirs()
        if (!destDir.exists() || !destDir.isDirectory) return invalidArgs("--dest must be a directory: $destRel")

        val encodingRaw = optionalFlagValue(argv, "--encoding")?.trim()?.takeIf { it.isNotBlank() }
        val maxFiles = parseIntFlag(argv, "--max-files", defaultValue = 2000).coerceAtLeast(0)
        val maxBytes = parseLongFlag(argv, "--max-bytes", defaultValue = 512L * 1024L * 1024L).coerceAtLeast(0L)

        var filesWritten = 0
        var dirsCreated = 0
        var bytesWritten = 0L
        var skippedExisting = 0
        var skippedUnsafePath = 0
        var skippedTooLarge = 0

        val open = openZipForRead(zipFile, encodingRaw)
        open.zip.use { zf ->
            val it = zf.entries
            val buf = ByteArray(64 * 1024)
            while (it.hasMoreElements()) {
                val e = it.nextElement()
                val rawName = e.name.orEmpty()
                val name = rawName.replace('\\', '/')
                if (isUnsafeArchiveEntryName(name)) {
                    skippedUnsafePath += 1
                    continue
                }

                val target = resolveExtractTarget(destDir, name)
                if (target == null) {
                    skippedUnsafePath += 1
                    continue
                }

                if (e.isDirectory) {
                    if (!target.exists()) {
                        target.mkdirs()
                        dirsCreated += 1
                    }
                    continue
                }

                if (maxFiles > 0 && filesWritten >= maxFiles) {
                    skippedTooLarge += 1
                    return archiveTooLarge(
                        inRel = inRel,
                        destRel = destRel,
                        filesWritten = filesWritten,
                        dirsCreated = dirsCreated,
                        bytesWritten = bytesWritten,
                        skippedExisting = skippedExisting,
                        skippedUnsafePath = skippedUnsafePath,
                        skippedTooLarge = skippedTooLarge,
                        message = "max files exceeded: --max-files=$maxFiles",
                    )
                }

                if (target.exists() && !overwrite) {
                    skippedExisting += 1
                    continue
                }

                val parent = target.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                    dirsCreated += 1
                }

                zf.getInputStream(e).use { input ->
                    FileOutputStream(target).use { output ->
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            if (maxBytes > 0 && bytesWritten + n > maxBytes) {
                                try {
                                    target.delete()
                                } catch (_: Throwable) {
                                }
                                skippedTooLarge += 1
                                return archiveTooLarge(
                                    inRel = inRel,
                                    destRel = destRel,
                                    filesWritten = filesWritten,
                                    dirsCreated = dirsCreated,
                                    bytesWritten = bytesWritten,
                                    skippedExisting = skippedExisting,
                                    skippedUnsafePath = skippedUnsafePath,
                                    skippedTooLarge = skippedTooLarge,
                                    message = "max bytes exceeded: --max-bytes=$maxBytes",
                                )
                            }
                            output.write(buf, 0, n)
                            bytesWritten += n.toLong()
                        }
                    }
                }
                filesWritten += 1
            }
        }

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("zip extract"))
                put("in", JsonPrimitive(inRel))
                put("dest", JsonPrimitive(destRel))
                put("encoding_requested", JsonPrimitive(open.encodingRequested))
                put("encoding_used", JsonPrimitive(open.encodingUsed))
                put("files_written", JsonPrimitive(filesWritten))
                put("dirs_created", JsonPrimitive(dirsCreated))
                put("bytes_written", JsonPrimitive(bytesWritten))
                put(
                    "skipped",
                    buildJsonObject {
                        put("existing", JsonPrimitive(skippedExisting))
                        put("unsafe_path", JsonPrimitive(skippedUnsafePath))
                        put("too_large", JsonPrimitive(skippedTooLarge))
                    },
                )
            }

        val stdout =
            buildString {
                appendLine("zip extract: wrote $filesWritten files to $destRel ($bytesWritten bytes)")
                if (open.encodingRequested != "auto" || open.encodingUsed != "UTF-8") {
                    appendLine("encoding: ${open.encodingUsed} (requested: ${open.encodingRequested})")
                }
                if (skippedExisting > 0) appendLine("skipped existing: $skippedExisting")
                if (skippedUnsafePath > 0) appendLine("skipped unsafe paths: $skippedUnsafePath")
                if (skippedTooLarge > 0) appendLine("skipped too large: $skippedTooLarge")
            }.trimEnd()

        return TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
    }

    private fun handleCreate(argv: List<String>): TerminalCommandOutput {
        val srcRel = requireFlagValue(argv, "--src")
        val outRel = requireFlagValue(argv, "--out")
        val overwrite = hasFlag(argv, "--overwrite")
        if (overwrite && !hasFlag(argv, "--confirm")) {
            requireConfirm(argv, extraMessage = "--overwrite requires --confirm")
        }
        requireConfirm(argv)

        val src = resolveWithinAgents(agentsRoot, srcRel)
        if (!src.exists()) return invalidArgs("src not found: $srcRel")

        val outFile = resolveWithinAgents(agentsRoot, outRel)
        val parent = outFile.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        if (outFile.exists() && !overwrite) {
            return TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = "refusing to overwrite existing output without --overwrite --confirm: $outRel",
                errorCode = "RefuseOverwrite",
                errorMessage = "refusing to overwrite",
            )
        }

        val level = parseIntFlag(argv, "--level", defaultValue = 6)
        if (level !in 0..9) return invalidArgs("--level must be 0-9")

        val srcRoot = src.canonicalFile
        var filesAdded = 0
        val buf = ByteArray(64 * 1024)

        val tmp = File(outFile.parentFile ?: agentsRoot, ".tmp-${UUID.randomUUID().toString().replace("-", "").take(10)}.zip")
        if (tmp.exists()) tmp.delete()

        ZipOutputStream(FileOutputStream(tmp)).use { zos ->
            try {
                // Available on Android/JDK: set compression level if supported.
                zos.setLevel(level)
            } catch (_: Throwable) {
                // Best-effort: ignore if not available on this runtime.
            }

            fun addFile(f: File, entryName: String) {
                val n = entryName.replace('\\', '/').trimStart('/')
                if (n.isEmpty()) return
                if (isUnsafeArchiveEntryName(n)) return
                val ce = f.canonicalFile
                val rootPath = srcRoot.path.trimEnd(File.separatorChar) + File.separator
                if (ce.path != srcRoot.path && !ce.path.startsWith(rootPath)) return

                val e = ZipEntry(n)
                val lm = f.lastModified()
                if (lm > 0L) e.time = lm
                zos.putNextEntry(e)
                f.inputStream().use { input ->
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        zos.write(buf, 0, read)
                    }
                }
                zos.closeEntry()
                filesAdded += 1
            }

            fun addDir(dir: File, entryName: String) {
                val n = entryName.replace('\\', '/').trimStart('/').trimEnd('/') + "/"
                if (isUnsafeArchiveEntryName(n)) return
                val e = ZipEntry(n)
                val lm = dir.lastModified()
                if (lm > 0L) e.time = lm
                zos.putNextEntry(e)
                zos.closeEntry()
            }

            if (src.isFile) {
                addFile(src, src.name)
            } else {
                val rootPath = srcRoot.path.trimEnd(File.separatorChar) + File.separator
                for (f in srcRoot.walkTopDown()) {
                    val cf = f.canonicalFile
                    if (cf.path != srcRoot.path && !cf.path.startsWith(rootPath)) continue
                    if (f == srcRoot) continue
                    val rel =
                        cf.path.substring(rootPath.length).replace('\\', '/')
                    if (f.isDirectory) {
                        addDir(f, rel)
                    } else if (f.isFile) {
                        addFile(f, rel)
                    }
                }
            }
        }

        if (outFile.exists()) outFile.delete()
        tmp.renameTo(outFile)

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("zip create"))
                put("src", JsonPrimitive(srcRel))
                put("out", JsonPrimitive(outRel))
                put("files_added", JsonPrimitive(filesAdded))
                put("bytes_written", JsonPrimitive(outFile.length()))
                put("compression_level", JsonPrimitive(level))
            }

        val stdout = "zip create: $filesAdded files -> $outRel (${outFile.length()} bytes)"
        return TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
    }

    private data class OpenZipResult(
        val zip: CommonsZipFile,
        val encodingRequested: String,
        val encodingUsed: String,
    )

    private fun openZipForRead(
        zipFile: File,
        encodingRaw: String?,
    ): OpenZipResult {
        val requested = normalizeEncodingSpec(encodingRaw)
        if (requested != "auto") {
            val candidates =
                when (requested) {
                    "windows-31j" -> listOf("windows-31j", "Shift_JIS")
                    "GBK" -> listOf("GBK", "GB18030")
                    else -> listOf(requested)
                }.filter { Charset.isSupported(it) }.distinct()
            if (candidates.isEmpty()) throw IllegalArgumentException("unsupported --encoding: $requested")
            var last: Throwable? = null
            for (enc in candidates) {
                try {
                    val zip = openCommonsZip(zipFile, enc)
                    // Force a scan to surface any decoding issues early.
                    validateZipEntries(zip)
                    return OpenZipResult(zip = zip, encodingRequested = requested, encodingUsed = enc)
                } catch (t: Throwable) {
                    last = t
                }
            }
            val msg = last?.message?.takeIf { it.isNotBlank() } ?: "zip entry name decode error"
            throw IllegalArgumentException(msg, last)
        }

        val candidates =
            listOf("UTF-8", "windows-31j", "Shift_JIS", "GBK", "GB18030")
                .filter { Charset.isSupported(it) }
                .distinct()

        // Phase 1: sample-score to pick a likely encoding (stable tie-break by candidate order).
        val scoredCandidates = mutableListOf<Pair<String, Int>>()
        for (enc in candidates) {
            try {
                openCommonsZip(zipFile, enc).use { zip ->
                    val score = scoreZipEntryNames(zip, maxEntries = 200)
                    scoredCandidates.add(enc to score)
                }
            } catch (_: Throwable) {
                // Ignore; phase 2 will try to open+validate anyway.
            }
        }
        val preferred =
            if (scoredCandidates.isEmpty()) {
                candidates
            } else {
                val bestByScore =
                    scoredCandidates
                        .sortedWith(
                            compareByDescending<Pair<String, Int>> { it.second }
                                .thenBy { pair -> candidates.indexOf(pair.first) },
                        )
                        .map { it.first }
                // Keep any unscored candidates as last resort, preserving the original order.
                (bestByScore + candidates.filter { c -> bestByScore.none { it == c } }).distinct()
            }

        // Phase 2: validate all entry names with the preferred ordering, returning the first fully-decodable encoding.
        var last: Throwable? = null
        for (enc in preferred) {
            try {
                val zip = openCommonsZip(zipFile, enc)
                try {
                    validateZipEntries(zip)
                } catch (t: Throwable) {
                    try {
                        zip.close()
                    } catch (_: Throwable) {
                    }
                    throw t
                }
                return OpenZipResult(zip = zip, encodingRequested = "auto", encodingUsed = enc)
            } catch (t: Throwable) {
                last = t
            }
        }
        val msg = last?.message?.takeIf { it.isNotBlank() } ?: "zip entry name decode error"
        throw IllegalArgumentException(msg, last)
    }

    private fun openCommonsZip(
        zipFile: File,
        charsetName: String,
    ): CommonsZipFile {
        val raf = RandomAccessFile(zipFile, "r")
        val ch = RafSeekableByteChannel(raf)
        try {
            return CommonsZipFile
                .builder()
                .setSeekableByteChannel(ch)
                .setCharset(Charset.forName(charsetName))
                .setUseUnicodeExtraFields(true)
                .get()
        } catch (t: Throwable) {
            try {
                ch.close()
            } catch (_: Throwable) {
            }
            throw t
        }
    }

    private class RafSeekableByteChannel(
        private val raf: RandomAccessFile,
    ) : SeekableByteChannel by raf.channel {
        override fun close() {
            try {
                raf.channel.close()
            } finally {
                raf.close()
            }
        }
    }

    private fun validateZipEntries(zip: CommonsZipFile) {
        val it = zip.entries
        while (it.hasMoreElements()) {
            val e = it.nextElement()
            // Accessing e.name is the main thing that can fail when decoding.
            e.name
        }
    }

    private fun scoreZipEntryNames(
        zip: CommonsZipFile,
        maxEntries: Int,
    ): Int {
        var score = 0
        var seen = 0
        val it = zip.entries
        while (it.hasMoreElements() && seen < maxEntries) {
            val e = it.nextElement()
            val n = e.name.orEmpty()
            score += scoreDecodedName(n)
            seen += 1
        }
        return score
    }

    private fun scoreDecodedName(name: String): Int {
        if (name.isEmpty()) return -10
        var s = 0
        val limit = minOf(name.length, 120)
        for (i in 0 until limit) {
            val ch = name[i]
            val cp = ch.code
            when {
                ch == '\uFFFD' -> s -= 40 // replacement char
                cp in 0x00..0x1F -> s -= 6 // control chars
                cp in 0x7F..0x9F -> s -= 6 // control chars
                cp in 0xE000..0xF8FF -> s -= 2 // private use (often mojibake)
                cp in 0x3040..0x309F -> s += 4 // hiragana
                cp in 0x30A0..0x30FF -> s += 4 // katakana
                cp in 0x4E00..0x9FFF -> s += 3 // CJK unified
                cp in 0xFF00..0xFFEF -> s += 1 // fullwidth/halfwidth
                else -> {
                    // neutral
                }
            }
        }
        // Mild penalty for the typical "�" marker (if it sneaks in via some console rendering).
        if (name.contains('�')) s -= 20
        return s
    }

    private fun normalizeEncodingSpec(raw: String?): String {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return "auto"
        val key = v.lowercase()
        return when (key) {
            "auto" -> "auto"
            "utf8", "utf-8" -> "UTF-8"
            "cp932",
            "ms932",
            "windows-31j",
            "shift_jis",
            "shift-jis",
            "shiftjis",
            "sjis",
            -> "windows-31j"
            "cp936",
            "gbk",
            "windows-936",
            -> "GBK"
            else -> v
        }
    }

    private fun zipEntryJson(
        name: String,
        size: Long,
        csize: Long,
        isDir: Boolean,
        time: Long,
    ): JsonElement {
        return buildJsonObject {
            put("name", JsonPrimitive(name))
            put("compressed_bytes", JsonPrimitive(csize))
            put("uncompressed_bytes", JsonPrimitive(size))
            put("is_dir", JsonPrimitive(isDir))
            put("modified_time_ms", JsonPrimitive(time))
        }
    }

    private fun invalidArgs(message: String): TerminalCommandOutput {
        return TerminalCommandOutput(
            exitCode = 2,
            stdout = "",
            stderr = message,
            errorCode = "InvalidArgs",
            errorMessage = message,
        )
    }

    private fun archiveTooLarge(
        inRel: String,
        destRel: String,
        filesWritten: Int,
        dirsCreated: Int,
        bytesWritten: Long,
        skippedExisting: Int,
        skippedUnsafePath: Int,
        skippedTooLarge: Int,
        message: String,
    ): TerminalCommandOutput {
        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(false))
                put("command", JsonPrimitive("zip extract"))
                put("in", JsonPrimitive(inRel))
                put("dest", JsonPrimitive(destRel))
                put("files_written", JsonPrimitive(filesWritten))
                put("dirs_created", JsonPrimitive(dirsCreated))
                put("bytes_written", JsonPrimitive(bytesWritten))
                put(
                    "skipped",
                    buildJsonObject {
                        put("existing", JsonPrimitive(skippedExisting))
                        put("unsafe_path", JsonPrimitive(skippedUnsafePath))
                        put("too_large", JsonPrimitive(skippedTooLarge))
                    },
                )
            }
        return TerminalCommandOutput(
            exitCode = 2,
            stdout = "",
            stderr = message,
            errorCode = "ArchiveTooLarge",
            errorMessage = message,
            result = result,
        )
    }
}
