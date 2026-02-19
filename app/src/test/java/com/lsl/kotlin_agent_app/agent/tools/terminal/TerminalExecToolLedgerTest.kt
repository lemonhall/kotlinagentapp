package com.lsl.kotlin_agent_app.agent.tools.terminal

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalExecToolLedgerTest {

    @Test
    fun ledger_add_withoutInit_returnsNotInitialized() =
        runTerminalExecToolTest { tool ->
            val ledgerDir = File(tool.filesDir, ".agents/workspace/ledger")
            if (ledgerDir.exists()) ledgerDir.deleteRecursively()

            val out = tool.exec("ledger add --type expense --amount 12.34 --category 餐饮 --account 微信")
            assertTrue(out.exitCode != 0)
            assertEquals("NotInitialized", out.errorCode)
        }

    @Test
    fun ledger_init_createsWorkspaceFiles_andSecondInitRequiresConfirm() =
        runTerminalExecToolTest { tool ->
            val ledgerDir = File(tool.filesDir, ".agents/workspace/ledger")
            if (ledgerDir.exists()) ledgerDir.deleteRecursively()

            val init = tool.exec("ledger init")
            assertEquals(0, init.exitCode)

            val meta = File(tool.filesDir, ".agents/workspace/ledger/meta.json")
            val categories = File(tool.filesDir, ".agents/workspace/ledger/categories.json")
            val accounts = File(tool.filesDir, ".agents/workspace/ledger/accounts.json")
            val tx = File(tool.filesDir, ".agents/workspace/ledger/transactions.jsonl")
            assertTrue(meta.exists())
            assertTrue(categories.exists())
            assertTrue(accounts.exists())
            assertTrue(tx.exists())

            val again = tool.exec("ledger init")
            assertTrue(again.exitCode != 0)
            assertEquals("ConfirmRequired", again.errorCode)

            val confirmed = tool.exec("ledger init --confirm")
            assertEquals(0, confirmed.exitCode)
        }

    @Test
    fun ledger_add_appendsJsonl_and_listSupportsOutArtifact() =
        runTerminalExecToolTest { tool ->
            val ledgerDir = File(tool.filesDir, ".agents/workspace/ledger")
            if (ledgerDir.exists()) ledgerDir.deleteRecursively()

            val init = tool.exec("ledger init")
            assertEquals(0, init.exitCode)

            val add = tool.exec("ledger add --type expense --amount 10.00 --category 餐饮 --account 现金 --note 午饭 --at 2026-02-18T12:00:00+08:00")
            assertEquals(0, add.exitCode)

            val txFile = File(tool.filesDir, ".agents/workspace/ledger/transactions.jsonl")
            assertTrue(txFile.exists())
            val lines = txFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            assertTrue("transactions.jsonl should have >= 1 line", lines.isNotEmpty())
            val parsed = Json.parseToJsonElement(lines.first())
            assertTrue("transactions.jsonl line should be JSON object", parsed is JsonObject)

            val list = tool.exec("ledger list --max 10")
            assertEquals(0, list.exitCode)
            assertTrue(list.artifacts.isEmpty())
            assertEquals("ledger list", (list.result?.get("command") as? JsonPrimitive)?.content)

            val outRel = "artifacts/ledger/test-list.json"
            val listOut = tool.exec("ledger list --out $outRel")
            assertEquals(0, listOut.exitCode)
            assertTrue(listOut.artifacts.contains(".agents/$outRel"))

            val outFile = File(tool.filesDir, ".agents/$outRel")
            assertTrue(outFile.exists())
            assertTrue(outFile.readText(Charsets.UTF_8).contains("\"count_total\""))
        }

    @Test
    fun ledger_summarySupportsOutArtifact_andRejectsPathTraversal() =
        runTerminalExecToolTest { tool ->
            val ledgerDir = File(tool.filesDir, ".agents/workspace/ledger")
            if (ledgerDir.exists()) ledgerDir.deleteRecursively()

            val init = tool.exec("ledger init")
            assertEquals(0, init.exitCode)

            val addExpense = tool.exec("ledger add --type expense --amount 10.00 --category 餐饮 --account 现金 --at 2026-02-18T12:00:00+08:00")
            assertEquals(0, addExpense.exitCode)
            val addIncome = tool.exec("ledger add --type income --amount 20.00 --category 工资 --account 银行卡 --at 2026-02-01T09:00:00+08:00")
            assertEquals(0, addIncome.exitCode)

            val outRel = "artifacts/ledger/test-summary.json"
            val summary = tool.exec("ledger summary --month 2026-02 --by category --out $outRel")
            assertEquals(0, summary.exitCode)
            assertTrue(summary.artifacts.contains(".agents/$outRel"))
            assertEquals(1000L, (summary.result?.get("expense_total_fen") as? JsonPrimitive)?.content?.toLongOrNull())
            assertEquals(2000L, (summary.result?.get("income_total_fen") as? JsonPrimitive)?.content?.toLongOrNull())

            val traversal = tool.exec("ledger list --out ../escape.json")
            assertTrue(traversal.exitCode != 0)
            assertEquals("PathEscapesAgentsRoot", traversal.errorCode)
        }
}

