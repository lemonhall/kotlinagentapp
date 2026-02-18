package com.lsl.kotlin_agent_app.agent.tools.terminal.commands.ledger

import android.content.Context
import com.lsl.kotlin_agent_app.agent.tools.ledger.LedgerStore
import com.lsl.kotlin_agent_app.agent.tools.ledger.NotInitialized
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalArtifact
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommandOutput
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.ConfirmRequired
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.PathEscapesAgentsRoot
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.hasFlag
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.optionalFlagValue
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.parseIntFlag
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.relPath
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.requireFlagValue
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.resolveWithinAgents
import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal class LedgerCommand(
    appContext: Context,
) : TerminalCommand {
    private val ctx = appContext.applicationContext
    private val agentsRoot: File = File(ctx.filesDir, ".agents").canonicalFile
    private val store = LedgerStore(agentsRoot = agentsRoot)

    override val name: String = "ledger"
    override val description: String = "Personal simple ledger stored in .agents/workspace/ledger (init/add/list/summary)."

    override suspend fun run(
        argv: List<String>,
        stdin: String?,
    ): TerminalCommandOutput {
        if (argv.size < 2) return invalidArgs("missing subcommand")
        return try {
            when (argv[1].lowercase()) {
                "init" -> handleInit(argv)
                "add" -> handleAdd(argv)
                "list" -> handleList(argv)
                "summary" -> handleSummary(argv)
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
        } catch (t: ConfirmRequired) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "missing --confirm"),
                errorCode = "ConfirmRequired",
                errorMessage = t.message,
            )
        } catch (t: NotInitialized) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "not initialized"),
                errorCode = "NotInitialized",
                errorMessage = t.message,
            )
        } catch (t: IllegalArgumentException) {
            invalidArgs(t.message ?: "invalid args")
        } catch (t: Throwable) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "ledger error"),
                errorCode = "LedgerError",
                errorMessage = t.message,
            )
        }
    }

    private fun handleInit(argv: List<String>): TerminalCommandOutput {
        val confirm = hasFlag(argv, "--confirm")
        val res = store.init(confirm = confirm)
        val stdout =
            if (res.created) {
                "ledger initialized: ${res.workspaceDir}"
            } else {
                "ledger reset: ${res.workspaceDir}"
            }
        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("ledger init"))
                put("workspace_dir", JsonPrimitive(res.workspaceDir))
                put("created", JsonPrimitive(res.created))
            }
        return TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
    }

    private fun handleAdd(argv: List<String>): TerminalCommandOutput {
        val type = requireFlagValue(argv, "--type").trim().lowercase()
        val amount = requireFlagValue(argv, "--amount").trim()
        val note = optionalFlagValue(argv, "--note")?.trim()?.takeIf { it.isNotBlank() }
        val at = optionalFlagValue(argv, "--at")?.trim()?.takeIf { it.isNotBlank() }

        val addRes =
            when (type) {
                "expense", "income" -> {
                    val category = requireFlagValue(argv, "--category").trim()
                    val account = requireFlagValue(argv, "--account").trim()
                    store.addExpenseOrIncome(type = type, amountYuanRaw = amount, category = category, account = account, note = note, atIso = at)
                }
                "transfer" -> {
                    val from = requireFlagValue(argv, "--from").trim()
                    val to = requireFlagValue(argv, "--to").trim()
                    store.addTransfer(amountYuanRaw = amount, fromAccount = from, toAccount = to, note = note, atIso = at)
                }
                else -> throw IllegalArgumentException("invalid --type: $type")
            }

        val stdout =
            buildString {
                append("ledger add: ok ")
                append(type)
                append(" ")
                append(addRes.amountYuan)
                append(" CNY")
                append(" (")
                append(addRes.amountFen)
                append(" fen)")
                append(" @ ")
                append(addRes.at)
                append(" id=")
                append(addRes.txId)
            }

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("ledger add"))
                put("tx_id", JsonPrimitive(addRes.txId))
                put("type", JsonPrimitive(type))
                put("amount_yuan", JsonPrimitive(addRes.amountYuan))
                put("amount_fen", JsonPrimitive(addRes.amountFen))
                put("saved_path", JsonPrimitive(addRes.savedPath))
                put("month", JsonPrimitive(addRes.month))
            }
        return TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
    }

    private fun handleList(argv: List<String>): TerminalCommandOutput {
        val month = optionalFlagValue(argv, "--month")?.trim()?.takeIf { it.isNotBlank() }
        val account = optionalFlagValue(argv, "--account")?.trim()?.takeIf { it.isNotBlank() }
        val category = optionalFlagValue(argv, "--category")?.trim()?.takeIf { it.isNotBlank() }
        val max = parseIntFlag(argv, "--max", defaultValue = 50).coerceAtLeast(0).coerceAtMost(500)
        val outRel = optionalFlagValue(argv, "--out")?.trim()?.takeIf { it.isNotBlank() }
        val outFile = outRel?.let { resolveWithinAgents(agentsRoot, it) }

        val filters = LedgerStore.ListFilters(month = month, account = account, category = category)
        val res = store.list(filters = filters, max = max)

        if (outFile != null) {
            val json =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("ledger list"))
                    put("count_total", JsonPrimitive(res.countTotal))
                    put(
                        "filters",
                        buildJsonObject {
                            if (month != null) put("month", JsonPrimitive(month))
                            if (account != null) put("account", JsonPrimitive(account))
                            if (category != null) put("category", JsonPrimitive(category))
                        },
                    )
                    put("transactions", buildJsonArray { res.all.forEach { add(it) } })
                }
            outFile.parentFile?.mkdirs()
            outFile.writeText(json.toString() + "\n", Charsets.UTF_8)

            val artifactPath = ".agents/" + relPath(agentsRoot, outFile)
            val result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("ledger list"))
                    put("count_total", JsonPrimitive(res.countTotal))
                    put("count_emitted", JsonPrimitive(res.countEmitted))
                    put(
                        "filters",
                        buildJsonObject {
                            if (month != null) put("month", JsonPrimitive(month))
                            if (account != null) put("account", JsonPrimitive(account))
                            if (category != null) put("category", JsonPrimitive(category))
                        },
                    )
                    put("out", JsonPrimitive(outRel))
                }
            return TerminalCommandOutput(
                exitCode = 0,
                stdout = "ledger list: ${res.countTotal} tx (written to $artifactPath)",
                result = result,
                artifacts =
                    listOf(
                        TerminalArtifact(
                            path = artifactPath,
                            mime = "application/json",
                            description = "ledger list output (full)",
                        ),
                    ),
            )
        }

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("ledger list"))
                put("count_total", JsonPrimitive(res.countTotal))
                put("count_emitted", JsonPrimitive(res.countEmitted))
                put(
                    "filters",
                    buildJsonObject {
                        if (month != null) put("month", JsonPrimitive(month))
                        if (account != null) put("account", JsonPrimitive(account))
                        if (category != null) put("category", JsonPrimitive(category))
                    },
                )
                put(
                    "transactions",
                    buildJsonArray {
                        for (tx in res.emitted) {
                            val summary =
                                buildJsonObject {
                                    tx["tx_id"]?.let { put("tx_id", it) }
                                    tx["type"]?.let { put("type", it) }
                                    tx["amount_yuan"]?.let { put("amount_yuan", it) }
                                    tx["amount_fen"]?.let { put("amount_fen", it) }
                                    tx["currency"]?.let { put("currency", it) }
                                    tx["category"]?.let { put("category", it) }
                                    tx["account"]?.let { put("account", it) }
                                    tx["from_account"]?.let { put("from_account", it) }
                                    tx["to_account"]?.let { put("to_account", it) }
                                    tx["at"]?.let { put("at", it) }
                                    tx["month"]?.let { put("month", it) }
                                }
                            add(summary)
                        }
                    },
                )
            }

        return TerminalCommandOutput(
            exitCode = 0,
            stdout = "ledger list: ${res.countTotal} tx (emitted ${res.countEmitted})",
            result = result,
        )
    }

    private fun handleSummary(argv: List<String>): TerminalCommandOutput {
        val month = requireFlagValue(argv, "--month").trim()
        val by = optionalFlagValue(argv, "--by")?.trim()?.takeIf { it.isNotBlank() } ?: "category"
        if (by != "category" && by != "account") throw IllegalArgumentException("invalid --by: $by")
        val outRel = optionalFlagValue(argv, "--out")?.trim()?.takeIf { it.isNotBlank() }
        val outFile = outRel?.let { resolveWithinAgents(agentsRoot, it) }

        val res = store.summary(month = month, by = by)

        if (outFile != null) {
            val json =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("ledger summary"))
                    put("month", JsonPrimitive(res.month))
                    put("by", JsonPrimitive(res.by))
                    put("expense_total_fen", JsonPrimitive(res.expenseTotalFen))
                    put("income_total_fen", JsonPrimitive(res.incomeTotalFen))
                    put("groups", res.groups)
                }
            outFile.parentFile?.mkdirs()
            outFile.writeText(json.toString() + "\n", Charsets.UTF_8)

            val artifactPath = ".agents/" + relPath(agentsRoot, outFile)
            val result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("ledger summary"))
                    put("month", JsonPrimitive(res.month))
                    put("by", JsonPrimitive(res.by))
                    put("expense_total_fen", JsonPrimitive(res.expenseTotalFen))
                    put("income_total_fen", JsonPrimitive(res.incomeTotalFen))
                    put("out", JsonPrimitive(outRel))
                }
            return TerminalCommandOutput(
                exitCode = 0,
                stdout = "ledger summary: month=${res.month} by=${res.by} (written to $artifactPath)",
                result = result,
                artifacts =
                    listOf(
                        TerminalArtifact(
                            path = artifactPath,
                            mime = "application/json",
                            description = "ledger summary output (full)",
                        ),
                    ),
            )
        }

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("ledger summary"))
                put("month", JsonPrimitive(res.month))
                put("by", JsonPrimitive(res.by))
                put("expense_total_fen", JsonPrimitive(res.expenseTotalFen))
                put("income_total_fen", JsonPrimitive(res.incomeTotalFen))
                put("groups", res.groups)
            }
        return TerminalCommandOutput(
            exitCode = 0,
            stdout = "ledger summary: month=${res.month} by=${res.by}",
            result = result,
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

