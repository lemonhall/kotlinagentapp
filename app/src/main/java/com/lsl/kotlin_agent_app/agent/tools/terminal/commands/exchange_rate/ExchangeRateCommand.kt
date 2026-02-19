package com.lsl.kotlin_agent_app.agent.tools.terminal.commands.exchange_rate

import android.content.Context
import com.lsl.kotlin_agent_app.agent.tools.exchange_rate.ExchangeRateClient
import com.lsl.kotlin_agent_app.agent.tools.exchange_rate.ExchangeRateHttpException
import com.lsl.kotlin_agent_app.agent.tools.exchange_rate.ExchangeRateNetworkException
import com.lsl.kotlin_agent_app.agent.tools.exchange_rate.ExchangeRateRemoteErrorException
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalArtifact
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommandOutput
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.PathEscapesAgentsRoot
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.hasFlag
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.optionalFlagValue
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.relPath
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.requireFlagValue
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.resolveWithinAgents
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class ExchangeRateCommand(
    appContext: Context,
) : TerminalCommand {
    private val ctx = appContext.applicationContext
    private val agentsRoot = File(ctx.filesDir, ".agents").canonicalFile
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val client = ExchangeRateClient()
    private val cacheFallbackTtlMs: Long = 24L * 60L * 60L * 1000L

    override val name: String = "exchange-rate"
    override val description: String = "ExchangeRate-API open access FX rates CLI (latest/convert) with local cache."

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
                "latest" -> handleLatest(argv)
                "convert" -> handleConvert(argv)
                else -> invalidArgs("unknown subcommand: $subRaw")
            }
        } catch (t: PathEscapesAgentsRoot) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "path escapes .agents root"),
                errorCode = "PathEscapesAgentsRoot",
                errorMessage = t.message,
            )
        } catch (t: ExchangeRateRemoteErrorException) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "remote error"),
                errorCode = "RemoteError",
                errorMessage = t.message,
                result =
                    buildJsonObject {
                        put("ok", JsonPrimitive(false))
                        put("command", JsonPrimitive("exchange-rate ${argv.getOrNull(1).orEmpty()}"))
                        if (t.errorType != null) put("error_type", JsonPrimitive(t.errorType))
                    },
            )
        } catch (t: ExchangeRateHttpException) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "http error"),
                errorCode = "RemoteHttpError",
                errorMessage = t.message,
                result =
                    buildJsonObject {
                        put("ok", JsonPrimitive(false))
                        put("command", JsonPrimitive("exchange-rate ${argv.getOrNull(1).orEmpty()}"))
                        put("status", JsonPrimitive(t.statusCode))
                    },
            )
        } catch (t: ExchangeRateNetworkException) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "network error"),
                errorCode = "NetworkError",
                errorMessage = t.message,
            )
        } catch (t: IllegalArgumentException) {
            invalidArgs(t.message ?: "invalid args")
        } catch (t: Throwable) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "exchange-rate error"),
                errorCode = "Error",
                errorMessage = t.message,
            )
        }
    }

    private fun help(sub: String?): TerminalCommandOutput {
        val usage =
            when (sub) {
                null ->
                    """
                    exchange-rate: FX rates (daily) and conversions via open.er-api.com (no API key).

                    Usage:
                      exchange-rate latest --base <ISO4217> [--symbols USD,EUR] [--out <agents-relpath>] [--no-cache]
                      exchange-rate convert --from <ISO4217> --to <ISO4217> --amount <decimal> [--precision 0..10] [--no-cache]

                    Subcommands:
                      latest    Fetch latest rates for a base currency (default emits common subset).
                      convert   Convert amount from one currency to another using latest rates.

                    Examples:
                      exchange-rate latest --base CNY --symbols USD,EUR
                      exchange-rate latest --base CNY --out artifacts/exchange-rate/latest-CNY.json
                      exchange-rate convert --from CNY --to USD --amount 100 --precision 4
                    """.trimIndent()
                "latest" ->
                    """
                    exchange-rate latest

                    Usage:
                      exchange-rate latest --base <ISO4217> [--symbols USD,EUR] [--out <agents-relpath>] [--no-cache]

                    Notes:
                      - Without --symbols and without --out, emits a small default subset.
                      - With --out, writes full response JSON under .agents/ and returns artifacts[].
                    """.trimIndent()
                "convert" ->
                    """
                    exchange-rate convert

                    Usage:
                      exchange-rate convert --from <ISO4217> --to <ISO4217> --amount <decimal> [--precision 0..10] [--no-cache]

                    Notes:
                      - Amount must be a non-negative decimal.
                    """.trimIndent()
                else -> return invalidArgs("unknown help target: $sub")
            }

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put(
                    "command",
                    JsonPrimitive(
                        when (sub) {
                            null -> "exchange-rate help"
                            else -> "exchange-rate $sub help"
                        },
                    ),
                )
                put("usage", JsonPrimitive(usage))
            }
        return TerminalCommandOutput(exitCode = 0, stdout = usage, result = result)
    }

    private data class CachedLatest(
        val json: JsonObject,
        val cached: Boolean,
        val stale: Boolean,
    )

    private suspend fun getLatestWithCache(
        baseCode: String,
        noCache: Boolean,
    ): CachedLatest {
        val base = baseCode.trim().uppercase()
        require(base.matches(Regex("^[A-Z]{3}$"))) { "invalid currency code: $baseCode" }

        val cacheFile = File(agentsRoot, "cache/exchange-rate/latest-$base.json")
        val cachedObj = readCacheOrNull(cacheFile)
        val nowMs = System.currentTimeMillis()

        if (!noCache && cachedObj != null) {
            val nextMs = parseNextUpdateMsOrNull(cachedObj)
            val isValid =
                when {
                    nextMs != null -> nowMs < nextMs
                    cacheFile.lastModified() > 0L -> nowMs - cacheFile.lastModified() < cacheFallbackTtlMs
                    else -> false
                }
            if (isValid) {
                return CachedLatest(json = cachedObj, cached = true, stale = false)
            }
        }

        return try {
            val (obj, _) = client.getLatest(baseCode = base)
            if (!noCache) writeCacheBestEffort(cacheFile, obj)
            CachedLatest(json = obj, cached = false, stale = false)
        } catch (t: Throwable) {
            if (noCache) throw t
            if (cachedObj != null) {
                val nextMs = parseNextUpdateMsOrNull(cachedObj)
                val expired =
                    when {
                        nextMs != null -> nowMs >= nextMs
                        cacheFile.lastModified() > 0L -> nowMs - cacheFile.lastModified() >= cacheFallbackTtlMs
                        else -> true
                    }
                CachedLatest(json = cachedObj, cached = true, stale = expired)
            } else {
                throw t
            }
        }
    }

    private fun readCacheOrNull(file: File): JsonObject? {
        if (!file.exists() || !file.isFile) return null
        val text =
            try {
                file.readText(Charsets.UTF_8)
            } catch (_: Throwable) {
                return null
            }
        val el =
            try {
                json.parseToJsonElement(text)
            } catch (_: Throwable) {
                return null
            }
        return runCatching { el.jsonObject }.getOrNull()
    }

    private fun writeCacheBestEffort(
        file: File,
        obj: JsonObject,
    ) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(obj.toString() + "\n", Charsets.UTF_8)
        } catch (_: Throwable) {
            // Best-effort: cache failure must not break command.
        }
    }

    private fun parseNextUpdateMsOrNull(obj: JsonObject): Long? {
        val raw = obj["time_next_update_utc"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (raw.isBlank()) return null
        return parseUtcRfc1123MsOrNull(raw)
    }

    private fun parseUtcRfc1123MsOrNull(s: String): Long? {
        return try {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
            fmt.isLenient = false
            val d: Date = fmt.parse(s) ?: return null
            d.time
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun handleLatest(argv: List<String>): TerminalCommandOutput {
        val base = requireFlagValue(argv, "--base").trim().uppercase()
        val symbolsRaw = optionalFlagValue(argv, "--symbols")?.trim()
        val outRel = optionalFlagValue(argv, "--out")?.trim()?.takeIf { it.isNotBlank() }
        val outFile = outRel?.let { resolveWithinAgents(agentsRoot, it) }
        val noCache = hasFlag(argv, "--no-cache")

        val wantedSymbols =
            symbolsRaw
                ?.split(',')
                ?.map { it.trim().uppercase() }
                ?.filter { it.isNotBlank() }
                ?.also { codes ->
                    for (c in codes) require(c.matches(Regex("^[A-Z]{3}$"))) { "invalid currency code: $c" }
                }

        val cached = getLatestWithCache(baseCode = base, noCache = noCache)
        val obj = cached.json

        val baseCode = obj["base_code"]?.jsonPrimitive?.content?.trim().orEmpty()
        val last = obj["time_last_update_utc"]?.jsonPrimitive?.content?.trim().orEmpty()
        val next = obj["time_next_update_utc"]?.jsonPrimitive?.content?.trim().orEmpty()
        val ratesObj = obj["rates"]?.jsonObject ?: throw IllegalArgumentException("missing rates")
        val ratesTotal = ratesObj.size

        val targets =
            when {
                wantedSymbols != null -> wantedSymbols
                outRel != null -> null // caller wants full response on disk; stdout/result only summary
                else -> listOf("USD", "EUR", "JPY", "GBP", "HKD")
            }

        val ratesOut: JsonObject? =
            targets?.let { codes ->
                val missing = codes.filter { !ratesObj.containsKey(it) }
                if (missing.isNotEmpty()) {
                    return unknownCurrency(command = "exchange-rate latest", message = "unknown currency: ${missing.first()}")
                }
                buildJsonObject {
                    for (c in codes) {
                        put(c, ratesObj[c]!!)
                    }
                }
            }

        if (outFile != null) {
            try {
                outFile.parentFile?.mkdirs()
                outFile.writeText(obj.toString() + "\n", Charsets.UTF_8)
            } catch (t: Throwable) {
                return cacheWriteError(command = "exchange-rate latest", message = "failed to write --out: $outRel", cause = t)
            }

            val artifactPath = ".agents/" + relPath(agentsRoot, outFile)
            val stdout = "exchange-rate latest $base: $ratesTotal rates (written to $artifactPath)"
            val result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("exchange-rate latest"))
                    put("base_code", JsonPrimitive(baseCode.ifBlank { base }))
                    put("time_last_update_utc", JsonPrimitive(last))
                    put("time_next_update_utc", JsonPrimitive(next))
                    put("cached", JsonPrimitive(cached.cached))
                    if (cached.stale) put("stale", JsonPrimitive(true))
                    put("rates_total", JsonPrimitive(ratesTotal))
                    if (ratesOut != null) put("rates", ratesOut)
                    put("out", JsonPrimitive(outRel))
                    put("source_url", JsonPrimitive("https://open.er-api.com/v6/latest/${base}"))
                }
            return TerminalCommandOutput(
                exitCode = 0,
                stdout = stdout,
                result = result,
                artifacts =
                    listOf(
                        TerminalArtifact(
                            path = artifactPath,
                            mime = "application/json",
                            description = "exchange-rate latest full output (may be large).",
                        ),
                    ),
            )
        }

        val emittedKeys = ratesOut?.keys?.joinToString(",").orEmpty()
        val stdout =
            when {
                wantedSymbols != null -> "exchange-rate latest $base: ${wantedSymbols.size} rates ($emittedKeys)"
                else -> "exchange-rate latest $base: ${ratesOut?.size ?: 0} shown / $ratesTotal total ($emittedKeys)"
            }

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("exchange-rate latest"))
                put("base_code", JsonPrimitive(baseCode.ifBlank { base }))
                put("time_last_update_utc", JsonPrimitive(last))
                put("time_next_update_utc", JsonPrimitive(next))
                put("cached", JsonPrimitive(cached.cached))
                if (cached.stale) put("stale", JsonPrimitive(true))
                put("rates_total", JsonPrimitive(ratesTotal))
                if (ratesOut != null) put("rates", ratesOut)
                put("source_url", JsonPrimitive("https://open.er-api.com/v6/latest/${base}"))
            }
        return TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
    }

    private suspend fun handleConvert(argv: List<String>): TerminalCommandOutput {
        val from = requireFlagValue(argv, "--from").trim().uppercase()
        val to = requireFlagValue(argv, "--to").trim().uppercase()
        val amountRaw = requireFlagValue(argv, "--amount").trim()
        val precision = (optionalFlagValue(argv, "--precision") ?: "6").trim().toIntOrNull()?.coerceIn(0, 10)
            ?: throw IllegalArgumentException("invalid --precision")
        val noCache = hasFlag(argv, "--no-cache")

        require(from.matches(Regex("^[A-Z]{3}$"))) { "invalid currency code: $from" }
        require(to.matches(Regex("^[A-Z]{3}$"))) { "invalid currency code: $to" }

        val amount =
            try {
                BigDecimal(amountRaw)
            } catch (_: Throwable) {
                throw IllegalArgumentException("invalid --amount: $amountRaw")
            }
        if (amount < BigDecimal.ZERO) throw IllegalArgumentException("--amount must be non-negative")

        val cached = getLatestWithCache(baseCode = from, noCache = noCache)
        val obj = cached.json
        val last = obj["time_last_update_utc"]?.jsonPrimitive?.content?.trim().orEmpty()
        val next = obj["time_next_update_utc"]?.jsonPrimitive?.content?.trim().orEmpty()
        val ratesObj = obj["rates"]?.jsonObject ?: throw IllegalArgumentException("missing rates")
        val rateEl = ratesObj[to] ?: return unknownCurrency(command = "exchange-rate convert", message = "unknown currency: $to")
        val rate =
            try {
                BigDecimal(rateEl.jsonPrimitive.content)
            } catch (_: Throwable) {
                throw IllegalArgumentException("invalid rate for $to")
            }

        val converted =
            amount
                .multiply(rate)
                .setScale(precision, RoundingMode.HALF_UP)

        val stdout = "exchange-rate convert: $amountRaw $from -> ${converted.toPlainString()} $to (rate=${rate.stripTrailingZeros().toPlainString()})"
        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("exchange-rate convert"))
                put("from", JsonPrimitive(from))
                put("to", JsonPrimitive(to))
                put("amount", JsonPrimitive(amount.toPlainString()))
                put("rate", JsonPrimitive(rate.stripTrailingZeros().toPlainString()))
                put("converted_amount", JsonPrimitive(converted.toPlainString()))
                put("time_last_update_utc", JsonPrimitive(last))
                put("time_next_update_utc", JsonPrimitive(next))
                put("cached", JsonPrimitive(cached.cached))
                if (cached.stale) put("stale", JsonPrimitive(true))
            }
        return TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
    }

    private fun unknownCurrency(
        command: String,
        message: String,
    ): TerminalCommandOutput {
        return TerminalCommandOutput(
            exitCode = 2,
            stdout = "",
            stderr = message,
            errorCode = "UnknownCurrency",
            errorMessage = message,
            result =
                buildJsonObject {
                    put("ok", JsonPrimitive(false))
                    put("command", JsonPrimitive(command))
                },
        )
    }

    private fun cacheWriteError(
        command: String,
        message: String,
        cause: Throwable,
    ): TerminalCommandOutput {
        return TerminalCommandOutput(
            exitCode = 2,
            stdout = "",
            stderr = (cause.message ?: message),
            errorCode = "CacheWriteError",
            errorMessage = message,
            result =
                buildJsonObject {
                    put("ok", JsonPrimitive(false))
                    put("command", JsonPrimitive(command))
                },
        )
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
