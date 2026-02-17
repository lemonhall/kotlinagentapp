package com.lsl.kotlin_agent_app.agent.tools.terminal

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.tools.OpenAiSchemaTool
import me.lemonhall.openagentic.sdk.tools.Tool
import me.lemonhall.openagentic.sdk.tools.ToolContext
import me.lemonhall.openagentic.sdk.tools.ToolInput
import me.lemonhall.openagentic.sdk.tools.ToolOutput
import me.lemonhall.openagentic.sdk.tools.ToolRegistry

class TerminalExecTool(
    appContext: Context,
    private val maxStdoutChars: Int = 16_000,
    private val maxStderrChars: Int = 8_000,
) : Tool, OpenAiSchemaTool {
    private val ctx = appContext.applicationContext

    private val registry =
        TerminalCommandRegistry(
            listOf(
                HelloCommand,
            ),
        )

    override val name: String = "terminal_exec"
    override val description: String = "Execute a whitelisted pseudo-terminal command (no shell, no external processes)."

    override fun openAiSchema(
        ctx: ToolContext,
        registry: ToolRegistry?,
    ): JsonObject {
        val params =
            buildJsonObject {
                put(
                    "command",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("Single-line pseudo-terminal command. Example: hello"))
                    },
                )
                put(
                    "stdin",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("Optional stdin content (plain text)."))
                    },
                )
                put(
                    "timeout_ms",
                    buildJsonObject {
                        put("type", JsonPrimitive("number"))
                        put("description", JsonPrimitive("Optional timeout hint in milliseconds (may be capped)."))
                    },
                )
                put(
                    "timeout",
                    buildJsonObject {
                        put("type", JsonPrimitive("number"))
                        put("description", JsonPrimitive("Alias of timeout_ms."))
                    },
                )
            }
        return buildJsonObject {
            put("type", JsonPrimitive("function"))
            put(
                "function",
                buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("description", JsonPrimitive(description))
                    put(
                        "parameters",
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put("properties", params)
                            put("required", JsonArray(listOf(JsonPrimitive("command"))))
                        },
                    )
                },
            )
        }
    }

    override suspend fun run(
        input: ToolInput,
        ctx: ToolContext,
    ): ToolOutput {
        val commandRaw = input["command"]?.asStringOrNull()?.trim().orEmpty()
        val stdin = input["stdin"]?.asStringOrNull()
        val startedAt = System.currentTimeMillis()
        val runId = allocateRunId()

        val parsed =
            try {
                parseAndValidateCommand(commandRaw)
            } catch (t: Throwable) {
                val out =
                    TerminalCommandOutput(
                        exitCode = 2,
                        stdout = "",
                        stderr = (t.message ?: "invalid command").take(maxStderrChars),
                        errorCode = "InvalidCommand",
                        errorMessage = t.message,
                    )
                writeAuditRun(
                    runId = runId,
                    command = commandRaw,
                    argv = emptyList(),
                    output = out,
                    durationMs = System.currentTimeMillis() - startedAt,
                )
                return ToolOutput.Json(renderToolOutput(runId = runId, argv = emptyList(), out = out, durationMs = System.currentTimeMillis() - startedAt))
            }

        val cmdName = parsed.firstOrNull().orEmpty()
        val cmd = registry.get(cmdName)
        val out =
            if (cmd == null) {
                TerminalCommandOutput(
                    exitCode = 127,
                    stdout = "",
                    stderr = "Unknown command: $cmdName",
                    errorCode = "UnknownCommand",
                    errorMessage = "unknown command: $cmdName",
                )
            } else {
                try {
                    cmd.run(argv = parsed, stdin = stdin)
                } catch (t: Throwable) {
                    TerminalCommandOutput(
                        exitCode = 1,
                        stdout = "",
                        stderr = (t.message ?: "command failed").take(maxStderrChars),
                        errorCode = "CommandFailed",
                        errorMessage = t.message,
                    )
                }
            }

        val duration = System.currentTimeMillis() - startedAt
        val bounded =
            out.copy(
                stdout = out.stdout.take(maxStdoutChars),
                stderr = out.stderr.take(maxStderrChars),
            )

        writeAuditRun(
            runId = runId,
            command = commandRaw,
            argv = parsed,
            output = bounded,
            durationMs = duration,
        )

        return ToolOutput.Json(renderToolOutput(runId = runId, argv = parsed, out = bounded, durationMs = duration))
    }

    private fun parseAndValidateCommand(command: String): List<String> {
        val s = command.trim()
        require(s.isNotEmpty()) { "command is empty" }
        require(!s.contains('\n') && !s.contains('\r')) { "command must be a single line" }

        // v1 strictness: explicitly ban multi-command / shell-like metacharacters.
        val banned =
            listOf(
                ";",
                "&&",
                "||",
                "|",
                ">",
                "<",
                "`",
                "$(",
            )
        for (b in banned) {
            require(!s.contains(b)) { "command contains banned token: $b" }
        }
        require(s.length <= 1024) { "command too long" }

        val argv = parseCommandLine(s)
        require(argv.isNotEmpty()) { "command argv is empty" }
        require(argv.size <= 32) { "too many argv items" }
        return argv
    }

    private fun parseCommandLine(command: String): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var inQuotes = false
        var escaping = false

        fun flush() {
            if (buf.isNotEmpty()) {
                out.add(buf.toString())
                buf.setLength(0)
            }
        }

        for (ch in command) {
            if (escaping) {
                buf.append(ch)
                escaping = false
                continue
            }
            when (ch) {
                '\\' -> {
                    escaping = true
                }
                '"' -> {
                    inQuotes = !inQuotes
                }
                ' ', '\t' -> {
                    if (inQuotes) {
                        buf.append(ch)
                    } else {
                        flush()
                    }
                }
                else -> buf.append(ch)
            }
        }
        require(!inQuotes) { "unterminated quote" }
        require(!escaping) { "dangling escape" }
        flush()
        return out
    }

    private fun allocateRunId(): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        val ts = fmt.format(Date())
        val rand = UUID.randomUUID().toString().replace("-", "").take(10)
        return "${ts}_$rand"
    }

    private fun writeAuditRun(
        runId: String,
        command: String,
        argv: List<String>,
        output: TerminalCommandOutput,
        durationMs: Long,
    ) {
        try {
            val dir = File(ctx.filesDir, ".agents/artifacts/terminal_exec/runs")
            dir.mkdirs()
            val f = File(dir, "$runId.json")
            val json = renderAuditJson(runId = runId, command = command, argv = argv, out = output, durationMs = durationMs)
            f.writeText(json, Charsets.UTF_8)
        } catch (_: Throwable) {
            // best-effort auditing
        }
    }

    private fun renderAuditJson(
        runId: String,
        command: String,
        argv: List<String>,
        out: TerminalCommandOutput,
        durationMs: Long,
    ): String {
        // Keep it simple: encode JSON manually through kotlinx JsonElement rendering.
        val el =
            buildJsonObject {
                put("run_id", JsonPrimitive(runId))
                put("timestamp_ms", JsonPrimitive(System.currentTimeMillis()))
                put("command", JsonPrimitive(command))
                put("argv", buildJsonArray { argv.forEach { add(JsonPrimitive(it)) } })
                put("exit_code", JsonPrimitive(out.exitCode))
                put("duration_ms", JsonPrimitive(durationMs))
                if (!out.errorCode.isNullOrBlank()) put("error_code", JsonPrimitive(out.errorCode))
                if (!out.errorMessage.isNullOrBlank()) put("error_message", JsonPrimitive(out.errorMessage))
                put("artifacts", buildJsonArray {
                    for (a in out.artifacts) {
                        add(
                            buildJsonObject {
                                put("path", JsonPrimitive(a.path))
                                put("mime", JsonPrimitive(a.mime))
                                put("description", JsonPrimitive(a.description))
                            },
                        )
                    }
                })
            }
        return el.toString() + "\n"
    }

    private fun renderToolOutput(
        runId: String,
        argv: List<String>,
        out: TerminalCommandOutput,
        durationMs: Long,
    ): JsonElement {
        return buildJsonObject {
            put("run_id", JsonPrimitive(runId))
            put("exit_code", JsonPrimitive(out.exitCode))
            put("stdout", JsonPrimitive(out.stdout))
            put("stderr", JsonPrimitive(out.stderr))
            put("duration_ms", JsonPrimitive(durationMs))
            if (!out.errorCode.isNullOrBlank()) put("error_code", JsonPrimitive(out.errorCode))
            if (!out.errorMessage.isNullOrBlank()) put("error_message", JsonPrimitive(out.errorMessage))

            if (out.result != null) put("result", out.result) else put("result", JsonNull)
            put(
                "artifacts",
                buildJsonArray {
                    for (a in out.artifacts) {
                        add(
                            buildJsonObject {
                                put("path", JsonPrimitive(a.path))
                                put("mime", JsonPrimitive(a.mime))
                                put("description", JsonPrimitive(a.description))
                            },
                        )
                    }
                },
            )

            // Convenience echo for debuggability.
            put("argv", buildJsonArray { argv.forEach { add(JsonPrimitive(it)) } })
        }
    }
}

private fun JsonElement.asStringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNullSafe()

private fun JsonPrimitive.contentOrNullSafe(): String? =
    try {
        this.content
    } catch (_: Throwable) {
        null
    }

