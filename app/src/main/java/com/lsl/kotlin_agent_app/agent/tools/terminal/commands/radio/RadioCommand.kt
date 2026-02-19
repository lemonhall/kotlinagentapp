package com.lsl.kotlin_agent_app.agent.tools.terminal.commands.radio

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommandOutput
import com.lsl.kotlin_agent_app.media.MusicPlaybackState
import com.lsl.kotlin_agent_app.media.MusicPlayerControllerProvider
import com.lsl.kotlin_agent_app.radios.RadioRepository
import com.lsl.kotlin_agent_app.radios.RadioStationFileV1
import com.lsl.kotlin_agent_app.radios.RadioPathNaming
import com.lsl.kotlin_agent_app.radios.StreamResolutionClassification
import com.lsl.kotlin_agent_app.radios.StreamUrlResolver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

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
    private val radioRepo = RadioRepository(ws)
    private val streamUrlResolver = StreamUrlResolver()

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
                "sync" -> handleSync(argv)
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

    private suspend fun handleSync(argv: List<String>): TerminalCommandOutput {
        val target = argv.getOrNull(2)?.trim()?.lowercase().orEmpty()
        val force = argv.any { it.trim().equals("--force", ignoreCase = true) }
        return when (target) {
            "countries" -> handleSyncCountries(force = force)
            "stations" -> handleSyncStations(argv, force = force)
            else -> invalidArgs("Usage: radio sync (countries|stations) [--force]")
        }
    }

    private suspend fun handleSyncCountries(force: Boolean): TerminalCommandOutput {
        ws.ensureInitialized()
        val out = radioRepo.syncCountries(force = force)
        if (!out.ok) {
            return TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (out.message ?: "sync countries failed"),
                errorCode = "SyncFailed",
                errorMessage = out.message,
            )
        }

        val idx = radioRepo.readCountriesIndexOrNull()
        val count = idx?.countries?.size ?: 0
        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("radio sync countries" + if (force) " --force" else ""))
                put("force", JsonPrimitive(force))
                put("countries_count", JsonPrimitive(count))
            }
        return TerminalCommandOutput(exitCode = 0, stdout = "synced countries: $count", result = result)
    }

    private suspend fun handleSyncStations(
        argv: List<String>,
        force: Boolean,
    ): TerminalCommandOutput {
        ws.ensureInitialized()

        // Ensure countries index exists (needed for --cc and for directory validation).
        val countries = radioRepo.syncCountries(force = false)
        if (!countries.ok) {
            return TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (countries.message ?: "sync countries failed"),
                errorCode = "SyncCountriesFailed",
                errorMessage = countries.message,
            )
        }

        val rawDir = optionalFlagValueRest(argv, "--dir")
        val rawCc = optionalFlagValueRest(argv, "--cc")
        if (rawDir.isNullOrBlank() && rawCc.isNullOrBlank()) {
            return invalidArgs("Usage: radio sync stations (--dir <country-dir> | --cc <CC>) [--force]")
        }
        if (!rawDir.isNullOrBlank() && !rawCc.isNullOrBlank()) {
            return invalidArgs("radio sync stations: provide only one of --dir or --cc")
        }

        val dir =
            if (!rawDir.isNullOrBlank()) {
                rawDir.trim()
            } else {
                val cc = rawCc!!.trim().uppercase()
                if (!Regex("^[A-Z]{2}$").matches(cc)) {
                    return invalidArgs("radio sync stations: invalid --cc (expected ISO 3166-1 alpha-2, e.g. EG)")
                }
                val idx = radioRepo.readCountriesIndexOrNull()
                val entry = idx?.countries?.firstOrNull { (it.code ?: "").trim().uppercase() == cc }
                if (entry == null) {
                    return TerminalCommandOutput(
                        exitCode = 2,
                        stdout = "",
                        stderr = "unknown country code: $cc",
                        errorCode = "CountryNotFound",
                        errorMessage = "unknown country code: $cc",
                    )
                }
                entry.dir
            }

        val out = radioRepo.syncStationsForCountryDir(countryDirName = dir, force = force)
        if (!out.ok) {
            return TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (out.message ?: "sync stations failed"),
                errorCode = "SyncFailed",
                errorMessage = out.message,
            )
        }

        val stationsIndexPath = "workspace/radios/$dir/.stations.index.json"
        val radiosCount =
            runCatching {
                ws.listDir("${RadioRepository.RADIOS_DIR}/$dir").count {
                    it.type == com.lsl.kotlin_agent_app.agent.AgentsDirEntryType.File &&
                        it.name.lowercase(java.util.Locale.ROOT).endsWith(".radio")
                }
            }.getOrDefault(0)

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("radio sync stations" + if (force) " --force" else ""))
                put("force", JsonPrimitive(force))
                put("dir", JsonPrimitive(dir))
                put("stations_index_path", JsonPrimitive(stationsIndexPath))
                put("radios_count", JsonPrimitive(radiosCount))
            }

        return TerminalCommandOutput(exitCode = 0, stdout = "synced stations: $dir radios=$radiosCount", result = result)
    }

    private suspend fun handleStatus(): TerminalCommandOutput {
        val ctrl = MusicPlayerControllerProvider.get()
        val snap = ctrl.statusSnapshotWithTransport()
        val st = snap.nowPlaying
        val tr = snap.transport
        val isRadio = !st.agentsPath.isNullOrBlank() && st.agentsPath.endsWith(".radio", ignoreCase = true) && isInRadiosTree(st.agentsPath)

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
                put(
                    "transport",
                    buildJsonObject {
                        put("is_connected", JsonPrimitive(tr.isConnected))
                        put("playback_state", JsonPrimitive(tr.playbackState.name.lowercase()))
                        put("play_when_ready", JsonPrimitive(tr.playWhenReady))
                        put("is_playing", JsonPrimitive(tr.isPlaying))
                        put("media_id", tr.mediaId?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("position_ms", JsonPrimitive(tr.positionMs))
                        put("duration_ms", tr.durationMs?.let { JsonPrimitive(it) } ?: JsonNull)
                    },
                )
                put("warning_message", st.warningMessage?.let { JsonPrimitive(it) } ?: JsonNull)
                put("error_message", st.errorMessage?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        return TerminalCommandOutput(exitCode = 0, stdout = "radio: $state", result = result)
    }

    private suspend fun handlePlay(argv: List<String>): TerminalCommandOutput {
        val rawIn = requireInPathArg(argv).trim()
        val agentsPath = normalizeAgentsPathArg(rawIn)
        if (!isInRadiosTree(agentsPath)) throw NotInRadiosDir("仅允许 radios/ 目录下的 .radio：$rawIn")
        if (!agentsPath.lowercase().endsWith(".radio")) throw NotRadioFile("仅允许 radios/ 目录下的 .radio：$rawIn")

        val f = File(ctx.filesDir, agentsPath)
        if (!f.exists() || !f.isFile) throw NotFound("文件不存在：$rawIn")

        val ctrl = MusicPlayerControllerProvider.get()

        val awaitMs = parseAwaitMsOrNull(argv) ?: 0L

        val station =
            withContext(Dispatchers.IO) {
                runCatching { RadioStationFileV1.parse(f.readText(Charsets.UTF_8)) }.getOrNull()
            } ?: throw InvalidRadio("非法 .radio：$rawIn")

        val resolved =
            withContext(Dispatchers.IO) {
                runCatching { streamUrlResolver.resolve(station.streamUrl) }.getOrNull()
            }

        val attemptUrls =
            buildList {
                if (resolved != null) {
                    when {
                        resolved.classification == StreamResolutionClassification.Hls ->
                            add(resolved.finalUrl.trim().ifBlank { station.streamUrl })

                        resolved.candidates.isNotEmpty() -> addAll(resolved.candidates)
                        resolved.finalUrl.isNotBlank() -> add(resolved.finalUrl)
                    }
                }
                add(station.streamUrl)
            }.map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(10)

        var verify: JsonObject? = null
        var attemptedUrl: String? = null

        for ((idx, url) in attemptUrls.withIndex()) {
            attemptedUrl = url
            try {
                ctrl.playAgentsRadioNow(agentsPath, streamUrlOverride = url)
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

            if (awaitMs > 0L) {
                val waited = awaitPlaybackOrError(awaitMs = awaitMs)
                verify =
                    buildJsonObject {
                        put("await_ms", JsonPrimitive(awaitMs))
                        put("elapsed_ms", JsonPrimitive(waited.elapsedMs))
                        put("polls", JsonPrimitive(waited.polls))
                        put("outcome", JsonPrimitive(waited.outcome))
                        put("attempt_index", JsonPrimitive(idx))
                        put("attempts_total", JsonPrimitive(attemptUrls.size))
                    }
                if (waited.outcome == "error" && idx + 1 < attemptUrls.size) {
                    continue
                }
            }

            break
        }

        val base = handleStatus().copy(stdout = "playing: ${rawIn.trim()}")
        val baseResult = base.result as? JsonObject ?: base.result?.jsonObject
        val merged =
            if (baseResult == null) {
                null
            } else {
                buildJsonObject {
                    for ((k, v) in baseResult) put(k, v)
                    put(
                        "play",
                        buildJsonObject {
                            put("in", JsonPrimitive(rawIn.trim()))
                            put("attempted_url", attemptedUrl?.let { JsonPrimitive(it) } ?: JsonNull)
                            put(
                                "resolution",
                                resolved?.let { r ->
                                    buildJsonObject {
                                        put("final_url", JsonPrimitive(r.finalUrl))
                                        put("classification", JsonPrimitive(r.classification.name))
                                        put(
                                            "candidates",
                                            buildJsonArray {
                                                for (c in r.candidates) add(JsonPrimitive(c))
                                            },
                                        )
                                    }
                                } ?: JsonNull,
                            )
                            put("verify", verify ?: JsonNull)
                        },
                    )
                }
            }

        return base.copy(result = merged)
    }

    private data class AwaitPlaybackResult(
        val outcome: String,
        val elapsedMs: Long,
        val polls: Int,
    )

    private suspend fun awaitPlaybackOrError(awaitMs: Long): AwaitPlaybackResult {
        val ctrl = MusicPlayerControllerProvider.get()
        val start = SystemClock.elapsedRealtime()
        var polls = 0
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - start
            if (elapsed >= awaitMs) {
                return AwaitPlaybackResult(outcome = "timeout", elapsedMs = elapsed, polls = polls)
            }
            val snap = ctrl.statusSnapshotWithTransport()
            polls += 1
            if (snap.nowPlaying.playbackState == MusicPlaybackState.Error) {
                return AwaitPlaybackResult(outcome = "error", elapsedMs = elapsed, polls = polls)
            }
            if (snap.transport.isPlaying) {
                return AwaitPlaybackResult(outcome = "playing", elapsedMs = elapsed, polls = polls)
            }
            delay(200)
        }
    }

    private fun parseAwaitMsOrNull(argv: List<String>): Long? {
        val raw = optionalFlagValueRest(argv, "--await_ms") ?: return null
        val n = raw.trim().toLongOrNull() ?: throw IllegalArgumentException("invalid --await_ms: $raw")
        if (n < 0L) throw IllegalArgumentException("--await_ms must be >= 0")
        return n.coerceAtMost(30_000L)
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

    private suspend fun handleFavAdd(argv: List<String>): TerminalCommandOutput {
        ws.ensureInitialized()
        val rawIn = optionalInPathArg(argv)
        val agentsPath =
            if (rawIn.isNullOrBlank()) {
                val st = MusicPlayerControllerProvider.get().statusSnapshot()
                val p = st.agentsPath?.trim().orEmpty()
                val isRadio = p.endsWith(".radio", ignoreCase = true) && isInRadiosTree(p)
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
        val rawIn = optionalInPathArg(argv)
        val agentsPath =
            if (rawIn.isNullOrBlank()) {
                val st = MusicPlayerControllerProvider.get().statusSnapshot()
                val p = st.agentsPath?.trim().orEmpty()
                val isRadio = p.endsWith(".radio", ignoreCase = true) && isInRadiosTree(p)
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
        val isRadio = !st.agentsPath.isNullOrBlank() && st.agentsPath.endsWith(".radio", ignoreCase = true) && isInRadiosTree(st.agentsPath)
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
                          radio play (--in <agents-path> | --in_b64 <base64-utf8-path>) [--await_ms <ms>]
                          radio pause
                          radio resume
                          radio stop
                          radio fav add [--in <agents-path> | --in_b64 <base64-utf8-path>]
                          radio fav rm [--in <agents-path> | --in_b64 <base64-utf8-path>]
                          radio fav list
                          radio sync countries [--force]
                          radio sync stations (--dir <country-dir> | --cc <CC>) [--force]
                        
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
                        "radio sync countries",
                        "radio sync stations --cc EG",
                    )
                }
                "status" -> "Usage: radio status" to listOf("radio status")
                "play" ->
                    "Usage: radio play (--in <agents-path> | --in_b64 <base64-utf8-path>) [--await_ms <ms>]" to
                        listOf(
                            "radio play --in workspace/radios/demo.radio",
                            "radio play --in \"workspace/radios/EG__Egypt/Egyptian Radio__6254b05b33.radio\"",
                            "radio play --in workspace/radios/demo.radio --await_ms 4000",
                        )
                "pause" -> "Usage: radio pause" to listOf("radio pause")
                "resume" -> "Usage: radio resume" to listOf("radio resume")
                "stop" -> "Usage: radio stop" to listOf("radio stop")
                "fav" -> {
                    val u =
                        """
                        Usage:
                          radio fav add [--in <agents-path> | --in_b64 <base64-utf8-path>]
                          radio fav rm [--in <agents-path> | --in_b64 <base64-utf8-path>]
                          radio fav list
                        """.trimIndent()
                    u to listOf("radio fav list", "radio fav add", "radio fav rm")
                }
                "sync" -> {
                    val u =
                        """
                        Usage:
                          radio sync countries [--force]
                          radio sync stations (--dir <country-dir> | --cc <CC>) [--force]
                        """.trimIndent()
                    u to listOf("radio sync countries", "radio sync stations --cc EG", "radio sync stations --dir EG__Egypt --force")
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
                        add(JsonPrimitive("sync"))
                    },
                )
                if (sub == null || sub == "play") {
                    put(
                        "flags",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("name", JsonPrimitive("--in"))
                                    put("required", JsonPrimitive(false))
                                    put("description", JsonPrimitive("Input .radio file within workspace/radios/. For paths with spaces, you may quote with double-quotes."))
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("name", JsonPrimitive("--in_b64"))
                                    put("required", JsonPrimitive(false))
                                    put("description", JsonPrimitive("Base64-encoded UTF-8 path for input .radio file within workspace/radios/. Use when you cannot reliably quote/escape spaces or Unicode."))
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("name", JsonPrimitive("--await_ms"))
                                    put("required", JsonPrimitive(false))
                                    put("description", JsonPrimitive("Optional wait window after play. During this window, the command will poll transport status until isPlaying=true or error. Default: 0. Max: 30000."))
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
                            add(
                                buildJsonObject {
                                    put("name", JsonPrimitive("--in_b64"))
                                    put("required", JsonPrimitive(false))
                                    put("description", JsonPrimitive("Optional base64-encoded UTF-8 path for input .radio file within workspace/radios/ (defaults to current playing station)."))
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

    private fun decodeBase64Utf8(
        rawB64: String,
        flag: String,
    ): String {
        val b64 = rawB64.trim()
        if (b64.isEmpty()) throw IllegalArgumentException("empty value for $flag")
        try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            return String(bytes, Charsets.UTF_8)
        } catch (t: IllegalArgumentException) {
            throw IllegalArgumentException("invalid base64 for $flag")
        }
    }

    private fun optionalFlagValueRest(
        argv: List<String>,
        flag: String,
    ): String? {
        val idx = argv.indexOf(flag)
        if (idx < 0 || idx + 1 >= argv.size) return null

        val parts = mutableListOf<String>()
        for (i in (idx + 1) until argv.size) {
            val tok = argv[i]
            if (tok.startsWith("--")) break
            parts.add(tok)
        }
        val joined = parts.joinToString(" ").trim()
        return joined.ifBlank { null }
    }

    private fun requireInPathArg(argv: List<String>): String {
        val inB64 = optionalFlagValueRest(argv, "--in_b64")
        if (!inB64.isNullOrBlank()) return decodeBase64Utf8(inB64, flag = "--in_b64")

        val rawIn = optionalFlagValueRest(argv, "--in")
        if (!rawIn.isNullOrBlank()) return rawIn

        throw IllegalArgumentException("missing required flag: --in or --in_b64")
    }

    private fun optionalInPathArg(argv: List<String>): String? {
        val inB64 = optionalFlagValueRest(argv, "--in_b64")
        if (!inB64.isNullOrBlank()) return decodeBase64Utf8(inB64, flag = "--in_b64")
        return optionalFlagValueRest(argv, "--in")
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
