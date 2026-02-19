package com.lsl.kotlin_agent_app.agent.tools.terminal.commands.tts

import android.content.Context
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalArtifact
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommandOutput
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.optionalFlagValue
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.parseIntFlag
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.parseLongFlag
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.relPath
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.requireFlagValue
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.resolveWithinAgents
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsFailure
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsNotSupported
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsQueueMode
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsRuntimeProvider
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsSpeakRequest
import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal class TtsCommand(
    appContext: Context,
) : TerminalCommand {
    private val ctx = appContext.applicationContext
    private val agentsRoot = File(ctx.filesDir, ".agents").canonicalFile
    private val runtime = TtsRuntimeProvider.get(ctx)

    override val name: String = "tts"
    override val description: String = "Android TextToSpeech (TTS) CLI (voices/speak/stop) via terminal_exec."

    override suspend fun run(
        argv: List<String>,
        stdin: String?,
    ): TerminalCommandOutput {
        return try {
            if (argv.size <= 1) return helpOut()

            val sub = argv[1].lowercase()
            if (sub == "--help" || sub == "help") {
                val target = argv.getOrNull(2)?.lowercase()
                return helpOut(target)
            }

            if (argv.size >= 3 && (argv[2] == "--help" || argv[2] == "help")) {
                return helpOut(sub)
            }

            when (sub) {
                "voices" -> handleVoices(argv)
                "speak" -> handleSpeak(argv, stdin)
                "stop" -> handleStop()
                else -> invalidArgs("unknown subcommand: ${argv[1]}")
            }
        } catch (t: TtsFailure) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "tts error"),
                errorCode = t.code,
                errorMessage = t.message,
            )
        } catch (t: IllegalArgumentException) {
            invalidArgs(t.message ?: "invalid args")
        } catch (t: Throwable) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "tts error"),
                errorCode = "TtsError",
                errorMessage = t.message,
            )
        }
    }

    private suspend fun handleVoices(argv: List<String>): TerminalCommandOutput {
        val max = parseIntFlag(argv, "--max", defaultValue = 50).coerceAtLeast(0)
        val outRel = optionalFlagValue(argv, "--out")?.trim()?.takeIf { it.isNotBlank() }
        val outFile = outRel?.let { resolveWithinAgents(agentsRoot, it) }

        val voices = runtime.listVoices()
        if (voices.isEmpty()) throw TtsNotSupported("No TTS voices available on this device.")

        val limited = if (max == 0) emptyList() else voices.take(max)
        val truncated = limited.size < voices.size

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("tts voices"))
                put("voices_count", JsonPrimitive(voices.size))
                put("max", JsonPrimitive(max))
                put("truncated", JsonPrimitive(truncated))
                put(
                    "voices",
                    buildJsonArray {
                        for (v in limited) {
                            add(
                                buildJsonObject {
                                    put("name", JsonPrimitive(v.name))
                                    put("locale_tag", JsonPrimitive(v.localeTag))
                                    if (v.quality != null) put("quality", JsonPrimitive(v.quality))
                                    if (v.latency != null) put("latency", JsonPrimitive(v.latency))
                                    if (v.requiresNetwork != null) put("requires_network", JsonPrimitive(v.requiresNetwork))
                                },
                            )
                        }
                    },
                )
            }

        val artifacts =
            if (outRel != null && outFile != null) {
                val fullJson = fullVoicesJson(outRel = outRel, voices = voices)
                val a = writeOutJson(file = outFile, json = fullJson)
                listOf(a)
            } else {
                emptyList()
            }

        val stdout =
            buildString {
                append("tts voices: count=${voices.size}")
                if (truncated) append(" (showing ${limited.size})")
                if (outRel != null) append(" out=$outRel")
            }

        return TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result, artifacts = artifacts)
    }

    private suspend fun handleSpeak(
        argv: List<String>,
        stdin: String?,
    ): TerminalCommandOutput {
        val text = requireFlagValue(argv, "--text")
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return invalidArgs("--text is empty")
        if (trimmed.length > 8000) return invalidArgs("--text too long (max 8000 chars)")

        val localeTag = optionalFlagValue(argv, "--locale")?.trim()?.takeIf { it.isNotBlank() }
        val rate = optionalFlagValue(argv, "--rate")?.trim()?.takeIf { it.isNotBlank() }?.let { parseFloatInRange("--rate", it, 0.1f, 2.0f) }
        val pitch = optionalFlagValue(argv, "--pitch")?.trim()?.takeIf { it.isNotBlank() }?.let { parseFloatInRange("--pitch", it, 0.5f, 2.0f) }

        val queue =
            when (optionalFlagValue(argv, "--queue")?.trim()?.lowercase()) {
                null, "", "flush" -> TtsQueueMode.Flush
                "add" -> TtsQueueMode.Add
                else -> return invalidArgs("invalid --queue (expected flush|add)")
            }

        val await = optionalFlagValue(argv, "--await")?.let { parseBoolFlag("--await", it) } ?: false
        val timeoutMs = parseLongFlag(argv, "--timeout_ms", defaultValue = 20_000L).coerceAtLeast(1L)

        val resp =
            runtime.speak(
                req =
                    TtsSpeakRequest(
                        text = trimmed,
                        localeTag = localeTag,
                        rate = rate,
                        pitch = pitch,
                        queueMode = queue,
                    ),
                await = await,
                timeoutMs = if (await) timeoutMs else null,
            )

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("tts speak"))
                put("utterance_id", JsonPrimitive(resp.utteranceId))
                put("text_chars", JsonPrimitive(trimmed.length))
                put("await", JsonPrimitive(await))
                put("completion", JsonPrimitive(resp.completion.name.lowercase()))
                if (!localeTag.isNullOrBlank()) put("locale_tag", JsonPrimitive(localeTag))
                if (rate != null) put("rate", JsonPrimitive(rate))
                if (pitch != null) put("pitch", JsonPrimitive(pitch))
                put("queue", JsonPrimitive(queue.name.lowercase()))
                put("stdin_used", JsonPrimitive(!stdin.isNullOrEmpty()))
            }

        val stdout = "tts speak: utterance_id=${resp.utteranceId} chars=${trimmed.length} await=$await"
        return TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
    }

    private suspend fun handleStop(): TerminalCommandOutput {
        val r = runtime.stop()
        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("tts stop"))
                put("stopped", JsonPrimitive(r.stopped))
            }
        val stdout = "tts stop: stopped=${r.stopped}"
        return TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
    }

    private fun helpOut(target: String? = null): TerminalCommandOutput {
        val usage =
            when (target?.lowercase()) {
                null, "", "tts" ->
                    """
                    Usage:
                      tts voices [--max N] [--out <agents_rel_path>]
                      tts speak --text "<text>" [--locale <bcp47>] [--rate 0.1..2.0] [--pitch 0.5..2.0] [--queue flush|add] [--await true|false] [--timeout_ms N]
                      tts stop
                    
                    Help:
                      tts --help
                      tts help
                      tts voices --help
                      tts help voices
                      tts speak --help
                      tts help speak
                      tts stop --help
                      tts help stop
                    """.trimIndent()
                "voices" ->
                    """
                    Usage:
                      tts voices [--max N] [--out <agents_rel_path>]
                    
                    Flags:
                      --max N       Max voices in result.voices (default 50, 0 = no voices array)
                      --out PATH    Write full JSON to .agents/PATH and return it via artifacts[]
                    """.trimIndent()
                "speak" ->
                    """
                    Usage:
                      tts speak --text "<text>" [--locale <bcp47>] [--rate 0.1..2.0] [--pitch 0.5..2.0] [--queue flush|add] [--await true|false] [--timeout_ms N]
                    
                    Notes:
                      - Default queue is flush (interrupt previous speech).
                      - If --await true, the command waits for completion (bounded by --timeout_ms).
                    """.trimIndent()
                "stop" ->
                    """
                    Usage:
                      tts stop
                    
                    Stops current speech and clears the queue.
                    """.trimIndent()
                else -> "Unknown help topic: $target"
            }

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("tts help"))
                put("topic", JsonPrimitive(target ?: ""))
                put("usage", JsonPrimitive(usage))
                put("subcommands", buildJsonArray {
                    add(JsonPrimitive("voices"))
                    add(JsonPrimitive("speak"))
                    add(JsonPrimitive("stop"))
                })
            }

        return TerminalCommandOutput(exitCode = 0, stdout = usage, result = result)
    }

    private fun fullVoicesJson(
        outRel: String,
        voices: List<com.lsl.kotlin_agent_app.agent.tools.tts.TtsVoiceSummary>,
    ): JsonElement {
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("command", JsonPrimitive("tts voices"))
            put("out", JsonPrimitive(outRel))
            put("voices_count", JsonPrimitive(voices.size))
            put(
                "voices",
                buildJsonArray {
                    for (v in voices) {
                        add(
                            buildJsonObject {
                                put("name", JsonPrimitive(v.name))
                                put("locale_tag", JsonPrimitive(v.localeTag))
                                if (v.quality != null) put("quality", JsonPrimitive(v.quality))
                                if (v.latency != null) put("latency", JsonPrimitive(v.latency))
                                if (v.requiresNetwork != null) put("requires_network", JsonPrimitive(v.requiresNetwork))
                            },
                        )
                    }
                },
            )
        }
    }

    private fun writeOutJson(
        file: File,
        json: JsonElement,
    ): TerminalArtifact {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        file.writeText(json.toString() + "\n", Charsets.UTF_8)
        return TerminalArtifact(
            path = ".agents/" + relPath(agentsRoot, file),
            mime = "application/json",
            description = "tts voices full output (may be large).",
        )
    }

    private fun parseBoolFlag(
        flag: String,
        raw: String,
    ): Boolean {
        return when (raw.trim().lowercase()) {
            "true", "1", "yes", "y" -> true
            "false", "0", "no", "n" -> false
            else -> throw IllegalArgumentException("invalid bool for $flag: $raw")
        }
    }

    private fun parseFloatInRange(
        flag: String,
        raw: String,
        min: Float,
        max: Float,
    ): Float {
        val v = raw.toFloatOrNull() ?: throw IllegalArgumentException("invalid float for $flag: $raw")
        if (v < min || v > max) throw IllegalArgumentException("$flag out of range ($min..$max): $raw")
        return v
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
}
