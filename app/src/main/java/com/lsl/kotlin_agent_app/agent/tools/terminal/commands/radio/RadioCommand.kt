package com.lsl.kotlin_agent_app.agent.tools.terminal.commands.radio

import android.content.Context
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommandOutput
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.requireFlagValue
import com.lsl.kotlin_agent_app.media.MusicPlaybackState
import com.lsl.kotlin_agent_app.media.MusicPlayerControllerProvider
import com.lsl.kotlin_agent_app.radios.RadioStationFileV1
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
                    )
                }
                "status" -> "Usage: radio status" to listOf("radio status")
                "play" -> "Usage: radio play --in <agents-path>" to listOf("radio play --in workspace/radios/demo.radio")
                "pause" -> "Usage: radio pause" to listOf("radio pause")
                "resume" -> "Usage: radio resume" to listOf("radio resume")
                "stop" -> "Usage: radio stop" to listOf("radio stop")
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
