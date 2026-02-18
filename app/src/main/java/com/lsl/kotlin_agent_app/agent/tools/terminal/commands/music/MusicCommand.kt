package com.lsl.kotlin_agent_app.agent.tools.terminal.commands.music

import android.content.Context
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommandOutput
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.ConfirmRequired
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.hasFlag
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.optionalFlagValue
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.requireFlagValue
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.resolveWithinAgents
import com.lsl.kotlin_agent_app.media.MusicPlaybackState
import com.lsl.kotlin_agent_app.media.MusicPlayerControllerProvider
import com.lsl.kotlin_agent_app.media.id3.Id3UpdateRequest
import com.lsl.kotlin_agent_app.media.id3.Id3v2TagEditor
import java.io.File
import java.util.Locale
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal class PathNotAllowed(
    message: String,
) : IllegalArgumentException(message)

internal class NotFound(
    message: String,
) : IllegalArgumentException(message)

internal class InvalidMp3(
    message: String,
) : IllegalArgumentException(message)

internal class WriteFailed(
    message: String,
) : IllegalArgumentException(message)

internal class MusicCommand(
    appContext: Context,
) : TerminalCommand {
    private val ctx = appContext.applicationContext
    private val agentsRoot = File(ctx.filesDir, ".agents").canonicalFile

    override val name: String = "music"
    override val description: String = "Music player control plane (status/play/pause/resume/stop/seek/next/prev) + mp3 metadata get/set."

    init {
        // Ensure CLI works even when Files UI hasn't been opened.
        MusicPlayerControllerProvider.installAppContext(ctx)
    }

    override suspend fun run(
        argv: List<String>,
        stdin: String?,
    ): TerminalCommandOutput {
        if (argv.size < 2) return invalidArgs("missing subcommand")
        val sub = argv[1].lowercase()

        return try {
            when (sub) {
                "status" -> handleStatus()
                "play" -> handlePlay(argv)
                "pause" -> handlePause()
                "resume" -> handleResume()
                "stop" -> handleStop()
                "seek" -> handleSeek(argv)
                "next" -> handleNext()
                "prev" -> handlePrev()
                "meta" -> handleMeta(argv)
                else -> invalidArgs("unknown subcommand: ${argv[1]}")
            }
        } catch (t: ConfirmRequired) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "missing --confirm"),
                errorCode = "ConfirmRequired",
                errorMessage = t.message,
            )
        } catch (t: PathNotAllowed) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "path not allowed"),
                errorCode = "PathNotAllowed",
                errorMessage = t.message,
            )
        } catch (t: NotFound) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "not found"),
                errorCode = "NotFound",
                errorMessage = t.message,
            )
        } catch (t: InvalidMp3) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "invalid mp3"),
                errorCode = "InvalidMp3",
                errorMessage = t.message,
            )
        } catch (t: WriteFailed) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "write failed"),
                errorCode = "WriteFailed",
                errorMessage = t.message,
            )
        } catch (t: IllegalArgumentException) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "invalid args"),
                errorCode = "InvalidArgs",
                errorMessage = t.message,
            )
        } catch (t: Throwable) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "error"),
                errorCode = "Error",
                errorMessage = t.message,
            )
        }
    }

    private suspend fun handleStatus(): TerminalCommandOutput {
        val ctrl = MusicPlayerControllerProvider.get()
        val st = ctrl.statusSnapshot()
        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("music status"))
                put("state", JsonPrimitive(st.playbackState.toCliState()))
                if (!st.agentsPath.isNullOrBlank()) {
                    put(
                        "track",
                        buildJsonObject {
                            put("path", JsonPrimitive(st.agentsPath.toCliPath()))
                            put("title", st.title?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("artist", st.artist?.let { JsonPrimitive(it) } ?: JsonNull)
                        },
                    )
                } else {
                    put("track", JsonNull)
                }
                put("position_ms", JsonPrimitive(st.positionMs))
                put("duration_ms", st.durationMs?.let { JsonPrimitive(it) } ?: JsonNull)
                put("queue_index", st.queueIndex?.let { JsonPrimitive(it) } ?: JsonNull)
                put("queue_size", st.queueSize?.let { JsonPrimitive(it) } ?: JsonPrimitive(0))
                put("warning_message", st.warningMessage?.let { JsonPrimitive(it) } ?: JsonNull)
                put("error_message", st.errorMessage?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        return TerminalCommandOutput(exitCode = 0, stdout = "music: ${st.playbackState.toCliState()}", result = result)
    }

    private suspend fun handlePlay(argv: List<String>): TerminalCommandOutput {
        val rawIn = requireFlagValue(argv, "--in").trim()
        val agentsPath = normalizeAgentsPathArg(rawIn)
        if (!isInMusicsTree(agentsPath)) throw PathNotAllowed("仅允许 musics/ 目录下的 mp3：$rawIn")

        val ctrl = MusicPlayerControllerProvider.get()
        try {
            ctrl.playAgentsMp3Now(agentsPath)
        } catch (t: Throwable) {
            val msg = t.message.orEmpty()
            when {
                msg.contains("not found", ignoreCase = true) -> throw NotFound("文件不存在：$rawIn")
                msg.contains("invalid mp3", ignoreCase = true) -> throw InvalidMp3("非法 mp3：$rawIn")
                msg.contains("path not allowed", ignoreCase = true) -> throw PathNotAllowed("仅允许 musics/ 目录下的 mp3：$rawIn")
                else -> throw IllegalArgumentException(t.message ?: "播放失败")
            }
        }
        return handleStatus().copy(stdout = "playing: ${rawIn.trim()}")
    }

    private suspend fun handlePause(): TerminalCommandOutput {
        MusicPlayerControllerProvider.get().pauseNow()
        return handleStatus().copy(stdout = "paused")
    }

    private suspend fun handleResume(): TerminalCommandOutput {
        MusicPlayerControllerProvider.get().resumeNow()
        return handleStatus().copy(stdout = "resumed")
    }

    private suspend fun handleStop(): TerminalCommandOutput {
        MusicPlayerControllerProvider.get().stopNow()
        return handleStatus().copy(stdout = "stopped")
    }

    private suspend fun handleSeek(argv: List<String>): TerminalCommandOutput {
        val raw = requireFlagValue(argv, "--to-ms").trim()
        val ms = raw.toLongOrNull() ?: throw IllegalArgumentException("invalid --to-ms: $raw")
        MusicPlayerControllerProvider.get().seekToNow(ms)
        return handleStatus().copy(stdout = "seeked")
    }

    private suspend fun handleNext(): TerminalCommandOutput {
        MusicPlayerControllerProvider.get().nextNow()
        return handleStatus().copy(stdout = "next")
    }

    private suspend fun handlePrev(): TerminalCommandOutput {
        MusicPlayerControllerProvider.get().prevNow()
        return handleStatus().copy(stdout = "prev")
    }

    private suspend fun handleMeta(argv: List<String>): TerminalCommandOutput {
        if (argv.size < 3) return invalidArgs("missing meta subcommand")
        val sub = argv[2].lowercase()
        return when (sub) {
            "get" -> metaGet(argv)
            "set" -> metaSet(argv)
            else -> invalidArgs("unknown meta subcommand: ${argv[2]}")
        }
    }

    private suspend fun metaGet(argv: List<String>): TerminalCommandOutput {
        val rawIn = requireFlagValue(argv, "--in").trim()
        val agentsPath = normalizeAgentsPathArg(rawIn)
        if (!isInMusicsTree(agentsPath)) throw PathNotAllowed("仅允许 musics/ 目录下的 mp3：$rawIn")
        val rel = agentsPath.removePrefix(".agents/").trimStart('/')
        val file = resolveWithinAgents(agentsRoot, rel)
        if (!file.exists() || !file.isFile) throw NotFound("文件不存在：$rawIn")
        if (!Id3v2TagEditor.looksLikeMp3OrId3(file)) throw InvalidMp3("非法 mp3：$rawIn")

        val md = Id3v2TagEditor.readMetadata(file)
        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("music meta get"))
                put("in", JsonPrimitive(rawIn))
                put("tag_version", md.tagVersion?.let { JsonPrimitive(it) } ?: JsonNull)
                put(
                    "metadata",
                    buildJsonObject {
                        put("title", md.title?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("artist", md.artist?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("album", md.album?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("album_artist", md.albumArtist?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("track_number", md.trackNumber?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("disc_number", md.discNumber?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("year", md.year?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("date", md.date?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("genre", md.genre?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("comment", md.comment?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("composer", md.composer?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("lyricist", md.lyricist?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("lyrics", md.lyrics?.let { JsonPrimitive(it) } ?: JsonNull)
                        put(
                            "cover_art",
                            md.coverArt?.let { ca ->
                                buildJsonObject {
                                    put("mime", JsonPrimitive(ca.mime))
                                    put("bytes_size", JsonPrimitive(ca.bytesSize))
                                }
                            } ?: JsonNull,
                        )
                    },
                )
            }
        return TerminalCommandOutput(exitCode = 0, stdout = "ok", result = result)
    }

    private suspend fun metaSet(argv: List<String>): TerminalCommandOutput {
        if (!hasFlag(argv, "--confirm")) throw ConfirmRequired("missing --confirm")
        val rawIn = requireFlagValue(argv, "--in").trim()
        val agentsPath = normalizeAgentsPathArg(rawIn)
        if (!isInMusicsTree(agentsPath)) throw PathNotAllowed("仅允许 musics/ 目录下的 mp3：$rawIn")
        val rel = agentsPath.removePrefix(".agents/").trimStart('/')
        val file = resolveWithinAgents(agentsRoot, rel)
        if (!file.exists() || !file.isFile) throw NotFound("文件不存在：$rawIn")
        if (!Id3v2TagEditor.looksLikeMp3OrId3(file)) throw InvalidMp3("非法 mp3：$rawIn")

        val title = optionalFlagValue(argv, "--title")
        val artist = optionalFlagValue(argv, "--artist")
        val album = optionalFlagValue(argv, "--album")
        val albumArtist = optionalFlagValue(argv, "--album-artist")
        val trackNumber = optionalFlagValue(argv, "--track-number")
        val discNumber = optionalFlagValue(argv, "--disc-number")
        val year = optionalFlagValue(argv, "--year")
        val date = optionalFlagValue(argv, "--date")
        val genre = optionalFlagValue(argv, "--genre")
        val comment = optionalFlagValue(argv, "--comment")
        val composer = optionalFlagValue(argv, "--composer")
        val lyricist = optionalFlagValue(argv, "--lyricist")
        val lyrics = optionalFlagValue(argv, "--lyrics")
        val coverArtPath = optionalFlagValue(argv, "--cover-art")

        val (coverArtBytes, coverArtMime) =
            if (coverArtPath.isNullOrBlank()) {
                null to null
            } else {
                val caRel = normalizeAgentsPathArg(coverArtPath).removePrefix(".agents/").trimStart('/')
                val caFile = resolveWithinAgents(agentsRoot, caRel)
                if (!caFile.exists() || !caFile.isFile) throw NotFound("cover art not found: $coverArtPath")
                val bytes = caFile.readBytes().takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("empty cover art: $coverArtPath")
                val mime = guessImageMimeOrNull(caFile) ?: "application/octet-stream"
                bytes to mime
            }

        val update =
            Id3UpdateRequest(
                title = title,
                artist = artist,
                album = album,
                albumArtist = albumArtist,
                trackNumber = trackNumber,
                discNumber = discNumber,
                year = year,
                date = date,
                genre = genre,
                comment = comment,
                composer = composer,
                lyricist = lyricist,
                lyrics = lyrics,
                coverArtBytes = coverArtBytes,
                coverArtMime = coverArtMime,
            )

        val wr =
            try {
                Id3v2TagEditor.writeMetadataAtomic(file, update)
            } catch (t: Throwable) {
                throw WriteFailed(t.message ?: "write failed")
            }

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("music meta set"))
                put("in", JsonPrimitive(rawIn))
                put("tag_version_before", wr.tagVersionBefore?.let { JsonPrimitive(it) } ?: JsonNull)
                put("tag_version_after", wr.tagVersionAfter?.let { JsonPrimitive(it) } ?: JsonNull)
                put(
                    "before",
                    buildJsonObject {
                        put("title", wr.before.title?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("artist", wr.before.artist?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("lyrics", wr.before.lyrics?.let { JsonPrimitive(it) } ?: JsonNull)
                    },
                )
                put(
                    "after",
                    buildJsonObject {
                        put("title", wr.after.title?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("artist", wr.after.artist?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("lyrics", wr.after.lyrics?.let { JsonPrimitive(it) } ?: JsonNull)
                    },
                )
                put(
                    "applied",
                    buildJsonObject {
                        put("title", title?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("artist", artist?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("lyrics", lyrics?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("cover_art", coverArtPath?.let { JsonPrimitive(it) } ?: JsonNull)
                    },
                )
            }
        return TerminalCommandOutput(exitCode = 0, stdout = "ok", result = result)
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

    private fun MusicPlaybackState.toCliState(): String {
        return when (this) {
            MusicPlaybackState.Idle -> "idle"
            MusicPlaybackState.Playing -> "playing"
            MusicPlaybackState.Paused -> "paused"
            MusicPlaybackState.Stopped -> "stopped"
            MusicPlaybackState.Error -> "error"
        }
    }

    private fun String?.toCliPath(): String {
        val p = (this ?: "").replace('\\', '/').trim().trimStart('/')
        return p.removePrefix(".agents/").trimStart('/')
    }

    private fun normalizeAgentsPathArg(raw: String): String {
        val p0 = raw.replace('\\', '/').trim().trimStart('/')
        return when {
            p0.startsWith(".agents/") -> p0
            p0.startsWith("workspace/") -> ".agents/$p0"
            p0.startsWith("musics/") -> ".agents/workspace/$p0"
            else -> p0
        }.trimEnd('/')
    }

    private fun isInMusicsTree(agentsPath: String): Boolean {
        val p = agentsPath.replace('\\', '/').trim().trimStart('/').trimEnd('/')
        return p == ".agents/workspace/musics" || p.startsWith(".agents/workspace/musics/")
    }

    private fun guessImageMimeOrNull(file: File): String? {
        val name = file.name.lowercase(Locale.US)
        return when {
            name.endsWith(".png") -> "image/png"
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".webp") -> "image/webp"
            else -> null
        }
    }
}
