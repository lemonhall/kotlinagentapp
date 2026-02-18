package com.lsl.kotlin_agent_app.agent.tools.terminal.commands.rss

import android.content.Context
import com.lsl.kotlin_agent_app.agent.tools.rss.RssClient
import com.lsl.kotlin_agent_app.agent.tools.rss.RssParseException
import com.lsl.kotlin_agent_app.agent.tools.rss.RssParser
import com.lsl.kotlin_agent_app.agent.tools.rss.RssStore
import com.lsl.kotlin_agent_app.agent.tools.rss.RssSubscription
import com.lsl.kotlin_agent_app.agent.tools.rss.parseRetryAfterMsOrNull
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalArtifact
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommandOutput
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.PathEscapesAgentsRoot
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.optionalFlagValue
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.parseIntFlag
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.relPath
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.requireFlagValue
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.resolveWithinAgents
import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class RssCommand(
    appContext: Context,
) : TerminalCommand {
    private val ctx = appContext.applicationContext
    private val agentsRoot: File = File(ctx.filesDir, ".agents").canonicalFile
    private val store = RssStore(agentsRoot = agentsRoot)
    private val client = RssClient()

    override val name: String = "rss"
    override val description: String = "RSS/Atom subscriptions and fetch stored in .agents/workspace/rss (add/list/remove/fetch)."

    override suspend fun run(
        argv: List<String>,
        stdin: String?,
    ): TerminalCommandOutput {
        if (argv.size < 2) return invalidArgs("missing subcommand")
        return try {
            when (argv[1].lowercase()) {
                "add" -> handleAdd(argv)
                "list" -> handleList(argv)
                "remove" -> handleRemove(argv)
                "fetch" -> handleFetch(argv)
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
        } catch (t: IllegalArgumentException) {
            invalidArgs(t.message ?: "invalid args")
        } catch (t: RssParseException) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "parse error"),
                errorCode = "ParseError",
                errorMessage = t.message,
            )
        } catch (t: Throwable) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "rss error"),
                errorCode = "NetworkError",
                errorMessage = t.message,
            )
        }
    }

    private fun handleAdd(argv: List<String>): TerminalCommandOutput {
        val name = requireFlagValue(argv, "--name").trim()
        val url = requireFlagValue(argv, "--url").trim()
        validateHttpUrl(url)
        val nowMs = System.currentTimeMillis()
        val sub = store.upsertSubscription(name = name, url = url, nowMs = nowMs)
        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("rss add"))
                put("name", JsonPrimitive(sub.name))
                put("url", JsonPrimitive(sub.url))
            }
        return TerminalCommandOutput(
            exitCode = 0,
            stdout = "rss add: ${sub.name}",
            result = result,
        )
    }

    private fun handleList(argv: List<String>): TerminalCommandOutput {
        val max = parseIntFlag(argv, "--max", defaultValue = 50).coerceIn(0, 500)
        val outRel = optionalFlagValue(argv, "--out")?.trim()?.takeIf { it.isNotBlank() }
        val outFile = outRel?.let { resolveWithinAgents(agentsRoot, it) }

        val full = store.readSubscriptionsFullJson()
        val countTotal = full.size

        if (outFile != null) {
            outFile.parentFile?.mkdirs()
            outFile.writeText(full.toString() + "\n", Charsets.UTF_8)
            val artifactPath = ".agents/" + relPath(agentsRoot, outFile)
            val result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("rss list"))
                    put("count_total", JsonPrimitive(countTotal))
                    put("out", JsonPrimitive(outRel))
                }
            return TerminalCommandOutput(
                exitCode = 0,
                stdout = "rss list: $countTotal subscriptions (written to $artifactPath)",
                result = result,
                artifacts =
                    listOf(
                        TerminalArtifact(
                            path = artifactPath,
                            mime = "application/json",
                            description = "rss subscriptions (full)",
                        ),
                    ),
            )
        }

        val items = store.listSubscriptionsSummary(max = max)
        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("rss list"))
                put("count_total", JsonPrimitive(countTotal))
                put("count_emitted", JsonPrimitive(items.size))
                put("items", buildJsonArray { items.forEach { add(it) } })
            }
        return TerminalCommandOutput(
            exitCode = 0,
            stdout = "rss list: $countTotal subscriptions (emitted ${items.size})",
            result = result,
        )
    }

    private fun handleRemove(argv: List<String>): TerminalCommandOutput {
        val name = requireFlagValue(argv, "--name").trim()
        val ok = store.removeSubscription(name = name)
        if (!ok) return notFound("rss remove", "subscription not found: $name")
        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("rss remove"))
                put("name", JsonPrimitive(name))
            }
        return TerminalCommandOutput(
            exitCode = 0,
            stdout = "rss remove: $name",
            result = result,
        )
    }

    private suspend fun handleFetch(argv: List<String>): TerminalCommandOutput {
        val maxItems = parseIntFlag(argv, "--max-items", defaultValue = 20).coerceIn(0, 500)
        val name = optionalFlagValue(argv, "--name")?.trim()?.takeIf { it.isNotBlank() }
        val urlFlag = optionalFlagValue(argv, "--url")?.trim()?.takeIf { it.isNotBlank() }
        val outRel = optionalFlagValue(argv, "--out")?.trim()?.takeIf { it.isNotBlank() }
        val outFile = outRel?.let { resolveWithinAgents(agentsRoot, it) }

        if (name.isNullOrBlank() && urlFlag.isNullOrBlank()) {
            throw IllegalArgumentException("missing --name or --url")
        }

        val sub: RssSubscription? = name?.let { store.getSubscriptionByName(it) }
        if (name != null && sub == null && urlFlag.isNullOrBlank()) {
            return notFound("rss fetch", "subscription not found: $name")
        }

        val url = (urlFlag ?: sub?.url).orEmpty()
        validateHttpUrl(url)
        val urlHttp = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("invalid url: $url")

        val prevState = name?.let { store.getFetchStateByName(it) }
        val resp = client.get(url = urlHttp, etag = prevState?.etag, lastModified = prevState?.lastModified)

        if (resp.statusCode == 429) {
            val retryAfterMs = parseRetryAfterMsOrNull(resp.headers)
            return rateLimited(command = "rss fetch", retryAfterMs = retryAfterMs)
        }
        if (resp.statusCode == 304) {
            if (name != null) {
                store.upsertFetchState(
                    prevState?.copy(
                        url = url,
                        lastFetchMs = System.currentTimeMillis(),
                        lastStatus = 304,
                    )
                        ?: com.lsl.kotlin_agent_app.agent.tools.rss.RssFetchState(
                            name = name,
                            url = url,
                            lastFetchMs = System.currentTimeMillis(),
                            lastStatus = 304,
                        ),
                )
            }
            val result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("rss fetch"))
                    if (name != null) put("name", JsonPrimitive(name))
                    put("url", JsonPrimitive(url))
                    put("http_status", JsonPrimitive(304))
                    put("count_total", JsonPrimitive(0))
                    put("count_emitted", JsonPrimitive(0))
                }
            return TerminalCommandOutput(
                exitCode = 0,
                stdout = "rss fetch: not modified (304)",
                result = result,
            )
        }
        if (resp.statusCode < 200 || resp.statusCode >= 300) {
            return httpError(command = "rss fetch", statusCode = resp.statusCode)
        }

        val itemsAll = RssParser.parse(resp.bodyText)
        val items = itemsAll.take(maxItems)

        if (name != null) {
            val etag = resp.headers["ETag"] ?: resp.headers["etag"] ?: resp.headers.entries.firstOrNull { it.key.equals("ETag", ignoreCase = true) }?.value
            val lastModified =
                resp.headers["Last-Modified"]
                    ?: resp.headers["last-modified"]
                    ?: resp.headers.entries.firstOrNull { it.key.equals("Last-Modified", ignoreCase = true) }?.value
            store.upsertFetchState(
                com.lsl.kotlin_agent_app.agent.tools.rss.RssFetchState(
                    name = name,
                    url = url,
                    etag = etag?.trim()?.takeIf { it.isNotBlank() },
                    lastModified = lastModified?.trim()?.takeIf { it.isNotBlank() },
                    lastFetchMs = System.currentTimeMillis(),
                    lastStatus = resp.statusCode,
                ),
            )
        }

        if (outFile != null) {
            val json =
                buildJsonArray {
                    for (it in itemsAll) {
                        add(
                            buildJsonObject {
                                if (!it.title.isNullOrBlank()) put("title", JsonPrimitive(it.title))
                                if (!it.link.isNullOrBlank()) put("link", JsonPrimitive(it.link))
                                if (!it.guid.isNullOrBlank()) put("guid", JsonPrimitive(it.guid))
                                if (!it.author.isNullOrBlank()) put("author", JsonPrimitive(it.author))
                                if (!it.publishedAt.isNullOrBlank()) put("published_at", JsonPrimitive(it.publishedAt))
                                if (!it.summary.isNullOrBlank()) put("summary", JsonPrimitive(it.summary))
                            },
                        )
                    }
                }
            outFile.parentFile?.mkdirs()
            outFile.writeText(json.toString() + "\n", Charsets.UTF_8)

            val artifactPath = ".agents/" + relPath(agentsRoot, outFile)
            val result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("rss fetch"))
                    if (name != null) put("name", JsonPrimitive(name))
                    put("url", JsonPrimitive(url))
                    put("count_total", JsonPrimitive(itemsAll.size))
                    put("count_emitted", JsonPrimitive(items.size))
                    put("out", JsonPrimitive(outRel))
                }
            return TerminalCommandOutput(
                exitCode = 0,
                stdout = "rss fetch: ${itemsAll.size} items (written to $artifactPath)",
                result = result,
                artifacts =
                    listOf(
                        TerminalArtifact(
                            path = artifactPath,
                            mime = "application/json",
                            description = "rss items output (full)",
                        ),
                    ),
            )
        }

        val summaries =
            buildJsonArray {
                for (it in items) {
                    add(
                        buildJsonObject {
                            if (!it.title.isNullOrBlank()) put("title", JsonPrimitive(it.title))
                            if (!it.publishedAt.isNullOrBlank()) put("published_at", JsonPrimitive(it.publishedAt))
                            if (!it.link.isNullOrBlank()) put("link", JsonPrimitive(it.link))
                        },
                    )
                }
            }
        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("rss fetch"))
                if (name != null) put("name", JsonPrimitive(name))
                put("url", JsonPrimitive(url))
                put("count_total", JsonPrimitive(itemsAll.size))
                put("count_emitted", JsonPrimitive(items.size))
                put("items", summaries)
            }

        return TerminalCommandOutput(
            exitCode = 0,
            stdout = "rss fetch: ${itemsAll.size} items (emitted ${items.size})",
            result = result,
        )
    }

    private fun validateHttpUrl(url: String) {
        val u = url.trim()
        val parsed = u.toHttpUrlOrNull() ?: throw IllegalArgumentException("invalid url: $url")
        val scheme = parsed.scheme.lowercase()
        if (scheme != "http" && scheme != "https") throw IllegalArgumentException("unsupported url scheme: $scheme")
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

    private fun notFound(
        command: String,
        message: String,
    ): TerminalCommandOutput {
        return TerminalCommandOutput(
            exitCode = 2,
            stdout = "",
            stderr = message,
            errorCode = "NotFound",
            errorMessage = message,
            result =
                buildJsonObject {
                    put("ok", JsonPrimitive(false))
                    put("command", JsonPrimitive(command))
                },
        )
    }

    private fun httpError(
        command: String,
        statusCode: Int,
    ): TerminalCommandOutput {
        return TerminalCommandOutput(
            exitCode = 2,
            stdout = "",
            stderr = "http $statusCode",
            errorCode = "HttpError",
            errorMessage = "http $statusCode",
            result =
                buildJsonObject {
                    put("ok", JsonPrimitive(false))
                    put("command", JsonPrimitive(command))
                    put("status", JsonPrimitive(statusCode))
                },
        )
    }

    private fun rateLimited(
        command: String,
        retryAfterMs: Long?,
    ): TerminalCommandOutput {
        return TerminalCommandOutput(
            exitCode = 2,
            stdout = "",
            stderr = "rate limited",
            errorCode = "RateLimited",
            errorMessage = "rate limited",
            result =
                buildJsonObject {
                    put("ok", JsonPrimitive(false))
                    put("command", JsonPrimitive(command))
                    if (retryAfterMs != null) put("retry_after_ms", JsonPrimitive(retryAfterMs))
                },
        )
    }
}
