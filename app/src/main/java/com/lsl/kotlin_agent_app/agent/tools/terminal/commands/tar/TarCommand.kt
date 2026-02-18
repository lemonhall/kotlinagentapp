package com.lsl.kotlin_agent_app.agent.tools.terminal.commands.tar

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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream

internal class TarCommand(
    appContext: Context,
) : TerminalCommand {
    private val agentsRoot = File(appContext.applicationContext.filesDir, ".agents").canonicalFile

    override val name: String = "tar"
    override val description: String = "A commons-compress tar CLI (list/extract/create) with safety guardrails."

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
            invalidArgs(t.message ?: "invalid args")
        } catch (t: Throwable) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "tar error"),
                errorCode = "TarError",
                errorMessage = t.message,
            )
        }
    }

    private enum class TarFormat(val id: String) {
        Tar("tar"),
        TarGz("tar.gz"),
        TarBz2("tar.bz2"),
        TarXz("tar.xz"),
    }

    private fun handleList(argv: List<String>): TerminalCommandOutput {
        val inRel = requireFlagValue(argv, "--in")
        val tarFile = resolveWithinAgents(agentsRoot, inRel)
        if (!tarFile.exists() || !tarFile.isFile) return invalidArgs("tar not found: $inRel")

        val format = parseFormat(argv, inRel)
        val max = parseIntFlag(argv, "--max", defaultValue = 200).coerceAtLeast(0)
        val outRel = optionalFlagValue(argv, "--out")?.trim()?.takeIf { it.isNotBlank() }
        val outFile = outRel?.let { resolveWithinAgents(agentsRoot, it) }

        val entries = mutableListOf<JsonElement>()
        val entriesAll = mutableListOf<JsonElement>()
        var total = 0

        openTarInputStream(tarFile, format).use { tin ->
            while (true) {
                val e = tin.nextTarEntry ?: break
                total += 1
                val el = tarEntryJson(e)
                if (entries.size < max) entries.add(el)
                if (outFile != null) entriesAll.add(el)
            }
        }

        val truncated = total > entries.size
        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("tar list"))
                put("in", JsonPrimitive(inRel))
                put("format", JsonPrimitive(format.id))
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
                        put("command", JsonPrimitive("tar list"))
                        put("in", JsonPrimitive(inRel))
                        put("format", JsonPrimitive(format.id))
                        put("count_total", JsonPrimitive(total))
                        put("entries", buildJsonArray { entriesAll.forEach { add(it) } })
                    }
                outFile.writeText(fullJson.toString() + "\n", Charsets.UTF_8)
                listOf(
                    TerminalArtifact(
                        path = ".agents/" + relPath(agentsRoot, outFile),
                        mime = "application/json",
                        description = "Full tar list output (may be large).",
                    ),
                )
            } else {
                emptyList()
            }

        val stdout =
            buildString {
                appendLine("tar list (${"%s".format(format.id)}): $total entries" + if (truncated) " (showing ${entries.size})" else "")
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

        val tarFile = resolveWithinAgents(agentsRoot, inRel)
        if (!tarFile.exists() || !tarFile.isFile) return invalidArgs("tar not found: $inRel")

        val destDir = resolveWithinAgents(agentsRoot, destRel)
        if (!destDir.exists()) destDir.mkdirs()
        if (!destDir.exists() || !destDir.isDirectory) return invalidArgs("--dest must be a directory: $destRel")

        val format = parseFormat(argv, inRel)
        val maxFiles = parseIntFlag(argv, "--max-files", defaultValue = 2000).coerceAtLeast(0)
        val maxBytes = parseLongFlag(argv, "--max-bytes", defaultValue = 512L * 1024L * 1024L).coerceAtLeast(0L)

        var filesWritten = 0
        var dirsCreated = 0
        var bytesWritten = 0L
        var skippedExisting = 0
        var skippedUnsafePath = 0
        var skippedUnsafeLink = 0
        var skippedTooLarge = 0

        val buf = ByteArray(64 * 1024)
        openTarInputStream(tarFile, format).use { tin ->
            while (true) {
                val e = tin.nextTarEntry ?: break
                val rawName = e.name.orEmpty()
                val name = rawName.replace('\\', '/')

                val isLink = e.isSymbolicLink || e.isLink
                if (isLink) {
                    skippedUnsafeLink += 1
                    drainEntry(tin, e, buf)
                    continue
                }

                if (isUnsafeArchiveEntryName(name)) {
                    skippedUnsafePath += 1
                    drainEntry(tin, e, buf)
                    continue
                }

                val target = resolveExtractTarget(destDir, name)
                if (target == null) {
                    skippedUnsafePath += 1
                    drainEntry(tin, e, buf)
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
                        format = format.id,
                        filesWritten = filesWritten,
                        dirsCreated = dirsCreated,
                        bytesWritten = bytesWritten,
                        skippedExisting = skippedExisting,
                        skippedUnsafePath = skippedUnsafePath,
                        skippedUnsafeLink = skippedUnsafeLink,
                        skippedTooLarge = skippedTooLarge,
                        message = "max files exceeded: --max-files=$maxFiles",
                    )
                }

                if (target.exists() && !overwrite) {
                    skippedExisting += 1
                    drainEntry(tin, e, buf)
                    continue
                }

                val parent = target.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                    dirsCreated += 1
                }

                FileOutputStream(target).use { output ->
                    var remaining = if (e.size >= 0) e.size else Long.MAX_VALUE
                    while (remaining > 0) {
                        val want = if (remaining == Long.MAX_VALUE) buf.size else minOf(buf.size.toLong(), remaining).toInt()
                        val n = tin.read(buf, 0, want)
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
                                format = format.id,
                                filesWritten = filesWritten,
                                dirsCreated = dirsCreated,
                                bytesWritten = bytesWritten,
                                skippedExisting = skippedExisting,
                                skippedUnsafePath = skippedUnsafePath,
                                skippedUnsafeLink = skippedUnsafeLink,
                                skippedTooLarge = skippedTooLarge,
                                message = "max bytes exceeded: --max-bytes=$maxBytes",
                            )
                        }
                        output.write(buf, 0, n)
                        bytesWritten += n.toLong()
                        if (remaining != Long.MAX_VALUE) remaining -= n.toLong()
                    }
                }
                filesWritten += 1
            }
        }

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("tar extract"))
                put("in", JsonPrimitive(inRel))
                put("dest", JsonPrimitive(destRel))
                put("format", JsonPrimitive(format.id))
                put("files_written", JsonPrimitive(filesWritten))
                put("dirs_created", JsonPrimitive(dirsCreated))
                put("bytes_written", JsonPrimitive(bytesWritten))
                put(
                    "skipped",
                    buildJsonObject {
                        put("existing", JsonPrimitive(skippedExisting))
                        put("unsafe_path", JsonPrimitive(skippedUnsafePath))
                        put("unsafe_link", JsonPrimitive(skippedUnsafeLink))
                        put("too_large", JsonPrimitive(skippedTooLarge))
                    },
                )
            }

        val stdout =
            buildString {
                appendLine("tar extract (${"%s".format(format.id)}): wrote $filesWritten files to $destRel ($bytesWritten bytes)")
                if (skippedExisting > 0) appendLine("skipped existing: $skippedExisting")
                if (skippedUnsafePath > 0) appendLine("skipped unsafe paths: $skippedUnsafePath")
                if (skippedUnsafeLink > 0) appendLine("skipped unsafe links: $skippedUnsafeLink")
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

        val format = parseFormatForCreate(argv, outRel)
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

        val srcRoot = src.canonicalFile
        val rootPath = if (srcRoot.isFile) srcRoot.parentFile?.canonicalFile?.path.orEmpty() else srcRoot.path
        val rootPrefix = rootPath.trimEnd(File.separatorChar) + File.separator
        var filesAdded = 0

        val tmpExt =
            when (format) {
                TarFormat.Tar -> ".tar"
                TarFormat.TarGz -> ".tar.gz"
                TarFormat.TarBz2 -> ".tar.bz2"
                TarFormat.TarXz -> ".tar.xz"
            }
        val tmp = File(outFile.parentFile ?: agentsRoot, ".tmp-${UUID.randomUUID().toString().replace("-", "").take(10)}$tmpExt")
        if (tmp.exists()) tmp.delete()

        openTarOutputStream(tmp, format).use { tout ->
            if (srcRoot.isFile) {
                addTarFile(tout, srcRoot, srcRoot.name, bufSize = 64 * 1024).also { filesAdded += it }
            } else {
                for (f in srcRoot.walkTopDown()) {
                    val cf = f.canonicalFile
                    if (cf.path != srcRoot.path && !cf.path.startsWith(srcRoot.path.trimEnd(File.separatorChar) + File.separator)) continue
                    if (f == srcRoot) continue
                    val rel = cf.path.substring(srcRoot.path.trimEnd(File.separatorChar).length + 1).replace('\\', '/')
                    if (isUnsafeArchiveEntryName(rel)) continue
                    if (f.isDirectory) {
                        val e = TarArchiveEntry(rel.trimEnd('/') + "/")
                        val lm = f.lastModified()
                        if (lm > 0L) e.modTime = Date(lm)
                        tout.putArchiveEntry(e)
                        tout.closeArchiveEntry()
                    } else if (f.isFile) {
                        filesAdded += addTarFile(tout, f, rel, bufSize = 64 * 1024)
                    }
                }
            }
            tout.finish()
        }

        if (outFile.exists()) outFile.delete()
        tmp.renameTo(outFile)

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("tar create"))
                put("src", JsonPrimitive(srcRel))
                put("out", JsonPrimitive(outRel))
                put("format", JsonPrimitive(format.id))
                put("files_added", JsonPrimitive(filesAdded))
                put("bytes_written", JsonPrimitive(outFile.length()))
            }

        val stdout = "tar create (${format.id}): $filesAdded files -> $outRel (${outFile.length()} bytes)"
        return TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
    }

    private fun parseFormat(
        argv: List<String>,
        inPath: String,
    ): TarFormat {
        val raw = optionalFlagValue(argv, "--format")?.trim()?.lowercase()
        if (!raw.isNullOrBlank()) return parseFormatId(raw)
        return inferFormatFromPath(inPath)
    }

    private fun parseFormatForCreate(
        argv: List<String>,
        outPath: String,
    ): TarFormat {
        val raw = optionalFlagValue(argv, "--format")?.trim()?.lowercase()
        if (!raw.isNullOrBlank()) return parseFormatId(raw)
        return inferFormatFromPath(outPath)
    }

    private fun parseFormatId(id: String): TarFormat {
        return when (id) {
            "tar" -> TarFormat.Tar
            "tar.gz", "tgz" -> TarFormat.TarGz
            "tar.bz2", "tbz2" -> TarFormat.TarBz2
            "tar.xz", "txz" -> TarFormat.TarXz
            else -> throw IllegalArgumentException("unsupported --format: $id")
        }
    }

    private fun inferFormatFromPath(path: String): TarFormat {
        val p = path.lowercase()
        return when {
            p.endsWith(".tar.gz") || p.endsWith(".tgz") -> TarFormat.TarGz
            p.endsWith(".tar.bz2") || p.endsWith(".tbz2") -> TarFormat.TarBz2
            p.endsWith(".tar.xz") || p.endsWith(".txz") -> TarFormat.TarXz
            else -> TarFormat.Tar
        }
    }

    private fun openTarInputStream(
        f: File,
        format: TarFormat,
    ): TarArchiveInputStream {
        val raw: InputStream = FileInputStream(f).buffered()
        val inStream =
            when (format) {
                TarFormat.Tar -> raw
                TarFormat.TarGz -> GzipCompressorInputStream(raw, true)
                TarFormat.TarBz2 -> BZip2CompressorInputStream(raw, true)
                TarFormat.TarXz -> XZCompressorInputStream(raw, true)
            }
        return TarArchiveInputStream(inStream)
    }

    private fun openTarOutputStream(
        f: File,
        format: TarFormat,
    ): TarArchiveOutputStream {
        val raw: OutputStream = FileOutputStream(f).buffered()
        val outStream =
            when (format) {
                TarFormat.Tar -> raw
                TarFormat.TarGz -> GzipCompressorOutputStream(raw)
                TarFormat.TarBz2 -> BZip2CompressorOutputStream(raw)
                TarFormat.TarXz -> XZCompressorOutputStream(raw)
            }
        return TarArchiveOutputStream(outStream).apply {
            setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
        }
    }

    private fun tarEntryJson(e: TarArchiveEntry): JsonElement {
        val n = e.name.orEmpty()
        val size = e.size
        val time = e.modTime?.time ?: 0L
        val isLink = e.isSymbolicLink || e.isLink
        val linkName = e.linkName
        return buildJsonObject {
            put("name", JsonPrimitive(n))
            put("compressed_bytes", JsonPrimitive(size))
            put("uncompressed_bytes", JsonPrimitive(size))
            put("is_dir", JsonPrimitive(e.isDirectory))
            put("modified_time_ms", JsonPrimitive(time))
            if (isLink) put("link_name", JsonPrimitive(linkName ?: ""))
        }
    }

    private fun addTarFile(
        tout: TarArchiveOutputStream,
        f: File,
        entryName: String,
        bufSize: Int,
    ): Int {
        val n = entryName.replace('\\', '/').trimStart('/')
        if (n.isEmpty()) return 0
        if (isUnsafeArchiveEntryName(n)) return 0

        val e = TarArchiveEntry(n)
        e.size = f.length()
        val lm = f.lastModified()
        if (lm > 0L) e.modTime = Date(lm)
        tout.putArchiveEntry(e)
        val buf = ByteArray(bufSize)
        f.inputStream().use { input ->
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                tout.write(buf, 0, r)
            }
        }
        tout.closeArchiveEntry()
        return 1
    }

    private fun drainEntry(
        tin: TarArchiveInputStream,
        entry: TarArchiveEntry,
        buf: ByteArray,
    ) {
        if (entry.isDirectory) return
        var remaining = if (entry.size >= 0) entry.size else 0L
        if (remaining <= 0L) {
            // Best-effort: read until EOF for this entry.
            while (true) {
                val n = tin.read(buf)
                if (n <= 0) break
            }
            return
        }
        while (remaining > 0) {
            val want = minOf(buf.size.toLong(), remaining).toInt()
            val n = tin.read(buf, 0, want)
            if (n <= 0) break
            remaining -= n.toLong()
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
        format: String,
        filesWritten: Int,
        dirsCreated: Int,
        bytesWritten: Long,
        skippedExisting: Int,
        skippedUnsafePath: Int,
        skippedUnsafeLink: Int,
        skippedTooLarge: Int,
        message: String,
    ): TerminalCommandOutput {
        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(false))
                put("command", JsonPrimitive("tar extract"))
                put("in", JsonPrimitive(inRel))
                put("dest", JsonPrimitive(destRel))
                put("format", JsonPrimitive(format))
                put("files_written", JsonPrimitive(filesWritten))
                put("dirs_created", JsonPrimitive(dirsCreated))
                put("bytes_written", JsonPrimitive(bytesWritten))
                put(
                    "skipped",
                    buildJsonObject {
                        put("existing", JsonPrimitive(skippedExisting))
                        put("unsafe_path", JsonPrimitive(skippedUnsafePath))
                        put("unsafe_link", JsonPrimitive(skippedUnsafeLink))
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

