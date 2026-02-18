package com.lsl.kotlin_agent_app.agent.tools.ledger

import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.ConfirmRequired
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.resolveWithinAgents
import java.io.File
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal class NotInitialized(
    message: String,
) : IllegalStateException(message)

internal class LedgerStore(
    private val agentsRoot: File,
) {
    private val agentsRootCanonical: File = agentsRoot.canonicalFile

    private fun ledgerDir(): File = resolveWithinAgents(agentsRootCanonical, "workspace/ledger")

    private fun metaFile(): File = resolveWithinAgents(agentsRootCanonical, "workspace/ledger/meta.json")

    private fun categoriesFile(): File = resolveWithinAgents(agentsRootCanonical, "workspace/ledger/categories.json")

    private fun accountsFile(): File = resolveWithinAgents(agentsRootCanonical, "workspace/ledger/accounts.json")

    fun transactionsFile(): File = resolveWithinAgents(agentsRootCanonical, "workspace/ledger/transactions.jsonl")

    data class InitResult(
        val created: Boolean,
        val workspaceDir: String,
    )

    fun init(confirm: Boolean): InitResult {
        val dir = ledgerDir()
        val existed = dir.exists()
        val nonEmpty = existed && (dir.listFiles()?.isNotEmpty() == true)
        if (nonEmpty && !confirm) {
            throw ConfirmRequired("ledger workspace exists; pass --confirm to reset")
        }
        if (!dir.exists()) dir.mkdirs()

        val now = OffsetDateTime.now()
        val tz = ZoneId.systemDefault().id
        val meta =
            buildJsonObject {
                put("schema_version", JsonPrimitive(1))
                put("created_at", JsonPrimitive(now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
                put("timezone", JsonPrimitive(tz))
                put("currency", JsonPrimitive("CNY"))
            }
        metaFile().writeText(meta.toString() + "\n", Charsets.UTF_8)

        val categories =
            buildJsonObject {
                put(
                    "expense",
                    buildJsonArray {
                        add(JsonPrimitive("餐饮"))
                        add(JsonPrimitive("交通"))
                        add(JsonPrimitive("购物"))
                        add(JsonPrimitive("住房"))
                        add(JsonPrimitive("通讯"))
                        add(JsonPrimitive("娱乐"))
                        add(JsonPrimitive("医疗"))
                        add(JsonPrimitive("教育"))
                        add(JsonPrimitive("其他"))
                    },
                )
                put(
                    "income",
                    buildJsonArray {
                        add(JsonPrimitive("工资"))
                        add(JsonPrimitive("奖金"))
                        add(JsonPrimitive("理财"))
                        add(JsonPrimitive("红包"))
                        add(JsonPrimitive("其他"))
                    },
                )
            }
        categoriesFile().writeText(categories.toString() + "\n", Charsets.UTF_8)

        val accounts =
            buildJsonObject {
                put(
                    "accounts",
                    buildJsonArray {
                        add(buildJsonObject { put("id", JsonPrimitive("cash")); put("name", JsonPrimitive("现金")) })
                        add(buildJsonObject { put("id", JsonPrimitive("bank")); put("name", JsonPrimitive("银行卡")) })
                        add(buildJsonObject { put("id", JsonPrimitive("wechat")); put("name", JsonPrimitive("微信")) })
                        add(buildJsonObject { put("id", JsonPrimitive("alipay")); put("name", JsonPrimitive("支付宝")) })
                    },
                )
            }
        accountsFile().writeText(accounts.toString() + "\n", Charsets.UTF_8)

        val tx = transactionsFile()
        if (!tx.exists()) {
            tx.parentFile?.mkdirs()
            tx.writeText("", Charsets.UTF_8)
        } else if (confirm) {
            tx.writeText("", Charsets.UTF_8)
        }

        return InitResult(created = !existed, workspaceDir = ".agents/workspace/ledger")
    }

    fun requireInitialized() {
        val meta = metaFile()
        val categories = categoriesFile()
        val accounts = accountsFile()
        val tx = transactionsFile()
        if (!meta.exists() || !categories.exists() || !accounts.exists() || !tx.exists()) {
            throw NotInitialized("ledger workspace not initialized; run: ledger init")
        }
    }

    data class AddResult(
        val txId: String,
        val savedPath: String,
        val at: String,
        val month: String,
        val amountFen: Long,
        val amountYuan: String,
    )

    fun addExpenseOrIncome(
        type: String,
        amountYuanRaw: String,
        category: String,
        account: String,
        note: String?,
        atIso: String?,
    ): AddResult {
        require(type == "expense" || type == "income") { "invalid type for add: $type" }
        requireInitialized()
        val at = parseAt(atIso)
        val month = formatMonth(at)
        val amountFen = parseYuanToFen(amountYuanRaw)
        val txId = allocateTxId()

        val obj =
            buildJsonObject {
                put("tx_id", JsonPrimitive(txId))
                put("type", JsonPrimitive(type))
                put("amount_yuan", JsonPrimitive(normalizeYuan(amountYuanRaw)))
                put("amount_fen", JsonPrimitive(amountFen))
                put("currency", JsonPrimitive("CNY"))
                put("category", JsonPrimitive(category))
                put("account", JsonPrimitive(account))
                if (!note.isNullOrBlank()) put("note", JsonPrimitive(note))
                put("at", JsonPrimitive(at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
                put("at_ms", JsonPrimitive(at.toInstant().toEpochMilli()))
                put("month", JsonPrimitive(month))
            }
        transactionsFile().appendText(obj.toString() + "\n", Charsets.UTF_8)
        return AddResult(
            txId = txId,
            savedPath = ".agents/workspace/ledger/transactions.jsonl",
            at = at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            month = month,
            amountFen = amountFen,
            amountYuan = normalizeYuan(amountYuanRaw),
        )
    }

    fun addTransfer(
        amountYuanRaw: String,
        fromAccount: String,
        toAccount: String,
        note: String?,
        atIso: String?,
    ): AddResult {
        requireInitialized()
        val at = parseAt(atIso)
        val month = formatMonth(at)
        val amountFen = parseYuanToFen(amountYuanRaw)
        val txId = allocateTxId()

        val obj =
            buildJsonObject {
                put("tx_id", JsonPrimitive(txId))
                put("type", JsonPrimitive("transfer"))
                put("amount_yuan", JsonPrimitive(normalizeYuan(amountYuanRaw)))
                put("amount_fen", JsonPrimitive(amountFen))
                put("currency", JsonPrimitive("CNY"))
                put("from_account", JsonPrimitive(fromAccount))
                put("to_account", JsonPrimitive(toAccount))
                if (!note.isNullOrBlank()) put("note", JsonPrimitive(note))
                put("at", JsonPrimitive(at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
                put("at_ms", JsonPrimitive(at.toInstant().toEpochMilli()))
                put("month", JsonPrimitive(month))
            }
        transactionsFile().appendText(obj.toString() + "\n", Charsets.UTF_8)
        return AddResult(
            txId = txId,
            savedPath = ".agents/workspace/ledger/transactions.jsonl",
            at = at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            month = month,
            amountFen = amountFen,
            amountYuan = normalizeYuan(amountYuanRaw),
        )
    }

    data class ListFilters(
        val month: String?,
        val account: String?,
        val category: String?,
    )

    data class ListResult(
        val countTotal: Int,
        val countEmitted: Int,
        val emitted: List<JsonObject>,
        val all: List<JsonObject>,
    )

    fun list(
        filters: ListFilters,
        max: Int,
    ): ListResult {
        requireInitialized()
        val all = readTransactionsFiltered(filters).sortedByDescending { txAtMs(it) ?: Long.MIN_VALUE }
        val emitted = all.take(max.coerceAtLeast(0))
        return ListResult(countTotal = all.size, countEmitted = emitted.size, emitted = emitted, all = all)
    }

    data class SummaryResult(
        val month: String,
        val by: String,
        val expenseTotalFen: Long,
        val incomeTotalFen: Long,
        val groups: JsonArray,
    )

    fun summary(
        month: String,
        by: String,
    ): SummaryResult {
        requireInitialized()
        val all = readTransactionsFiltered(ListFilters(month = month, account = null, category = null))
        var expenseTotal = 0L
        var incomeTotal = 0L
        val groups = linkedMapOf<String, Pair<Long, Long>>() // key -> (expense,income)

        for (tx in all) {
            val type = tx["type"]?.asString()
            val amount = tx["amount_fen"]?.asLong() ?: 0L
            if (type != "expense" && type != "income") continue
            val key =
                when (by) {
                    "category" -> tx["category"]?.asString().orEmpty()
                    "account" -> tx["account"]?.asString().orEmpty()
                    else -> ""
                }.ifBlank { "(unknown)" }

            val prev = groups[key] ?: (0L to 0L)
            val next =
                if (type == "expense") {
                    expenseTotal += amount
                    (prev.first + amount) to prev.second
                } else {
                    incomeTotal += amount
                    prev.first to (prev.second + amount)
                }
            groups[key] = next
        }

        val groupsArray =
            buildJsonArray {
                for ((k, v) in groups) {
                    add(
                        buildJsonObject {
                            put("key", JsonPrimitive(k))
                            put("expense_fen", JsonPrimitive(v.first))
                            put("income_fen", JsonPrimitive(v.second))
                        },
                    )
                }
            }
        return SummaryResult(
            month = month,
            by = by,
            expenseTotalFen = expenseTotal,
            incomeTotalFen = incomeTotal,
            groups = groupsArray,
        )
    }

    private fun readTransactionsFiltered(filters: ListFilters): List<JsonObject> {
        val txFile = transactionsFile()
        if (!txFile.exists()) return emptyList()
        val lines = txFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        val out = mutableListOf<JsonObject>()
        for (line in lines) {
            val el =
                try {
                    Json.parseToJsonElement(line)
                } catch (_: Throwable) {
                    continue
                }
            val obj = el as? JsonObject ?: continue
            if (!filters.month.isNullOrBlank()) {
                val m = obj["month"]?.asString()
                if (m != filters.month) continue
            }
            if (!filters.category.isNullOrBlank()) {
                val c = obj["category"]?.asString()
                if (c != filters.category) continue
            }
            if (!filters.account.isNullOrBlank()) {
                val a = filters.account
                val account = obj["account"]?.asString()
                val from = obj["from_account"]?.asString()
                val to = obj["to_account"]?.asString()
                if (account != a && from != a && to != a) continue
            }
            out.add(obj)
        }
        return out
    }

    private fun parseAt(raw: String?): OffsetDateTime {
        val s = raw?.trim().orEmpty()
        return if (s.isEmpty()) {
            OffsetDateTime.now()
        } else {
            OffsetDateTime.parse(s)
        }
    }

    private fun formatMonth(at: OffsetDateTime): String = "%04d-%02d".format(at.year, at.monthValue)

    private fun allocateTxId(): String = UUID.randomUUID().toString()

    private fun normalizeYuan(raw: String): String = raw.trim()

    private fun parseYuanToFen(raw: String): Long {
        val s = raw.trim()
        if (s.isEmpty()) throw IllegalArgumentException("missing amount")
        val bd =
            try {
                BigDecimal(s)
            } catch (_: Throwable) {
                throw IllegalArgumentException("invalid amount: $raw")
            }
        if (bd.signum() <= 0) throw IllegalArgumentException("amount must be > 0")
        val fen =
            try {
                bd.movePointRight(2).longValueExact()
            } catch (_: Throwable) {
                throw IllegalArgumentException("amount must have at most 2 decimal places: $raw")
            }
        return fen
    }

    private fun txAtMs(obj: JsonObject): Long? = obj["at_ms"]?.asLong()
}

private fun kotlinx.serialization.json.JsonElement.asString(): String? = (this as? JsonPrimitive)?.contentOrNullSafe()

private fun kotlinx.serialization.json.JsonElement.asLong(): Long? = (this as? JsonPrimitive)?.contentOrNullSafe()?.toLongOrNull()

private fun JsonPrimitive.contentOrNullSafe(): String? =
    try {
        this.content
    } catch (_: Throwable) {
        null
    }
