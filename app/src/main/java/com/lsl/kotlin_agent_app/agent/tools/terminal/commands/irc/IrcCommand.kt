package com.lsl.kotlin_agent_app.agent.tools.terminal.commands.irc

import android.content.Context
import com.lsl.kotlin_agent_app.config.AppPrefsKeys
import com.lsl.kotlin_agent_app.agent.tools.irc.IrcConfig
import com.lsl.kotlin_agent_app.agent.tools.irc.IrcConfigLoader
import com.lsl.kotlin_agent_app.agent.tools.irc.IrcConnectionState
import com.lsl.kotlin_agent_app.agent.tools.irc.IrcSessionRuntimeStore
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommandOutput
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.ConfirmRequired
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.hasFlag
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.optionalFlagValue
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.parseIntFlag
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.requireConfirm
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class IrcMissingCredentials(
    message: String,
) : IllegalArgumentException(message)

internal class NickTooLong(
    message: String,
) : IllegalArgumentException(message)

internal class ForbiddenArg(
    message: String,
) : IllegalArgumentException(message)

internal class IrcCommand(
    appContext: Context,
) : TerminalCommand {
    private val ctx = appContext.applicationContext
    private val agentsRoot = File(ctx.filesDir, ".agents").canonicalFile
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override val name: String = "irc"
    override val description: String =
        "IRC long-connection CLI (status/send/pull) bound to agent session with dotenv secrets and pull cursor/truncation."

    override suspend fun run(
        argv: List<String>,
        stdin: String?,
    ): TerminalCommandOutput {
        if (argv.size < 2) return invalidArgs("missing subcommand")
        rejectForbiddenSecretFlags(argv)

        return try {
            when (argv[1].lowercase()) {
                "connect" -> handleConnect(argv)
                "disconnect" -> handleDisconnect()
                "status" -> handleStatus()
                "history" -> handleHistory(argv)
                "send" -> handleSend(argv, stdin)
                "pull" -> handlePull(argv)
                else -> invalidArgs("unknown subcommand: ${argv[1]}")
            }
        } catch (t: IrcMissingCredentials) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "missing credentials"),
                errorCode = "MissingCredentials",
                errorMessage = t.message,
            )
        } catch (t: NickTooLong) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "nick too long"),
                errorCode = "NickTooLong",
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
        } catch (t: ForbiddenArg) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "forbidden arg"),
                errorCode = "ForbiddenArg",
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

    private suspend fun handleConnect(argv: List<String>): TerminalCommandOutput {
        val (config, sessionKey) = requireConfigAndSessionKey()
        val force = hasFlag(argv, "--force")
        IrcSessionRuntimeStore.requestConnect(
            agentsRoot = agentsRoot,
            sessionKey = sessionKey,
            force = force,
        )

        val snapshot = IrcSessionRuntimeStore.statusFlow(sessionKey).value
        val state =
            when (snapshot.state) {
                IrcConnectionState.NotInitialized -> "not_initialized"
                IrcConnectionState.Connecting -> "connecting"
                IrcConnectionState.Connected -> "connected"
                IrcConnectionState.Joined -> "joined"
                IrcConnectionState.Reconnecting -> "reconnecting"
                IrcConnectionState.Disconnected -> "disconnected"
                IrcConnectionState.Error -> "error"
            }

        return TerminalCommandOutput(
            exitCode = 0,
            stdout = "irc: connect requested (force=$force)",
            result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("irc connect"))
                    put("force", JsonPrimitive(force))
                    put("session_bound", JsonPrimitive(true))
                    put("state", JsonPrimitive(state))
                    put("server", JsonPrimitive(config.server))
                    put("port", JsonPrimitive(config.port))
                    put("tls", JsonPrimitive(config.tls))
                    put("nick", JsonPrimitive(config.nick))
                    put("channel", JsonPrimitive(config.channel))
                },
        )
    }

    private fun handleDisconnect(): TerminalCommandOutput {
        val sessionKey = requireSessionKey()
        IrcSessionRuntimeStore.closeSession(sessionKey)
        return TerminalCommandOutput(
            exitCode = 0,
            stdout = "irc: disconnected (session=$sessionKey)",
            result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("irc disconnect"))
                    put("session_bound", JsonPrimitive(true))
                },
        )
    }

    private suspend fun handleStatus(): TerminalCommandOutput {
        val (config, sessionKey) = requireConfigAndSessionKey()
        // Ensure a session-bound runtime exists so inbound capture/cursor state can bind to this session.
        IrcSessionRuntimeStore.getOrCreateRuntime(
            agentsRoot = agentsRoot,
            sessionKey = sessionKey,
            config = config,
        )
        val snapshot = IrcSessionRuntimeStore.statusFlow(sessionKey).value

        val state =
            when (snapshot.state) {
                IrcConnectionState.NotInitialized -> "not_initialized"
                IrcConnectionState.Connecting -> "connecting"
                IrcConnectionState.Connected -> "connected"
                IrcConnectionState.Joined -> "joined"
                IrcConnectionState.Reconnecting -> "reconnecting"
                IrcConnectionState.Disconnected -> "disconnected"
                IrcConnectionState.Error -> "error"
            }

        val ok = snapshot.state != IrcConnectionState.Error
        return TerminalCommandOutput(
            exitCode = 0,
            stdout = "irc: $state",
            result =
                buildJsonObject {
                    put("ok", JsonPrimitive(ok))
                    put("command", JsonPrimitive("irc status"))
                    put("state", JsonPrimitive(state))
                    put("server", JsonPrimitive(config.server))
                    put("port", JsonPrimitive(config.port))
                    put("tls", JsonPrimitive(config.tls))
                    put("nick", JsonPrimitive(config.nick))
                    put("channel", JsonPrimitive(config.channel))
                    put("auto_forward_to_agent", JsonPrimitive(config.autoForwardToAgent))
                    snapshot.lastError?.let { le ->
                        put(
                            "last_error",
                            buildJsonObject {
                                put("error_code", JsonPrimitive(le.errorCode))
                                put("message", JsonPrimitive(le.message))
                            },
                        )
                    }
                },
        )
    }

    private suspend fun handleSend(
        argv: List<String>,
        stdin: String?,
    ): TerminalCommandOutput {
        val (config, sessionKey) = requireConfigAndSessionKey()
        val to = optionalFlagValue(argv, "--to")?.trim()?.takeIf { it.isNotBlank() } ?: config.channel

        val useStdin = hasFlag(argv, "--text-stdin")
        val textFlag = optionalFlagValue(argv, "--text")?.takeIf { it.isNotBlank() }
        val text =
            when {
                useStdin -> stdin ?: throw IllegalArgumentException("missing stdin for --text-stdin")
                textFlag != null -> textFlag
                else -> throw IllegalArgumentException("missing --text or --text-stdin")
            }
        if (useStdin && textFlag != null) throw IllegalArgumentException("use only one of --text or --text-stdin")

        val defaultTo = config.channel
        if (to != defaultTo) {
            requireConfirm(argv, extraMessage = "sending to non-default target requires --confirm")
        }

        val rt =
            IrcSessionRuntimeStore.getOrCreateRuntime(
                agentsRoot = agentsRoot,
                sessionKey = sessionKey,
                config = config,
            )

        // Ensure connected+joined before send (v28: session-bound long connection).
        rt.ensureConnectedAndJoinedDefault()
        val now = System.currentTimeMillis()
        rt.sendPrivmsg(to = to, text = text)
        appendOutboundJsonl(
            sessionKey = sessionKey,
            channel = to,
            nick = config.nick,
            text = text,
            tsMs = now,
        )

        val st = IrcSessionRuntimeStore.statusFlow(sessionKey).value
        val connected = st.state == IrcConnectionState.Connected || st.state == IrcConnectionState.Joined
        val joined = st.state == IrcConnectionState.Joined

        return TerminalCommandOutput(
            exitCode = 0,
            stdout = "sent PRIVMSG to $to (len=${text.length})",
            result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("irc send"))
                    put("to", JsonPrimitive(to))
                    put("message_len", JsonPrimitive(text.length))
                    put("session_bound", JsonPrimitive(true))
                    put("connected", JsonPrimitive(connected))
                    put("joined_default_channel", JsonPrimitive(joined))
                },
        )
    }

    private fun handleHistory(argv: List<String>): TerminalCommandOutput {
        val sessionKey = requireSessionKey()
        val channelFilter = optionalFlagValue(argv, "--from")?.trim()?.takeIf { it.isNotBlank() }
        val limit = parseIntFlag(argv, "--limit", defaultValue = 50).coerceIn(0, 200)
        val scanLines = (limit.coerceAtLeast(50) * 20).coerceIn(200, 4_000)

        val dir = sessionDir(sessionKey)
        val inbound = File(dir, "inbound.jsonl")
        val outbound = File(dir, "outbound.jsonl")

        data class Msg(
            val id: String,
            val tsMs: Long,
            val channel: String,
            val nick: String,
            val text: String,
            val direction: String,
        )

        fun parseLine(
            line: String,
            direction: String,
        ): Msg? {
            val s = line.trim()
            if (s.isBlank()) return null
            val obj =
                try {
                    json.parseToJsonElement(s).jsonObject
                } catch (_: Throwable) {
                    return null
                }
            val id = obj["id"]?.jsonPrimitive?.content?.trim().orEmpty()
            val ts = obj["ts_ms"]?.jsonPrimitive?.content?.trim()?.toLongOrNull() ?: 0L
            val ch = obj["channel"]?.jsonPrimitive?.content?.trim().orEmpty()
            val nick = obj["nick"]?.jsonPrimitive?.content?.trim().orEmpty()
            val text = obj["text"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (id.isBlank() || ts <= 0L || ch.isBlank() || text.isBlank()) return null
            if (channelFilter != null && ch != channelFilter) return null
            return Msg(id = id, tsMs = ts, channel = ch, nick = nick, text = text, direction = direction)
        }

        val merged = ArrayList<Msg>(limit * 2)
        tailLines(inbound, maxLines = scanLines).forEach { line ->
            parseLine(line, direction = "in")?.let { merged.add(it) }
        }
        tailLines(outbound, maxLines = scanLines).forEach { line ->
            parseLine(line, direction = "out")?.let { merged.add(it) }
        }

        val final =
            merged
                .sortedWith(compareBy<Msg> { it.tsMs }.thenBy { it.direction }.thenBy { it.id })
                .takeLast(limit)

        return TerminalCommandOutput(
            exitCode = 0,
            stdout = "history ${final.size} message(s)",
            result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("irc history"))
                    put("session_bound", JsonPrimitive(true))
                    channelFilter?.let { put("from", JsonPrimitive(it)) }
                    put("returned", JsonPrimitive(final.size))
                    put(
                        "messages",
                        buildJsonArray {
                            for (m in final) {
                                add(
                                    buildJsonObject {
                                        put("id", JsonPrimitive(m.id))
                                        put("ts", JsonPrimitive(m.tsMs))
                                        put("channel", JsonPrimitive(m.channel))
                                        put("nick", JsonPrimitive(m.nick))
                                        put("text", JsonPrimitive(m.text))
                                        put("direction", JsonPrimitive(m.direction))
                                    },
                                )
                            }
                        },
                    )
                },
        )
    }

    private suspend fun handlePull(argv: List<String>): TerminalCommandOutput {
        val (config, sessionKey) = requireConfigAndSessionKey()
        val from = optionalFlagValue(argv, "--from")?.trim()?.takeIf { it.isNotBlank() } ?: config.channel
        val limit = parseIntFlag(argv, "--limit", defaultValue = 20).coerceIn(0, 200)
        val peek = hasFlag(argv, "--peek")

        // v28: summary is the only stable format; accept flag but ignore for now.
        val rt =
            IrcSessionRuntimeStore.getOrCreateRuntime(
                agentsRoot = agentsRoot,
                sessionKey = sessionKey,
                config = config,
            )

        val pr = rt.pull(from = from, limit = limit, peek = peek)

        return TerminalCommandOutput(
            exitCode = 0,
            stdout = "pulled ${pr.returned} message(s) from $from",
            result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("irc pull"))
                    put("from", JsonPrimitive(from))
                    put("returned", JsonPrimitive(pr.returned))
                    pr.cursorBefore?.let { put("cursor_before", JsonPrimitive(it)) }
                    if (!peek) {
                        pr.cursorAfter?.let { put("cursor_after", JsonPrimitive(it)) }
                    }
                    put("truncated", JsonPrimitive(pr.truncated))
                    if (pr.droppedCount > 0L) put("dropped_count", JsonPrimitive(pr.droppedCount))
                    put(
                        "messages",
                        buildJsonArray {
                            for (m in pr.messages) {
                                add(
                                    buildJsonObject {
                                        put("id", JsonPrimitive(m.id))
                                        put("ts", JsonPrimitive(m.tsMs))
                                        put("nick", JsonPrimitive(m.nick))
                                        put("text", JsonPrimitive(m.text))
                                    },
                                )
                            }
                        },
                    )
                },
        )
    }

    private fun requireConfigAndSessionKey(): Pair<IrcConfig, String> {
        val config =
            IrcConfigLoader.loadFromAgentsRoot(agentsRoot)
                ?: throw IrcMissingCredentials("Missing IRC config in .agents/skills/${IrcConfigLoader.skillName}/secrets/.env")
        if (config.nick.length > 9) throw NickTooLong("IRC_NICK length must be <= 9")
        val sessionKey = requireSessionKey()
        return config to sessionKey
    }

    private fun requireSessionKey(): String {
        val prefs = ctx.getSharedPreferences("kotlin-agent-app", Context.MODE_PRIVATE)
        val sid = prefs.getString(AppPrefsKeys.CHAT_SESSION_ID, null)?.trim()?.ifEmpty { null }
        return sid ?: "__no_session__"
    }

    private fun rejectForbiddenSecretFlags(argv: List<String>) {
        val forbidden =
            setOf(
                "--password",
                "--pass",
                "--key",
                "--secret",
                "--token",
                "--apikey",
                "--api-key",
            )
        argv.forEach { a ->
            val lower = a.trim().lowercase()
            if (forbidden.contains(lower)) {
                throw ForbiddenArg("forbidden secret flag: $a (use .env secrets instead)")
            }
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

    private fun sessionDir(sessionKey: String): File {
        val safe = sessionKey.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(agentsRoot, "workspace/irc/sessions/$safe")
    }

    private fun tailLines(
        file: File,
        maxLines: Int,
    ): List<String> {
        if (maxLines <= 0) return emptyList()
        if (!file.exists() || !file.isFile) return emptyList()
        val deque = ArrayDeque<String>(maxLines + 1)
        return try {
            file.forEachLine(Charsets.UTF_8) { line ->
                val s = line.trimEnd()
                if (s.isBlank()) return@forEachLine
                if (deque.size >= maxLines) deque.removeFirst()
                deque.addLast(s)
            }
            deque.toList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun appendOutboundJsonl(
        sessionKey: String,
        channel: String,
        nick: String,
        text: String,
        tsMs: Long,
    ) {
        try {
            val dir = sessionDir(sessionKey)
            dir.mkdirs()
            val f = File(dir, "outbound.jsonl")
            val id = UUID.randomUUID().toString().replace("-", "")
            val safeText = text.replace("\u0000", "").replace("\r\n", "\n").take(16_000)
            val jsonLine =
                """{"id":${escapeJson(id)},"ts_ms":$tsMs,"channel":${escapeJson(channel)},"nick":${escapeJson(nick)},"text":${escapeJson(safeText)}}"""
            f.appendText(jsonLine + "\n", Charsets.UTF_8)
        } catch (_: Throwable) {
            // best-effort
        }
    }

    private fun escapeJson(s: String): String {
        val escaped =
            s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
        return "\"$escaped\""
    }
}
