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
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

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
            invalidArgs(t.message ?: "invalid args")
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
        val outRel = optionalFlagValue(argv, "--out")?.trim()?.takeIf { it.isNotBlank() }
        val outFile = outRel?.let { resolveWithinAgents(agentsRoot, it) }

        val entries = mutableListOf<JsonElement>()
        val entriesAll = mutableListOf<JsonElement>()
        var total = 0

        ZipFile(zipFile).use { zf ->
            val it = zf.entries()
            while (it.hasMoreElements()) {
                val e = it.nextElement()
                total += 1
                val el = zipEntryJson(e)
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

        val maxFiles = parseIntFlag(argv, "--max-files", defaultValue = 2000).coerceAtLeast(0)
        val maxBytes = parseLongFlag(argv, "--max-bytes", defaultValue = 512L * 1024L * 1024L).coerceAtLeast(0L)

        var filesWritten = 0
        var dirsCreated = 0
        var bytesWritten = 0L
        var skippedExisting = 0
        var skippedUnsafePath = 0
        var skippedTooLarge = 0

        ZipFile(zipFile).use { zf ->
            val it = zf.entries()
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

    private fun zipEntryJson(e: ZipEntry): JsonElement {
        val n = e.name.orEmpty()
        val size = e.size
        val csize = e.compressedSize
        val time = e.time
        return buildJsonObject {
            put("name", JsonPrimitive(n))
            put("compressed_bytes", JsonPrimitive(csize))
            put("uncompressed_bytes", JsonPrimitive(size))
            put("is_dir", JsonPrimitive(e.isDirectory))
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

