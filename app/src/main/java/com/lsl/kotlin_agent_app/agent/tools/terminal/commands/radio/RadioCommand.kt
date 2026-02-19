package com.lsl.kotlin_agent_app.agent.tools.terminal.commands.radio

import android.content.Context
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommandOutput
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.requireFlagValue
import com.lsl.kotlin_agent_app.media.MusicPlaybackState
import com.lsl.kotlin_agent_app.media.MusicPlayerControllerProvider
import com.lsl.kotlin_agent_app.radios.RadioStationFileV1
import com.lsl.kotlin_agent_app.radios.RadioPathNaming
import java.io.File
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal class NotInRadiosDir(
    message: String,
) : IllegalArgumentException(message)

internal class NotRadioFile(
    message: String,
) : IllegalArgumentException(message)

internal class NotFound(
    message: String,
) : IllegalArgumentException(message)

internal class InvalidRadio(
    message: String,
) : IllegalArgumentException(message)

internal class NotPlayingRadio(
    message: String,
) : IllegalArgumentException(message)

internal class RadioCommand(
    appContext: Context,
) : TerminalCommand {
    private val ctx = appContext.applicationContext
    private val ws = AgentsWorkspace(ctx)

    override val name: String = "radio"
    override val description: String = "Radio player control plane (status/play/pause/resume/stop)."

    init {
        // Ensure CLI works even when Files UI hasn't been opened.
        MusicPlayerControllerProvider.installAppContext(ctx)
    }

    override suspend fun run(
        argv: List<String>,
        stdin: String?,
    ): TerminalCommandOutput {
        if (argv.size < 2) return help(sub = null)

        val subRaw = argv[1].trim()
        val sub = subRaw.lowercase()

        val helpRequested = argv.any { it == "--help" } || sub == "help"
        if (helpRequested) {
            val targetSub =
                when {
                    sub == "help" -> argv.getOrNull(2)?.trim()?.lowercase()
                    sub == "--help" -> null
                    else -> sub
                }
            return help(sub = targetSub)
        }

        return try {
            when (sub) {
                "status" -> handleStatus()
                "play" -> handlePlay(argv)
                "pause" -> handlePause()
                "resume" -> handleResume()
                "stop" -> handleStop()
                "fav" -> handleFav(argv)
                else -> invalidArgs("unknown subcommand: $subRaw")
            }
        } catch (t: NotInRadiosDir) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "not in radios dir"),
                errorCode = "NotInRadiosDir",
                errorMessage = t.message,
            )
        } catch (t: NotRadioFile) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "not a .radio file"),
                errorCode = "NotRadioFile",
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
        } catch (t: InvalidRadio) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "invalid .radio"),
                errorCode = "InvalidRadio",
                errorMessage = t.message,
            )
        } catch (t: NotPlayingRadio) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "not playing radio"),
                errorCode = "NotPlayingRadio",
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
        val isRadio = st.isLive && !st.agentsPath.isNullOrBlank() && st.agentsPath.endsWith(".radio", ignoreCase = true) && isInRadiosTree(st.agentsPath)

        val stationObj =
            if (!isRadio) {
                JsonNull
            } else {
                val agentsPath = st.agentsPath ?: ""
                val workspacePath = toWorkspacePath(agentsPath)
                val parsed =
                    runCatching {
                        val f = File(ctx.filesDir, agentsPath)
                        if (!f.exists() || !f.isFile) return@runCatching null
                        RadioStationFileV1.parse(f.readText(Charsets.UTF_8))
                    }.getOrNull()

                buildJsonObject {
                    put("path", JsonPrimitive(workspacePath))
                    put("id", parsed?.id?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("name", parsed?.name?.let { JsonPrimitive(it) } ?: (st.title?.let { JsonPrimitive(it) } ?: JsonNull))
                    put("country", parsed?.country?.let { JsonPrimitive(it) } ?: (st.artist?.let { JsonPrimitive(it) } ?: JsonNull))
                    put("favicon_url", parsed?.faviconUrl?.let { JsonPrimitive(it) } ?: JsonNull)
                }
            }

        val state =
            when {
                st.playbackState == MusicPlaybackState.Stopped -> "stopped"
                st.playbackState == MusicPlaybackState.Error -> "error"
                !isRadio -> "idle"
                else -> st.playbackState.toCliState()
            }

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("radio status"))
                put("state", JsonPrimitive(state))
                put("station", stationObj)
                put("position_ms", JsonPrimitive(st.positionMs))
                put("warning_message", st.warningMessage?.let { JsonPrimitive(it) } ?: JsonNull)
                put("error_message", st.errorMessage?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        return TerminalCommandOutput(exitCode = 0, stdout = "radio: $state", result = result)
    }

    private suspend fun handlePlay(argv: List<String>): TerminalCommandOutput {
        val rawIn = requireFlagValue(argv, "--in").trim()
        val agentsPath = normalizeAgentsPathArg(rawIn)
        if (!isInRadiosTree(agentsPath)) throw NotInRadiosDir("仅允许 radios/ 目录下的 .radio：$rawIn")
        if (!agentsPath.lowercase().endsWith(".radio")) throw NotRadioFile("仅允许 radios/ 目录下的 .radio：$rawIn")

        val f = File(ctx.filesDir, agentsPath)
        if (!f.exists() || !f.isFile) throw NotFound("文件不存在：$rawIn")

        val ctrl = MusicPlayerControllerProvider.get()
        try {
            ctrl.playAgentsRadioNow(agentsPath)
        } catch (t: Throwable) {
            val msg = t.message.orEmpty()
            when {
                msg.contains("invalid .radio", ignoreCase = true) -> throw InvalidRadio("非法 .radio：$rawIn")
                msg.contains("not a file", ignoreCase = true) -> throw NotFound("文件不存在：$rawIn")
                msg.contains("not found", ignoreCase = true) -> throw NotFound("文件不存在：$rawIn")
                msg.contains("path not allowed", ignoreCase = true) -> throw NotInRadiosDir("仅允许 radios/ 目录下的 .radio：$rawIn")
                else -> throw IllegalArgumentException(t.message ?: "播放失败")
            }
        }

        return handleStatus().copy(stdout = "playing: ${rawIn.trim()}")
    }

    private suspend fun handleFav(argv: List<String>): TerminalCommandOutput {
        if (argv.size < 3) return invalidArgs("missing subcommand: fav add|rm|list")
        when (val sub = argv[2].trim().lowercase()) {
            "add" -> return handleFavAdd(argv)
            "rm", "remove" -> return handleFavRemove(argv)
            "list" -> return handleFavList()
            else -> return invalidArgs("unknown fav subcommand: $sub")
        }
    }

    private fun optionalFlagValue(
        argv: List<String>,
        flag: String,
    ): String? {
        val idx = argv.indexOf(flag)
        if (idx < 0) return null
        return argv.getOrNull(idx + 1)?.trim()?.ifBlank { null }
    }

    private suspend fun handleFavAdd(argv: List<String>): TerminalCommandOutput {
        ws.ensureInitialized()
        val rawIn = optionalFlagValue(argv, "--in")
        val agentsPath =
            if (rawIn.isNullOrBlank()) {
                val st = MusicPlayerControllerProvider.get().statusSnapshot()
                val p = st.agentsPath?.trim().orEmpty()
                val isRadio = st.isLive && p.endsWith(".radio", ignoreCase = true) && isInRadiosTree(p)
                if (!isRadio) throw NotPlayingRadio("no active radio playback")
                p
            } else {
                val p = normalizeAgentsPathArg(rawIn)
                if (!isInRadiosTree(p)) throw NotInRadiosDir("仅允许 radios/ 目录下的 .radio：$rawIn")
                if (!p.lowercase().endsWith(".radio")) throw NotRadioFile("仅允许 radios/ 目录下的 .radio：$rawIn")
                p
            }

        val file = File(ctx.filesDir, agentsPath)
        if (!file.exists() || !file.isFile) throw NotFound("文件不存在：${rawIn ?: toWorkspacePath(agentsPath)}")
        val raw = file.readText(Charsets.UTF_8)
        val station =
            try {
                RadioStationFileV1.parse(raw)
            } catch (_: Throwable) {
                throw InvalidRadio("非法 .radio：${rawIn ?: toWorkspacePath(agentsPath)}")
            }

        val uuid = station.id.substringAfter(':', missingDelimiterValue = station.id)
        val fileName = RadioPathNaming.stationFileName(stationName = station.name, stationUuid = uuid)
        val dest = ".agents/workspace/radios/favorites/$fileName"
        val destFile = File(ctx.filesDir, dest)
        if (!destFile.exists()) {
            destFile.parentFile?.mkdirs()
            destFile.writeText(raw.trimEnd() + "\n", Charsets.UTF_8)
        }

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("radio fav add"))
                put("favorite_path", JsonPrimitive(toWorkspacePath(dest)))
                put(
                    "station",
                    buildJsonObject {
                        put("id", JsonPrimitive(station.id))
                        put("name", JsonPrimitive(station.name))
                        put("path", JsonPrimitive(toWorkspacePath(dest)))
                    },
                )
            }
        return TerminalCommandOutput(exitCode = 0, stdout = "favorited: ${station.name}", result = result)
    }

    private suspend fun handleFavRemove(argv: List<String>): TerminalCommandOutput {
        ws.ensureInitialized()
        val rawIn = optionalFlagValue(argv, "--in")
        val agentsPath =
            if (rawIn.isNullOrBlank()) {
                val st = MusicPlayerControllerProvider.get().statusSnapshot()
                val p = st.agentsPath?.trim().orEmpty()
                val isRadio = st.isLive && p.endsWith(".radio", ignoreCase = true) && isInRadiosTree(p)
                if (!isRadio) throw NotPlayingRadio("no active radio playback")
                p
            } else {
                val p = normalizeAgentsPathArg(rawIn)
                if (!isInRadiosTree(p)) throw NotInRadiosDir("仅允许 radios/ 目录下的 .radio：$rawIn")
                if (!p.lowercase().endsWith(".radio")) throw NotRadioFile("仅允许 radios/ 目录下的 .radio：$rawIn")
                p
            }

        val file = File(ctx.filesDir, agentsPath)
        if (!file.exists() || !file.isFile) throw NotFound("文件不存在：${rawIn ?: toWorkspacePath(agentsPath)}")
        val raw = file.readText(Charsets.UTF_8)
        val station =
            try {
                RadioStationFileV1.parse(raw)
            } catch (_: Throwable) {
                throw InvalidRadio("非法 .radio：${rawIn ?: toWorkspacePath(agentsPath)}")
            }

        val uuid = station.id.substringAfter(':', missingDelimiterValue = station.id)
        val fileName = RadioPathNaming.stationFileName(stationName = station.name, stationUuid = uuid)
        val dest = ".agents/workspace/radios/favorites/$fileName"
        val destFile = File(ctx.filesDir, dest)
        if (!destFile.exists()) throw NotFound("不在收藏：${station.name}")
        destFile.delete()

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("radio fav rm"))
                put("removed_path", JsonPrimitive(toWorkspacePath(dest)))
                put(
                    "station",
                    buildJsonObject {
                        put("id", JsonPrimitive(station.id))
                        put("name", JsonPrimitive(station.name))
                    },
                )
            }
        return TerminalCommandOutput(exitCode = 0, stdout = "unfavorited: ${station.name}", result = result)
    }

    private suspend fun handleFavList(): TerminalCommandOutput {
        ws.ensureInitialized()
        val dir = File(ctx.filesDir, ".agents/workspace/radios/favorites")
        val files =
            dir.listFiles()
                .orEmpty()
                .filter { it.isFile && it.name.lowercase().endsWith(".radio") }
                .sortedBy { it.name.lowercase() }

        val favorites =
            buildJsonArray {
                for (f in files) {
                    val parsed =
                        runCatching {
                            RadioStationFileV1.parse(f.readText(Charsets.UTF_8))
                        }.getOrNull()
                    add(
                        buildJsonObject {
                            put("path", JsonPrimitive(toWorkspacePath(".agents/workspace/radios/favorites/" + f.name)))
                            put("id", parsed?.id?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("name", parsed?.name?.let { JsonPrimitive(it) } ?: JsonPrimitive(f.name))
                            put("country", parsed?.country?.let { JsonPrimitive(it) } ?: JsonNull)
                        },
                    )
                }
            }

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("radio fav list"))
                put("count_total", JsonPrimitive(files.size))
                put("favorites", favorites)
            }
        return TerminalCommandOutput(exitCode = 0, stdout = "favorites: ${files.size}", result = result)
    }

    private suspend fun handlePause(): TerminalCommandOutput {
        ensurePlayingRadio()
        MusicPlayerControllerProvider.get().pauseNow()
        return handleStatus().copy(stdout = "paused")
    }

    private suspend fun handleResume(): TerminalCommandOutput {
        ensurePlayingRadio()
        MusicPlayerControllerProvider.get().resumeNow()
        return handleStatus().copy(stdout = "resumed")
    }

    private suspend fun handleStop(): TerminalCommandOutput {
        ensurePlayingRadio()
        MusicPlayerControllerProvider.get().stopNow()
        return handleStatus().copy(stdout = "stopped")
    }

    private suspend fun ensurePlayingRadio() {
        val st = MusicPlayerControllerProvider.get().statusSnapshot()
        val isRadio = st.isLive && !st.agentsPath.isNullOrBlank() && st.agentsPath.endsWith(".radio", ignoreCase = true) && isInRadiosTree(st.agentsPath)
        if (!isRadio) throw NotPlayingRadio("no active radio playback")
    }

    private fun help(sub: String?): TerminalCommandOutput {
        val (usage, examples) =
            when (sub) {
                null -> {
                    val u =
                        """
                        Usage:
                          radio status
                          radio play --in <agents-path>
                          radio pause
                          radio resume
                          radio stop
                          radio fav add [--in <agents-path>]
                          radio fav rm [--in <agents-path>]
                          radio fav list
                        
                        Help:
                          radio --help
                          radio help
                          radio <sub> --help
                          radio help <sub>
                        """.trimIndent()
                    u to listOf(
                        "radio status",
                        "radio play --in workspace/radios/demo.radio",
                        "radio pause",
                        "radio resume",
                        "radio stop",
                        "radio fav add",
                        "radio fav list",
                    )
                }
                "status" -> "Usage: radio status" to listOf("radio status")
                "play" -> "Usage: radio play --in <agents-path>" to listOf("radio play --in workspace/radios/demo.radio")
                "pause" -> "Usage: radio pause" to listOf("radio pause")
                "resume" -> "Usage: radio resume" to listOf("radio resume")
                "stop" -> "Usage: radio stop" to listOf("radio stop")
                "fav" -> {
                    val u =
                        """
                        Usage:
                          radio fav add [--in <agents-path>]
                          radio fav rm [--in <agents-path>]
                          radio fav list
                        """.trimIndent()
                    u to listOf("radio fav list", "radio fav add", "radio fav rm")
                }
                else -> return invalidArgs("unknown subcommand: $sub")
            }

        val stdout = usage.trim()
        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive(if (sub == null) "radio help" else "radio $sub help"))
                put("usage", JsonPrimitive(stdout))
                put(
                    "subcommands",
                    buildJsonArray {
                        add(JsonPrimitive("status"))
                        add(JsonPrimitive("play"))
                        add(JsonPrimitive("pause"))
                        add(JsonPrimitive("resume"))
                        add(JsonPrimitive("stop"))
                        add(JsonPrimitive("fav"))
                    },
                )
                if (sub == null || sub == "play") {
                    put(
                        "flags",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("name", JsonPrimitive("--in"))
                                    put("required", JsonPrimitive(true))
                                    put("description", JsonPrimitive("Input .radio file within workspace/radios/"))
                                },
                            )
                        },
                    )
                } else if (sub == "fav") {
                    put(
                        "flags",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("name", JsonPrimitive("--in"))
                                    put("required", JsonPrimitive(false))
                                    put("description", JsonPrimitive("Optional input .radio path within workspace/radios/ (defaults to current playing station)."))
                                },
                            )
                        },
                    )
                } else {
                    put("flags", buildJsonArray { })
                }
                put(
                    "examples",
                    buildJsonArray {
                        for (e in examples) add(JsonPrimitive(e))
                    },
                )
            }
        return TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
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

    private fun normalizeAgentsPathArg(raw: String): String {
        val p0 = raw.replace('\\', '/').trim().trimStart('/')
        return when {
            p0.startsWith(".agents/") -> p0
            p0.startsWith("workspace/") -> ".agents/$p0"
            p0.startsWith("radios/") -> ".agents/workspace/$p0"
            else -> p0
        }.trimEnd('/')
    }

    private fun isInRadiosTree(agentsPath: String): Boolean {
        val p = agentsPath.replace('\\', '/').trim().trimStart('/').trimEnd('/')
        return p == ".agents/workspace/radios" || p.startsWith(".agents/workspace/radios/")
    }

    private fun toWorkspacePath(agentsPath: String): String {
        val p = agentsPath.replace('\\', '/').trim().trimStart('/').trimEnd('/')
        return p.removePrefix(".agents/").trimStart('/')
    }
}
