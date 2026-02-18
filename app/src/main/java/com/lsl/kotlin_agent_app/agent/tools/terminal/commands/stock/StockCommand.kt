package com.lsl.kotlin_agent_app.agent.tools.terminal.commands.stock

import android.content.Context
import com.lsl.kotlin_agent_app.agent.tools.stock.FinnhubClient
import com.lsl.kotlin_agent_app.agent.tools.stock.FinnhubHttpException
import com.lsl.kotlin_agent_app.agent.tools.stock.FinnhubNetworkException
import com.lsl.kotlin_agent_app.agent.tools.stock.StockRateLimiter
import com.lsl.kotlin_agent_app.agent.tools.stock.StockSecretsLoader
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalArtifact
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommandOutput
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.PathEscapesAgentsRoot
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.optionalFlagValue
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.relPath
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.requireFlagValue
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.resolveWithinAgents
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class MissingCredentials(
    message: String,
) : IllegalArgumentException(message)

internal class OutRequired(
    message: String,
) : IllegalArgumentException(message)

internal class NotSupported(
    message: String,
) : IllegalArgumentException(message)

internal class StockCommand(
    appContext: Context,
) : TerminalCommand {
    private val ctx = appContext.applicationContext
    private val agentsRoot = File(ctx.filesDir, ".agents").canonicalFile

    private val rateLimiter = StockRateLimiter()
    private val finnhub = FinnhubClient()

    override val name: String = "stock"
    override val description: String =
        "Finnhub Stock REST CLI (quote/profile/symbols/news/metrics/earnings) with dotenv secrets, rate limiting, and artifacts for large outputs."

    override suspend fun run(
        argv: List<String>,
        stdin: String?,
    ): TerminalCommandOutput {
        if (argv.size < 2) return invalidArgs("missing subcommand")
        val sub = argv[1].lowercase()

        return try {
            when (sub) {
                "candle", "candles" -> throw NotSupported("candles are not supported")
                "quote" -> handleQuote(argv)
                "profile" -> handleProfile(argv)
                "symbols" -> handleSymbols(argv)
                "company-news" -> handleCompanyNews(argv)
                "news" -> handleMarketNews(argv)
                "rec" -> handleRecommendation(argv)
                "metric" -> handleMetric(argv)
                "earnings" -> handleEarnings(argv)
                else -> invalidArgs("unknown subcommand: ${argv[1]}")
            }
        } catch (t: OutRequired) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "missing --out"),
                errorCode = "OutRequired",
                errorMessage = t.message,
            )
        } catch (t: MissingCredentials) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "missing credentials"),
                errorCode = "MissingCredentials",
                errorMessage = t.message,
            )
        } catch (t: PathEscapesAgentsRoot) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "path escapes .agents root"),
                errorCode = "PathEscapesAgentsRoot",
                errorMessage = t.message,
            )
        } catch (t: NotSupported) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "not supported"),
                errorCode = "NotSupported",
                errorMessage = t.message,
            )
        } catch (t: FinnhubNetworkException) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "network error"),
                errorCode = "NetworkError",
                errorMessage = t.message,
            )
        } catch (t: FinnhubHttpException) {
            if (t.statusCode == 429) {
                val retryAfterMs = parseRetryAfterMsOrNull(t.headers)
                TerminalCommandOutput(
                    exitCode = 2,
                    stdout = "",
                    stderr = "rate limited",
                    errorCode = "RateLimited",
                    errorMessage = t.message,
                    result =
                        buildJsonObject {
                            put("ok", JsonPrimitive(false))
                            put("command", JsonPrimitive("stock ${argv.getOrNull(1).orEmpty()}"))
                            put("status", JsonPrimitive(429))
                            if (retryAfterMs != null) put("retry_after_ms", JsonPrimitive(retryAfterMs))
                        },
                )
            } else {
                TerminalCommandOutput(
                    exitCode = 2,
                    stdout = "",
                    stderr = (t.message ?: "http error"),
                    errorCode = "FinnhubHttpError",
                    errorMessage = t.message,
                    result =
                        buildJsonObject {
                            put("ok", JsonPrimitive(false))
                            put("command", JsonPrimitive("stock ${argv.getOrNull(1).orEmpty()}"))
                            put("status", JsonPrimitive(t.statusCode))
                        },
                )
            }
        } catch (t: IllegalArgumentException) {
            invalidArgs(t.message ?: "invalid args")
        } catch (t: Throwable) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "stock error"),
                errorCode = "StockError",
                errorMessage = t.message,
            )
        }
    }

    private fun requireSecrets(): Pair<String, String> {
        val s =
            StockSecretsLoader.loadFromAgentsRoot(agentsRoot)
                ?: throw MissingCredentials("Missing FINNHUB_API_KEY in .agents/skills/${StockSecretsLoader.skillName}/secrets/.env")
        return s.apiKey to s.baseUrl
    }

    private fun enforceRateLimit(): Long? {
        val d = rateLimiter.tryAcquire()
        if (!d.allowed) return d.retryAfterMs
        return null
    }

    private suspend fun handleQuote(argv: List<String>): TerminalCommandOutput {
        val symbol = requireFlagValue(argv, "--symbol").trim()
        val (token, baseUrl) = requireSecrets()
        val retryAfter = enforceRateLimit()
        if (retryAfter != null) return rateLimited("stock quote", retryAfter)

        val (el, _) = finnhub.getJson(baseUrl = baseUrl, path = "/quote", query = mapOf("symbol" to symbol), token = token)
        val quote = el.jsonObject
        val c = quote["c"]?.asDoubleOrNull()
        val pc = quote["pc"]?.asDoubleOrNull()
        val stdout = "$symbol quote: c=${c ?: "?"} pc=${pc ?: "?"}"
        return TerminalCommandOutput(
            exitCode = 0,
            stdout = stdout,
            result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("stock quote"))
                    put("symbol", JsonPrimitive(symbol))
                    put("quote", quote)
                },
        )
    }

    private suspend fun handleProfile(argv: List<String>): TerminalCommandOutput {
        val symbol = requireFlagValue(argv, "--symbol").trim()
        val (token, baseUrl) = requireSecrets()
        val retryAfter = enforceRateLimit()
        if (retryAfter != null) return rateLimited("stock profile", retryAfter)

        val (el, _) = finnhub.getJson(baseUrl = baseUrl, path = "/stock/profile2", query = mapOf("symbol" to symbol), token = token)
        val profile = el.jsonObject
        val name = profile.stringOrEmpty("name")
        val exchange = profile.stringOrEmpty("exchange")
        val industry = profile.stringOrEmpty("finnhubIndustry")
        val stdout = listOf(symbol, name, exchange, industry).filter { it.isNotBlank() }.joinToString(" | ").ifBlank { "$symbol profile" }
        return TerminalCommandOutput(
            exitCode = 0,
            stdout = stdout,
            result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("stock profile"))
                    put("symbol", JsonPrimitive(symbol))
                    put("profile", profile)
                },
        )
    }

    private suspend fun handleSymbols(argv: List<String>): TerminalCommandOutput {
        val exchange = requireFlagValue(argv, "--exchange").trim()
        val outRel = optionalFlagValue(argv, "--out")?.trim().orEmpty()
        if (outRel.isBlank()) throw OutRequired("stock symbols requires --out to avoid large output")
        val outFile = resolveWithinAgents(agentsRoot, outRel)

        val (token, baseUrl) = requireSecrets()
        val retryAfter = enforceRateLimit()
        if (retryAfter != null) return rateLimited("stock symbols", retryAfter)

        val (el, _) = finnhub.getJson(baseUrl = baseUrl, path = "/stock/symbol", query = mapOf("exchange" to exchange), token = token)
        val arr = (el as? JsonArray) ?: throw IllegalArgumentException("invalid response: expected json array")
        writeJson(outFile, arr)
        val rel = relPath(agentsRoot, outFile)
        val artifact =
            TerminalArtifact(
                path = ".agents/$rel",
                mime = "application/json",
                description = "stock symbols (may be large).",
            )
        return TerminalCommandOutput(
            exitCode = 0,
            stdout = "wrote ${arr.size} symbols to .agents/$rel",
            artifacts = listOf(artifact),
            result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("stock symbols"))
                    put("exchange", JsonPrimitive(exchange))
                    put("out", JsonPrimitive(outRel))
                    put("count_total", JsonPrimitive(arr.size))
                },
        )
    }

    private suspend fun handleCompanyNews(argv: List<String>): TerminalCommandOutput {
        val symbol = requireFlagValue(argv, "--symbol").trim()
        val from = requireFlagValue(argv, "--from").trim()
        val to = requireFlagValue(argv, "--to").trim()
        val outRel = optionalFlagValue(argv, "--out")?.trim()?.takeIf { it.isNotBlank() }
        val outFile = outRel?.let { resolveWithinAgents(agentsRoot, it) }

        val (token, baseUrl) = requireSecrets()
        val retryAfter = enforceRateLimit()
        if (retryAfter != null) return rateLimited("stock company-news", retryAfter)

        val (el, _) =
            finnhub.getJson(
                baseUrl = baseUrl,
                path = "/company-news",
                query = mapOf("symbol" to symbol, "from" to from, "to" to to),
                token = token,
            )
        val arr = (el as? JsonArray) ?: throw IllegalArgumentException("invalid response: expected json array")

        if (outFile != null) {
            writeJson(outFile, arr)
            val rel = relPath(agentsRoot, outFile)
            val artifact =
                TerminalArtifact(
                    path = ".agents/$rel",
                    mime = "application/json",
                    description = "stock company-news full output (may be large).",
                )
            return TerminalCommandOutput(
                exitCode = 0,
                stdout = "wrote ${arr.size} news items to .agents/$rel",
                artifacts = listOf(artifact),
                result =
                    buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("command", JsonPrimitive("stock company-news"))
                        put("symbol", JsonPrimitive(symbol))
                        put("from", JsonPrimitive(from))
                        put("to", JsonPrimitive(to))
                        put("out", JsonPrimitive(outRel))
                        put("count_total", JsonPrimitive(arr.size))
                    },
            )
        }

        val emitted = arr.take(10).mapNotNull { it as? JsonObject }
        return TerminalCommandOutput(
            exitCode = 0,
            stdout = "$symbol company-news: ${arr.size} total (showing ${emitted.size})",
            result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("stock company-news"))
                    put("symbol", JsonPrimitive(symbol))
                    put("from", JsonPrimitive(from))
                    put("to", JsonPrimitive(to))
                    put("count_total", JsonPrimitive(arr.size))
                    put(
                        "items",
                        buildJsonArray {
                            for (o in emitted) {
                                add(
                                    buildJsonObject {
                                        o["headline"]?.let { put("headline", it) }
                                        o["datetime"]?.let { put("datetime", it) }
                                        o["url"]?.let { put("url", it) }
                                    },
                                )
                            }
                        },
                    )
                },
        )
    }

    private suspend fun handleMarketNews(argv: List<String>): TerminalCommandOutput {
        val category = optionalFlagValue(argv, "--category")?.trim().takeIf { !it.isNullOrBlank() } ?: "general"
        val outRel = optionalFlagValue(argv, "--out")?.trim()?.takeIf { it.isNotBlank() }
        val outFile = outRel?.let { resolveWithinAgents(agentsRoot, it) }

        val (token, baseUrl) = requireSecrets()
        val retryAfter = enforceRateLimit()
        if (retryAfter != null) return rateLimited("stock news", retryAfter)

        val (el, _) =
            finnhub.getJson(
                baseUrl = baseUrl,
                path = "/news",
                query = mapOf("category" to category),
                token = token,
            )
        val arr = (el as? JsonArray) ?: throw IllegalArgumentException("invalid response: expected json array")

        if (outFile != null) {
            writeJson(outFile, arr)
            val rel = relPath(agentsRoot, outFile)
            val artifact =
                TerminalArtifact(
                    path = ".agents/$rel",
                    mime = "application/json",
                    description = "stock market news full output (may be large).",
                )
            return TerminalCommandOutput(
                exitCode = 0,
                stdout = "wrote ${arr.size} market news items to .agents/$rel",
                artifacts = listOf(artifact),
                result =
                    buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("command", JsonPrimitive("stock news"))
                        put("category", JsonPrimitive(category))
                        put("out", JsonPrimitive(outRel))
                        put("count_total", JsonPrimitive(arr.size))
                    },
            )
        }

        val emitted = arr.take(10).mapNotNull { it as? JsonObject }
        return TerminalCommandOutput(
            exitCode = 0,
            stdout = "market news($category): ${arr.size} total (showing ${emitted.size})",
            result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("stock news"))
                    put("category", JsonPrimitive(category))
                    put("count_total", JsonPrimitive(arr.size))
                    put(
                        "items",
                        buildJsonArray {
                            for (o in emitted) {
                                add(
                                    buildJsonObject {
                                        o["headline"]?.let { put("headline", it) }
                                        o["datetime"]?.let { put("datetime", it) }
                                        o["url"]?.let { put("url", it) }
                                    },
                                )
                            }
                        },
                    )
                },
        )
    }

    private suspend fun handleRecommendation(argv: List<String>): TerminalCommandOutput {
        val symbol = requireFlagValue(argv, "--symbol").trim()
        val (token, baseUrl) = requireSecrets()
        val retryAfter = enforceRateLimit()
        if (retryAfter != null) return rateLimited("stock rec", retryAfter)

        val (el, _) =
            finnhub.getJson(
                baseUrl = baseUrl,
                path = "/stock/recommendation",
                query = mapOf("symbol" to symbol),
                token = token,
            )
        val arr = (el as? JsonArray) ?: throw IllegalArgumentException("invalid response: expected json array")
        return TerminalCommandOutput(
            exitCode = 0,
            stdout = "$symbol rec: ${arr.size} points",
            result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("stock rec"))
                    put("symbol", JsonPrimitive(symbol))
                    put("trend", arr)
                },
        )
    }

    private suspend fun handleMetric(argv: List<String>): TerminalCommandOutput {
        val symbol = requireFlagValue(argv, "--symbol").trim()
        val metric = optionalFlagValue(argv, "--metric")?.trim().takeIf { !it.isNullOrBlank() } ?: "all"
        val outRel = optionalFlagValue(argv, "--out")?.trim()?.takeIf { it.isNotBlank() }
        val outFile = outRel?.let { resolveWithinAgents(agentsRoot, it) }

        val (token, baseUrl) = requireSecrets()
        val retryAfter = enforceRateLimit()
        if (retryAfter != null) return rateLimited("stock metric", retryAfter)

        val (el, _) =
            finnhub.getJson(
                baseUrl = baseUrl,
                path = "/stock/metric",
                query = mapOf("symbol" to symbol, "metric" to metric),
                token = token,
            )
        val obj = el.jsonObject
        if (outFile != null) {
            writeJson(outFile, obj)
            val rel = relPath(agentsRoot, outFile)
            val artifact =
                TerminalArtifact(
                    path = ".agents/$rel",
                    mime = "application/json",
                    description = "stock metric full output (may be large).",
                )
            return TerminalCommandOutput(
                exitCode = 0,
                stdout = "wrote metric($metric) for $symbol to .agents/$rel",
                artifacts = listOf(artifact),
                result =
                    buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("command", JsonPrimitive("stock metric"))
                        put("symbol", JsonPrimitive(symbol))
                        put("metric", JsonPrimitive(metric))
                        put("out", JsonPrimitive(outRel))
                    },
            )
        }

        val metricObj = obj["metric"]?.jsonObject
        val subset =
            buildJsonObject {
                fun putIfPresent(key: String) {
                    metricObj?.get(key)?.let { put(key, it) }
                }
                putIfPresent("marketCapitalization")
                putIfPresent("52WeekHigh")
                putIfPresent("52WeekLow")
                putIfPresent("beta")
                putIfPresent("peTTM")
            }
        return TerminalCommandOutput(
            exitCode = 0,
            stdout = "$symbol metric($metric)",
            result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("stock metric"))
                    put("symbol", JsonPrimitive(symbol))
                    put("metric", JsonPrimitive(metric))
                    put("metric_subset", subset)
                },
        )
    }

    private suspend fun handleEarnings(argv: List<String>): TerminalCommandOutput {
        val from = requireFlagValue(argv, "--from").trim()
        val to = requireFlagValue(argv, "--to").trim()
        val outRel = optionalFlagValue(argv, "--out")?.trim()?.takeIf { it.isNotBlank() }
        val outFile = outRel?.let { resolveWithinAgents(agentsRoot, it) }

        val (token, baseUrl) = requireSecrets()
        val retryAfter = enforceRateLimit()
        if (retryAfter != null) return rateLimited("stock earnings", retryAfter)

        val (el, _) =
            finnhub.getJson(
                baseUrl = baseUrl,
                path = "/calendar/earnings",
                query = mapOf("from" to from, "to" to to),
                token = token,
            )
        val obj = el.jsonObject
        if (outFile != null) {
            writeJson(outFile, obj)
            val rel = relPath(agentsRoot, outFile)
            val artifact =
                TerminalArtifact(
                    path = ".agents/$rel",
                    mime = "application/json",
                    description = "stock earnings calendar full output (may be large).",
                )
            return TerminalCommandOutput(
                exitCode = 0,
                stdout = "wrote earnings calendar to .agents/$rel",
                artifacts = listOf(artifact),
                result =
                    buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("command", JsonPrimitive("stock earnings"))
                        put("from", JsonPrimitive(from))
                        put("to", JsonPrimitive(to))
                        put("out", JsonPrimitive(outRel))
                    },
            )
        }

        val arr = (obj["earningsCalendar"] as? JsonArray) ?: JsonArray(emptyList())
        val emitted = arr.take(20).mapNotNull { it as? JsonObject }
        return TerminalCommandOutput(
            exitCode = 0,
            stdout = "earnings calendar: ${arr.size} total (showing ${emitted.size})",
            result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("stock earnings"))
                    put("from", JsonPrimitive(from))
                    put("to", JsonPrimitive(to))
                    put("count_total", JsonPrimitive(arr.size))
                    put(
                        "items",
                        buildJsonArray {
                            for (o in emitted) {
                                add(
                                    buildJsonObject {
                                        o["symbol"]?.let { put("symbol", it) }
                                        o["date"]?.let { put("date", it) }
                                    },
                                )
                            }
                        },
                    )
                },
        )
    }

    private fun rateLimited(
        command: String,
        retryAfterMs: Long,
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
                    put("retry_after_ms", JsonPrimitive(retryAfterMs))
                },
        )
    }

    private fun writeJson(
        file: File,
        json: JsonElement,
    ) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        file.writeText(json.toString() + "\n", Charsets.UTF_8)
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

    private fun JsonElement.asDoubleOrNull(): Double? =
        runCatching { this.jsonPrimitive.content.toDoubleOrNull() }.getOrNull()

    private fun JsonObject.stringOrEmpty(key: String): String =
        (this[key] as? JsonPrimitive)?.content?.trim().orEmpty()

    private fun parseRetryAfterMsOrNull(headers: Map<String, String>): Long? {
        val v =
            headers["Retry-After"]
                ?: headers["retry-after"]
                ?: headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value
        val seconds = v?.trim()?.toLongOrNull() ?: return null
        return (seconds * 1000L).coerceAtLeast(0L)
    }
}
