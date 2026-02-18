package com.lsl.kotlin_agent_app.agent.tools.terminal

import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.agent.tools.calendar.FakeCalendarPermissionChecker
import com.lsl.kotlin_agent_app.agent.tools.calendar.InMemoryCalendarStore
import com.lsl.kotlin_agent_app.agent.tools.mail.QqMailImapClient
import com.lsl.kotlin_agent_app.agent.tools.mail.QqMailMessage
import com.lsl.kotlin_agent_app.agent.tools.mail.QqMailSendRequest
import com.lsl.kotlin_agent_app.agent.tools.mail.QqMailSendResult
import com.lsl.kotlin_agent_app.agent.tools.mail.QqMailSmtpClient
import com.lsl.kotlin_agent_app.agent.tools.rss.RssClientTestHooks
import com.lsl.kotlin_agent_app.agent.tools.rss.RssHttpResponse
import com.lsl.kotlin_agent_app.agent.tools.rss.RssTransport
import com.lsl.kotlin_agent_app.agent.tools.stock.FinnhubClientTestHooks
import com.lsl.kotlin_agent_app.agent.tools.stock.FinnhubHttpResponse
import com.lsl.kotlin_agent_app.agent.tools.stock.FinnhubTransport
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.cal.CalCommandTestHooks
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.qqmail.QqMailCommandTestHooks
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.lemonhall.openagentic.sdk.tools.ToolContext
import me.lemonhall.openagentic.sdk.tools.ToolOutput
import okhttp3.HttpUrl
import okio.FileSystem
import okio.Path.Companion.toPath
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalExecToolTest {

    @Test
    fun unknownCommand_isRejected() = runTest { tool ->
        val out = tool.exec("no_such_command")
        assertTrue(out.exitCode != 0)
        assertEquals("UnknownCommand", out.errorCode)
    }

    @Test
    fun hello_printsAsciiAndSignature_andWritesAuditRun() = runTest { tool ->
        val out = tool.exec("hello")
        assertEquals(0, out.exitCode)
        assertTrue(out.stdout.contains("HELLO"))
        assertTrue(out.stdout.contains("lemonhall"))

        val runId = out.runId
        assertTrue(runId.isNotBlank())

        val auditPath = File(out.filesDir, ".agents/artifacts/terminal_exec/runs/$runId.json")
        assertTrue("audit file should exist: $auditPath", auditPath.exists())
        val auditText = auditPath.readText(Charsets.UTF_8)
        assertTrue(auditText.contains("\"command\""))
        assertTrue(auditText.contains("hello"))
        assertTrue("audit should include stdout", auditText.contains("\"stdout\""))
        assertTrue("audit should not include stdin key", !auditText.contains("\"stdin\""))
    }

    @Test
    fun newlineIsRejected() = runTest { tool ->
        val out = tool.exec("hello\nworld")
        assertTrue(out.exitCode != 0)
        assertEquals("InvalidCommand", out.errorCode)
    }

    @Test
    fun rss_add_writesSubscriptions_and_list_readsBack() = runTest { tool ->
        val add = tool.exec("rss add --name test-feed --url https://example.com/feed.xml")
        assertEquals(0, add.exitCode)
        assertEquals("rss add", add.result?.get("command")?.let { (it as JsonPrimitive).content })

        val subsPath = File(tool.filesDir, ".agents/workspace/rss/subscriptions.json")
        assertTrue("subscriptions should exist: $subsPath", subsPath.exists())
        val subsText = subsPath.readText(Charsets.UTF_8)
        assertTrue(subsText.contains("test-feed"))
        assertTrue(subsText.contains("https://example.com/feed.xml"))

        val list = tool.exec("rss list --max 10")
        assertEquals(0, list.exitCode)
        assertEquals("rss list", list.result?.get("command")?.let { (it as JsonPrimitive).content })
        val items = list.result?.get("items")?.jsonArray
        assertNotNull(items)
        assertTrue(items!!.isNotEmpty())
        val first = items.first().jsonObject
        assertEquals("test-feed", (first["name"] as? JsonPrimitive)?.content)
        assertEquals("https://example.com/feed.xml", (first["url"] as? JsonPrimitive)?.content)
    }

    @Test
    fun rss_remove_missing_isNotFound() = runTest { tool ->
        val out = tool.exec("rss remove --name no_such_feed")
        assertTrue(out.exitCode != 0)
        assertEquals("NotFound", out.errorCode)
    }

    @Test
    fun rss_fetch_fileScheme_isRejected() = runTest { tool ->
        val out = tool.exec("rss fetch --url file:///etc/passwd --max-items 5")
        assertTrue(out.exitCode != 0)
        assertEquals("InvalidArgs", out.errorCode)
    }

    @Test
    fun rss_fetch_429_isRateLimited_andReturnsRetryAfterMs() {
        var transport: CapturingRssTransport? = null
        runTest(
            setup = {
                transport =
                    CapturingRssTransport(
                        statusCode = 429,
                        bodyText = "rate limited",
                        headers = mapOf("Retry-After" to "5"),
                    )
                RssClientTestHooks.install(transport!!)
                val teardown = { RssClientTestHooks.clear() }
                teardown
            },
        ) { tool ->
            val out = tool.exec("rss fetch --url https://example.com/feed.xml --max-items 5")
            assertTrue(out.exitCode != 0)
            assertEquals("RateLimited", out.errorCode)
            assertEquals("5000", (out.result?.get("retry_after_ms") as? JsonPrimitive)?.content)
        }
    }

    @Test
    fun rss_fetch_withOut_writesItems_andUpdatesFetchState_andUsesEtagOnSecondFetch() {
        var transport: CapturingRssTransport? = null
        val rssXml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Test Feed</title>
                <item>
                  <title>Item 1</title>
                  <link>https://example.com/1</link>
                  <guid>1</guid>
                  <pubDate>Wed, 18 Feb 2026 01:30:32 +0000</pubDate>
                  <description>Summary 1</description>
                </item>
                <item>
                  <title>Item 2</title>
                  <link>https://example.com/2</link>
                  <guid>2</guid>
                  <pubDate>Wed, 18 Feb 2026 02:30:32 +0000</pubDate>
                  <description>Summary 2</description>
                </item>
              </channel>
            </rss>
            """.trimIndent()

        runTest(
            setup = {
                transport =
                    CapturingRssTransport(
                        statusCode = 200,
                        bodyText = rssXml,
                        headers = mapOf("ETag" to "W/\"abc\"", "Last-Modified" to "Wed, 18 Feb 2026 01:30:32 GMT"),
                    )
                RssClientTestHooks.install(transport!!)
                val teardown = { RssClientTestHooks.clear() }
                teardown
            },
        ) { tool ->
            val add = tool.exec("rss add --name test-feed --url https://example.com/feed.xml")
            assertEquals(0, add.exitCode)

            val fetch1 = tool.exec("rss fetch --name test-feed --max-items 2 --out artifacts/rss/test-items.json")
            assertEquals(0, fetch1.exitCode)
            assertTrue(fetch1.artifacts.contains(".agents/artifacts/rss/test-items.json"))

            val outFile = File(tool.filesDir, ".agents/artifacts/rss/test-items.json")
            assertTrue(outFile.exists())
            val outText = outFile.readText(Charsets.UTF_8)
            assertTrue(outText.contains("Item 1"))
            assertTrue(outText.contains("https://example.com/1"))

            val stateFile = File(tool.filesDir, ".agents/workspace/rss/fetch_state.json")
            assertTrue(stateFile.exists())
            val stateText = stateFile.readText(Charsets.UTF_8)
            assertTrue(stateText.contains("W/\\\"abc\\\"") || stateText.contains("W/\"abc\""))

            // Second fetch should include If-None-Match based on stored etag (transport captures lastHeaders).
            tool.exec("rss fetch --name test-feed --max-items 1")
            val lastHeaders = transport?.lastHeaders.orEmpty()
            assertTrue("If-None-Match should be set", lastHeaders.keys.any { it.equals("If-None-Match", ignoreCase = true) })
        }
    }

    @Test
    fun stock_symbols_withoutOut_isRejectedWithOutRequired() = runTest { tool ->
        val out = tool.exec("stock symbols --exchange US")
        assertTrue(out.exitCode != 0)
        assertEquals("OutRequired", out.errorCode)
    }

    @Test
    fun stock_outPathWithDotDot_isRejected() = runTest { tool ->
        val out = tool.exec("stock symbols --exchange US --out ../pwn.json")
        assertTrue(out.exitCode != 0)
        assertEquals("PathEscapesAgentsRoot", out.errorCode)
    }

    @Test
    fun stock_missingCredentials_isRejected() = runTest { tool ->
        val out = tool.exec("stock quote --symbol AAPL")
        assertTrue(out.exitCode != 0)
        assertEquals("MissingCredentials", out.errorCode)
    }

    @Test
    fun stock_candle_isNotSupported() = runTest { tool ->
        val out = tool.exec("stock candle --symbol AAPL")
        assertTrue(out.exitCode != 0)
        assertEquals("NotSupported", out.errorCode)
    }

    @Test
    fun stock_quote_happyPath_makesRequest_andDoesNotLeakToken() {
        var transport: CapturingFinnhubTransport? = null
        val token = "TEST_FINNHUB_TOKEN_123"
        runTest(
            setup = { context ->
                val agentsRoot = File(context.filesDir, ".agents")
                val env = File(agentsRoot, "skills/stock-cli/secrets/.env")
                env.parentFile?.mkdirs()
                env.writeText("FINNHUB_API_KEY=$token\n", Charsets.UTF_8)

                transport =
                    CapturingFinnhubTransport(
                        statusCode = 200,
                        bodyText = """{"c":123.45,"h":124.0,"l":120.0,"o":121.0,"pc":122.0,"t":1700000000}""",
                        headers = emptyMap(),
                    )
                FinnhubClientTestHooks.install(transport!!)
                val teardown = { FinnhubClientTestHooks.clear() }
                teardown
            },
        ) { tool ->
            val out = tool.exec("stock quote --symbol AAPL")
            assertEquals(0, out.exitCode)
            assertTrue(out.stdout.contains("AAPL"))
            assertTrue("stdout must not contain token", !out.stdout.contains(token))

            val result = out.result
            assertNotNull(result)
            assertEquals("stock quote", (result!!["command"] as? JsonPrimitive)?.content)
            assertEquals("AAPL", (result["symbol"] as? JsonPrimitive)?.content)

            val url = transport!!.lastUrl
            val headers = transport!!.lastHeaders
            assertNotNull(url)
            assertNotNull(headers)
            assertTrue(url!!.encodedPath.endsWith("/quote"))
            assertEquals("AAPL", url.queryParameter("symbol"))
            assertEquals(token, headers!!["X-Finnhub-Token"])
        }
    }

    @Test
    fun stock_symbols_withOut_writesArtifact_andCapturesRequest() {
        var transport: CapturingFinnhubTransport? = null
        val token = "TEST_FINNHUB_TOKEN_123"
        runTest(
            setup = { context ->
                val agentsRoot = File(context.filesDir, ".agents")
                val env = File(agentsRoot, "skills/stock-cli/secrets/.env")
                env.parentFile?.mkdirs()
                env.writeText("FINNHUB_API_KEY=$token\n", Charsets.UTF_8)

                transport =
                    CapturingFinnhubTransport(
                        statusCode = 200,
                        bodyText = """[{"symbol":"AAPL"},{"symbol":"MSFT"}]""",
                        headers = emptyMap(),
                    )
                FinnhubClientTestHooks.install(transport!!)
                val teardown = { FinnhubClientTestHooks.clear() }
                teardown
            },
        ) { tool ->
            val out = tool.exec("stock symbols --exchange US --out artifacts/stock/symbols-us.json")
            assertEquals(0, out.exitCode)
            assertTrue(out.artifacts.contains(".agents/artifacts/stock/symbols-us.json"))
            assertTrue(File(tool.filesDir, ".agents/artifacts/stock/symbols-us.json").exists())

            val result = out.result
            assertNotNull(result)
            assertEquals("stock symbols", (result!!["command"] as? JsonPrimitive)?.content)
            assertEquals("US", (result["exchange"] as? JsonPrimitive)?.content)
            assertEquals("2", (result["count_total"] as? JsonPrimitive)?.content)

            val url = transport!!.lastUrl
            val headers = transport!!.lastHeaders
            assertNotNull(url)
            assertNotNull(headers)
            assertTrue(url!!.encodedPath.endsWith("/stock/symbol"))
            assertEquals("US", url.queryParameter("exchange"))
            assertEquals(token, headers!!["X-Finnhub-Token"])
        }
    }

    @Test
    fun stock_quote_http429_includesRetryAfterMs_whenProvided() {
        var transport: CapturingFinnhubTransport? = null
        val token = "TEST_FINNHUB_TOKEN_123"
        runTest(
            setup = { context ->
                val agentsRoot = File(context.filesDir, ".agents")
                val env = File(agentsRoot, "skills/stock-cli/secrets/.env")
                env.parentFile?.mkdirs()
                env.writeText("FINNHUB_API_KEY=$token\n", Charsets.UTF_8)

                transport =
                    CapturingFinnhubTransport(
                        statusCode = 429,
                        bodyText = "",
                        headers = mapOf("Retry-After" to "2"),
                    )
                FinnhubClientTestHooks.install(transport!!)
                val teardown = { FinnhubClientTestHooks.clear() }
                teardown
            },
        ) { tool ->
            val out = tool.exec("stock quote --symbol AAPL")
            assertTrue(out.exitCode != 0)
            assertEquals("RateLimited", out.errorCode)
            val retryAfterMs = (out.result?.get("retry_after_ms") as? JsonPrimitive)?.content?.toLongOrNull()
            assertEquals(2_000L, retryAfterMs)
        }
    }

    @Test
    fun agentsWorkspace_installsStockCliSkill_andSeedsEnv() = runTest { tool ->
        val skill = File(tool.filesDir, ".agents/skills/stock-cli/SKILL.md")
        assertTrue("stock-cli skill should exist: $skill", skill.exists())
        val env = File(tool.filesDir, ".agents/skills/stock-cli/secrets/.env")
        assertTrue("stock-cli .env should exist: $env", env.exists())
    }

    @Test
    fun ledger_add_withoutInit_returnsNotInitialized() = runTest { tool ->
        val ledgerDir = File(tool.filesDir, ".agents/workspace/ledger")
        if (ledgerDir.exists()) ledgerDir.deleteRecursively()

        val out = tool.exec("ledger add --type expense --amount 12.34 --category 餐饮 --account 微信")
        assertTrue(out.exitCode != 0)
        assertEquals("NotInitialized", out.errorCode)
    }

    @Test
    fun ledger_init_createsWorkspaceFiles_andSecondInitRequiresConfirm() = runTest { tool ->
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
    fun ledger_add_appendsJsonl_and_listSupportsOutArtifact() = runTest { tool ->
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
    fun ledger_summarySupportsOutArtifact_andRejectsPathTraversal() = runTest { tool ->
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

    @Test
    fun cal_listCalendars_withoutReadPermission_returnsPermissionDenied() = runTest(
        setup = {
            CalCommandTestHooks.install(
                store = InMemoryCalendarStore(),
                permissions = FakeCalendarPermissionChecker(read = false, write = false),
            );
            { CalCommandTestHooks.clear() }
        },
    ) { tool ->
        val out = tool.exec("cal list-calendars")
        assertTrue(out.exitCode != 0)
        assertEquals("PermissionDenied", out.errorCode)
    }

    @Test
    fun cal_listCalendars_withReadPermission_supportsOutArtifact() = runTest(
        setup = {
            val store =
                InMemoryCalendarStore().apply {
                    addCalendar(id = 1, displayName = "Personal", accountName = "me", accountType = "local")
                    addCalendar(id = 2, displayName = "Work", accountName = "me", accountType = "local")
                }
            CalCommandTestHooks.install(
                store = store,
                permissions = FakeCalendarPermissionChecker(read = true, write = false),
            );
            { CalCommandTestHooks.clear() }
        },
    ) { tool ->
        val outRel = "artifacts/cal/test-calendars.json"
        val out = tool.exec("cal list-calendars --max 1 --out $outRel")
        assertEquals(0, out.exitCode)
        assertTrue(out.artifacts.contains(".agents/$outRel"))

        val outFile = File(tool.filesDir, ".agents/$outRel")
        assertTrue(outFile.exists())
        assertTrue(outFile.readText(Charsets.UTF_8).contains("\"count_total\""))
    }

    @Test
    fun cal_listEvents_withReadPermission_supportsOutArtifact() = runTest(
        setup = {
            val store =
                InMemoryCalendarStore().apply {
                    addCalendar(id = 1, displayName = "Personal", accountName = "me", accountType = "local")
                }
            CalCommandTestHooks.install(
                store = store,
                permissions = FakeCalendarPermissionChecker(read = true, write = true),
            );
            { CalCommandTestHooks.clear() }
        },
    ) { tool ->
        val created =
            tool.exec(
                "cal create-event --calendar-id 1 --title \"Demo\" --start 2026-02-18T10:00:00Z --end 2026-02-18T11:00:00Z --confirm",
            )
        assertEquals(0, created.exitCode)

        val outRel = "artifacts/cal/test-events.json"
        val listed =
            tool.exec(
                "cal list-events --from 2026-02-18T00:00:00Z --to 2026-02-19T00:00:00Z --out $outRel",
            )
        assertEquals(0, listed.exitCode)
        assertTrue(listed.artifacts.contains(".agents/$outRel"))

        val outFile = File(tool.filesDir, ".agents/$outRel")
        assertTrue(outFile.exists())
        assertTrue(outFile.readText(Charsets.UTF_8).contains("\"events\""))
    }

    @Test
    fun cal_createEvent_withoutConfirm_isRejected() = runTest(
        setup = {
            CalCommandTestHooks.install(
                store = InMemoryCalendarStore(),
                permissions = FakeCalendarPermissionChecker(read = true, write = true),
            );
            { CalCommandTestHooks.clear() }
        },
    ) { tool ->
        val out =
            tool.exec(
                "cal create-event --calendar-id 1 --title \"t\" --start 2026-02-18T10:00:00Z --end 2026-02-18T11:00:00Z",
            )
        assertTrue(out.exitCode != 0)
        assertEquals("ConfirmRequired", out.errorCode)
    }

    @Test
    fun cal_createUpdateAddReminderDelete_happyPath() = runTest(
        setup = {
            val store =
                InMemoryCalendarStore().apply {
                    addCalendar(id = 1, displayName = "Personal", accountName = "me", accountType = "local")
                }
            CalCommandTestHooks.install(
                store = store,
                permissions = FakeCalendarPermissionChecker(read = true, write = true),
            );
            { CalCommandTestHooks.clear() }
        },
    ) { tool ->
        val created =
            tool.exec(
                "cal create-event --calendar-id 1 --title \"Demo\" --start 2026-02-18T10:00:00Z --end 2026-02-18T11:00:00Z --remind-minutes 15 --confirm",
            )
        assertEquals(0, created.exitCode)
        val eventId = (created.result?.get("event_id") as? JsonPrimitive)?.content?.toLongOrNull()
        assertTrue("expected event_id", (eventId ?: 0L) > 0L)

        val updated =
            tool.exec(
                "cal update-event --event-id $eventId --location \"Room\" --confirm",
            )
        assertEquals(0, updated.exitCode)

        val reminder =
            tool.exec(
                "cal add-reminder --event-id $eventId --minutes 30 --confirm",
            )
        assertEquals(0, reminder.exitCode)

        val listed =
            tool.exec(
                "cal list-events --from 2026-02-18T00:00:00Z --to 2026-02-19T00:00:00Z --max 50",
            )
        assertEquals(0, listed.exitCode)
        val events = listed.result?.get("events")?.jsonArray
        assertTrue("expected at least 1 event", (events?.size ?: 0) >= 1)

        val deleted =
            tool.exec(
                "cal delete-event --event-id $eventId --confirm",
            )
        assertEquals(0, deleted.exitCode)
    }

    @Test
    fun git_init_status_add_commit_log_happyPath() = runTest { tool ->
        val repoName = "jgit-demo-" + UUID.randomUUID().toString().replace("-", "").take(8)
        val repoRel = "workspace/$repoName"

        // init repo
        val initOut = tool.exec("git init --dir $repoRel")
        assertEquals(0, initOut.exitCode)

        // create a new file (untracked)
        val repoDir = File(initOut.filesDir, ".agents/$repoRel")
        assertTrue(repoDir.exists())
        File(repoDir, "a.txt").writeText("hello", Charsets.UTF_8)

        val status1 = tool.exec("git status --repo $repoRel")
        assertEquals(0, status1.exitCode)
        assertTrue(status1.stdout.contains("untracked", ignoreCase = true))

        // add + commit
        val addOut = tool.exec("git add --repo $repoRel --all")
        assertEquals(0, addOut.exitCode)

        val commitOut = tool.exec("git commit --repo $repoRel --message \"init\"")
        assertEquals(0, commitOut.exitCode)

        val status2 = tool.exec("git status --repo $repoRel")
        assertEquals(0, status2.exitCode)
        assertTrue(status2.stdout.contains("clean", ignoreCase = true))

        val logOut = tool.exec("git log --repo $repoRel --max 1")
        assertEquals(0, logOut.exitCode)
        assertTrue(logOut.stdout.contains("init"))
    }

    @Test
    fun git_repoPathTraversal_isRejected() = runTest { tool ->
        val out = tool.exec("git status --repo ../")
        assertTrue(out.exitCode != 0)
        assertEquals("PathEscapesAgentsRoot", out.errorCode)
    }

    @Test
    fun git_branch_checkout_show_diff_reset_stash_happyPath() = runTest { tool ->
        val repoName = "jgit-v14-" + UUID.randomUUID().toString().replace("-", "").take(8)
        val repoRel = "workspace/$repoName"

        // init + first commit
        assertEquals(0, tool.exec("git init --dir $repoRel").exitCode)
        val repoDir = File(tool.filesDir, ".agents/$repoRel")
        File(repoDir, "a.txt").writeText("v1", Charsets.UTF_8)
        assertEquals(0, tool.exec("git add --repo $repoRel --all").exitCode)
        assertEquals(0, tool.exec("git commit --repo $repoRel --message \"c1\"").exitCode)

        // branch + checkout
        val co = tool.exec("git checkout --repo $repoRel --branch feat --create")
        assertEquals(0, co.exitCode)
        val branches = tool.exec("git branch --repo $repoRel")
        assertEquals(0, branches.exitCode)
        assertTrue(branches.stdout.contains("feat"))

        // second commit on feat
        File(repoDir, "a.txt").writeText("v2", Charsets.UTF_8)
        assertEquals(0, tool.exec("git add --repo $repoRel --all").exitCode)
        assertEquals(0, tool.exec("git commit --repo $repoRel --message \"c2\"").exitCode)

        // show HEAD
        val show = tool.exec("git show --repo $repoRel --commit HEAD --patch --max-chars 2000")
        assertEquals(0, show.exitCode)
        assertTrue(show.stdout.contains("c2"))

        // diff between commits
        val diff = tool.exec("git diff --repo $repoRel --from HEAD~1 --to HEAD --patch --max-chars 2000")
        assertEquals(0, diff.exitCode)
        assertTrue(diff.stdout.isNotBlank())

        // stash: dirty working tree then stash push/list/pop
        File(repoDir, "a.txt").writeText("dirty", Charsets.UTF_8)
        val stashPush = tool.exec("git stash push --repo $repoRel --message \"wip\"")
        assertEquals(0, stashPush.exitCode)
        val statusAfterStash = tool.exec("git status --repo $repoRel")
        assertEquals(0, statusAfterStash.exitCode)
        assertTrue(statusAfterStash.stdout.contains("clean", ignoreCase = true))

        val stashList = tool.exec("git stash list --repo $repoRel --max 5")
        assertEquals(0, stashList.exitCode)
        assertTrue(stashList.stdout.contains("wip"))

        val stashPop = tool.exec("git stash pop --repo $repoRel --index 0")
        assertEquals(0, stashPop.exitCode)
        val statusAfterPop = tool.exec("git status --repo $repoRel")
        assertEquals(0, statusAfterPop.exitCode)
        assertTrue(statusAfterPop.stdout.contains("not clean", ignoreCase = true))

        // reset: hard back one commit (from c2 to c1)
        val reset = tool.exec("git reset --repo $repoRel --mode hard --to HEAD~1")
        assertEquals(0, reset.exitCode)
        val log1 = tool.exec("git log --repo $repoRel --max 1")
        assertEquals(0, log1.exitCode)
        assertTrue(log1.stdout.contains("c1"))
    }

    @Test
    fun git_remoteClone_requiresConfirm() = runTest { tool ->
        val out =
            tool.exec(
                "git clone --remote https://example.com/repo.git --dir workspace/clone-no-confirm",
            )
        assertTrue(out.exitCode != 0)
        assertEquals("ConfirmRequired", out.errorCode)
    }

    @Test
    fun git_remoteClone_rejectsUserinfoUrl() = runTest { tool ->
        val out =
            tool.exec(
                "git clone --remote https://user:pass@example.com/repo.git --dir workspace/clone-bad --confirm",
            )
        assertTrue(out.exitCode != 0)
        assertEquals("InvalidRemoteUrl", out.errorCode)
    }

    @Test
    fun git_localRemote_clone_push_pull_happyPath() = runTest { tool ->
        val id = UUID.randomUUID().toString().replace("-", "").take(8)
        val originRel = "workspace/origin-$id.git"
        val clone1Rel = "workspace/clone1-$id"
        val clone2Rel = "workspace/clone2-$id"

        // Bare origin (needed for push)
        val initBare = tool.exec("git init --dir $originRel --bare")
        assertEquals(0, initBare.exitCode)

        // Clone1, commit, push
        assertEquals(0, tool.exec("git clone --local-remote $originRel --dir $clone1Rel --confirm").exitCode)
        val clone1Dir = File(tool.filesDir, ".agents/$clone1Rel")
        File(clone1Dir, "a.txt").writeText("v1", Charsets.UTF_8)
        assertEquals(0, tool.exec("git add --repo $clone1Rel --all").exitCode)
        assertEquals(0, tool.exec("git commit --repo $clone1Rel --message \"c1\"").exitCode)
        assertEquals(0, tool.exec("git push --repo $clone1Rel --confirm").exitCode)

        // Clone2 then pull after new push
        assertEquals(0, tool.exec("git clone --local-remote $originRel --dir $clone2Rel --confirm").exitCode)

        // New commit in clone1
        File(clone1Dir, "a.txt").writeText("v2", Charsets.UTF_8)
        assertEquals(0, tool.exec("git add --repo $clone1Rel --all").exitCode)
        assertEquals(0, tool.exec("git commit --repo $clone1Rel --message \"c2\"").exitCode)
        assertEquals(0, tool.exec("git push --repo $clone1Rel --confirm").exitCode)

        // Pull into clone2 and verify newest commit message appears in log
        assertEquals(0, tool.exec("git pull --repo $clone2Rel --confirm").exitCode)
        val log = tool.exec("git log --repo $clone2Rel --max 1")
        assertEquals(0, log.exitCode)
        assertTrue(log.stdout.contains("c2"))
    }

    @Test
    fun zip_create_requiresConfirm() = runTest { tool ->
        val srcRel = "workspace/zip-src-" + UUID.randomUUID().toString().replace("-", "").take(8)
        val srcDir = File(tool.filesDir, ".agents/$srcRel")
        srcDir.mkdirs()
        File(srcDir, "a.txt").writeText("hello", Charsets.UTF_8)

        val out = tool.exec("zip create --src $srcRel --out workspace/out.zip")
        assertTrue(out.exitCode != 0)
        assertEquals("ConfirmRequired", out.errorCode)
    }

    @Test
    fun zip_extract_blocksPathTraversalEntry() = runTest { tool ->
        val zipRel = "workspace/bad-" + UUID.randomUUID().toString().replace("-", "").take(8) + ".zip"
        val zipFile = File(tool.filesDir, ".agents/$zipRel")
        zipFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("../evil.txt"))
            zos.write("nope".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("ok.txt"))
            zos.write("ok".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val destRel = "workspace/unpack-" + UUID.randomUUID().toString().replace("-", "").take(8)
        val out = tool.exec("zip extract --in $zipRel --dest $destRel --confirm")
        assertEquals(0, out.exitCode)

        val escaped = File(tool.filesDir, ".agents/workspace/evil.txt")
        assertTrue("zip slip must not create escaped file: $escaped", !escaped.exists())

        val skipped = out.result?.get("skipped")?.jsonObject
        val unsafe = skipped?.get("unsafe_path") as? JsonPrimitive
        assertTrue("expected skipped.unsafe_path >= 1", (unsafe?.content?.toLongOrNull() ?: 0L) >= 1L)
    }

    @Test
    fun zip_list_supportsOutArtifact() = runTest { tool ->
        val zipRel = "workspace/many-" + UUID.randomUUID().toString().replace("-", "").take(8) + ".zip"
        val zipFile = File(tool.filesDir, ".agents/$zipRel")
        zipFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            for (i in 0 until 250) {
                zos.putNextEntry(ZipEntry("f/$i.txt"))
                zos.write("x".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }

        val outRel = "artifacts/archive/zip-list-" + UUID.randomUUID().toString().replace("-", "").take(8) + ".json"
        val out = tool.exec("zip list --in $zipRel --max 5 --out $outRel")
        assertEquals(0, out.exitCode)
        assertTrue(out.artifacts.any { it.endsWith("/$outRel") })

        val outFile = File(tool.filesDir, ".agents/$outRel")
        assertTrue("list --out file should exist: $outFile", outFile.exists())
        val text = outFile.readText(Charsets.UTF_8)
        assertTrue(text.contains("\"count_total\""))
        assertTrue(text.contains("\"entries\""))
    }

    @Test
    fun tar_create_requiresConfirm() = runTest { tool ->
        val srcRel = "workspace/tar-src-" + UUID.randomUUID().toString().replace("-", "").take(8)
        val srcDir = File(tool.filesDir, ".agents/$srcRel")
        srcDir.mkdirs()
        File(srcDir, "a.txt").writeText("hello", Charsets.UTF_8)

        val out = tool.exec("tar create --src $srcRel --out workspace/out.tar")
        assertTrue(out.exitCode != 0)
        assertEquals("ConfirmRequired", out.errorCode)
    }

    @Test
    fun tar_extract_blocksPathTraversalEntry() = runTest { tool ->
        val tarRel = "workspace/bad-" + UUID.randomUUID().toString().replace("-", "").take(8) + ".tar"
        val tarFile = File(tool.filesDir, ".agents/$tarRel")
        tarFile.parentFile?.mkdirs()

        TarArchiveOutputStream(FileOutputStream(tarFile)).use { tout ->
            tout.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tout.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)

            val evil = TarArchiveEntry("../evil.txt")
            val evilBytes = "nope".toByteArray(Charsets.UTF_8)
            evil.size = evilBytes.size.toLong()
            tout.putArchiveEntry(evil)
            tout.write(evilBytes)
            tout.closeArchiveEntry()

            val ok = TarArchiveEntry("ok.txt")
            val okBytes = "ok".toByteArray(Charsets.UTF_8)
            ok.size = okBytes.size.toLong()
            tout.putArchiveEntry(ok)
            tout.write(okBytes)
            tout.closeArchiveEntry()

            tout.finish()
        }

        val destRel = "workspace/unpack-" + UUID.randomUUID().toString().replace("-", "").take(8)
        val out = tool.exec("tar extract --in $tarRel --dest $destRel --confirm")
        assertEquals(0, out.exitCode)

        val escaped = File(tool.filesDir, ".agents/workspace/evil.txt")
        assertTrue("tar slip must not create escaped file: $escaped", !escaped.exists())

        val skipped = out.result?.get("skipped")?.jsonObject
        val unsafe = skipped?.get("unsafe_path") as? JsonPrimitive
        assertTrue("expected skipped.unsafe_path >= 1", (unsafe?.content?.toLongOrNull() ?: 0L) >= 1L)
    }

    @Test
    fun qqmail_send_withoutConfirm_isRejected() = runTest { tool ->
        val out = tool.exec("qqmail send --to a@example.com --subject hi --body-stdin")
        assertTrue(out.exitCode != 0)
        assertEquals("ConfirmRequired", out.errorCode)
    }

    @Test
    fun qqmail_fetch_missingCredentials_isRejected() = runTest(
        setup = { context ->
            val agentsRoot = File(context.filesDir, ".agents")
            val env = File(agentsRoot, "skills/qqmail-cli/secrets/.env")
            env.parentFile?.mkdirs()
            env.writeText(
                """
                EMAIL_ADDRESS=
                EMAIL_PASSWORD=
                """.trimIndent() + "\n",
                Charsets.UTF_8,
            )
            val teardown = { }
            teardown
        },
    ) { tool ->
        val out = tool.exec("qqmail fetch")
        assertTrue(out.exitCode != 0)
        assertEquals("MissingCredentials", out.errorCode)
    }

    @Test
    fun qqmail_fetch_writesMarkdown_andSupportsOutArtifact_andDedupesByMessageId() = runTest(
        setup = { context ->
            val agentsRoot = File(context.filesDir, ".agents")
            val env = File(agentsRoot, "skills/qqmail-cli/secrets/.env")
            env.parentFile?.mkdirs()
            env.writeText(
                """
                EMAIL_ADDRESS=test@qq.com
                EMAIL_PASSWORD=SUPER_SECRET_AUTH_CODE
                SMTP_SERVER=smtp.qq.com
                SMTP_PORT=465
                IMAP_SERVER=imap.qq.com
                IMAP_PORT=993
                """.trimIndent() + "\n",
                Charsets.UTF_8,
            )

            val fakeImap =
                object : QqMailImapClient {
                    override suspend fun fetchLatest(
                        folder: String,
                        limit: Int,
                    ): List<QqMailMessage> {
                        return listOf(
                            QqMailMessage(
                                folder = folder,
                                messageId = "<m1@test>",
                                subject = "Hello 1",
                                from = "a@test",
                                to = "test@qq.com",
                                dateMs = 1_700_000_000_000L,
                                bodyText = "Body 1",
                            ),
                            QqMailMessage(
                                folder = folder,
                                messageId = "<m2@test>",
                                subject = "Hello 2",
                                from = "b@test",
                                to = "test@qq.com",
                                dateMs = 1_700_000_000_001L,
                                bodyText = "Body 2",
                            ),
                        ).take(limit.coerceAtLeast(0))
                    }
                }
            val fakeSmtp =
                object : QqMailSmtpClient {
                    override suspend fun send(req: QqMailSendRequest): QqMailSendResult {
                        return QqMailSendResult(messageId = "<sent@test>")
                    }
                }
            QqMailCommandTestHooks.install(imap = fakeImap, smtp = fakeSmtp)
            val teardown = { QqMailCommandTestHooks.clear() }
            teardown
        },
    ) { tool ->
        val outRel = "artifacts/qqmail/test-fetch.json"
        val out1 = tool.exec("qqmail fetch --folder INBOX --limit 2 --out $outRel")
        assertEquals(0, out1.exitCode)
        assertTrue(out1.artifacts.contains(".agents/$outRel"))

        val outFile = File(tool.filesDir, ".agents/$outRel")
        assertTrue(outFile.exists())
        assertTrue(outFile.readText(Charsets.UTF_8).contains("\"count_total\""))

        val inboxDir = File(tool.filesDir, ".agents/workspace/qqmail/inbox")
        assertTrue(inboxDir.exists())
        val firstFiles = inboxDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty()
        assertEquals(2, firstFiles.size)
        assertTrue(firstFiles[0].readText(Charsets.UTF_8).contains("message_id:"))

        val out2 = tool.exec("qqmail fetch --folder INBOX --limit 2")
        assertEquals(0, out2.exitCode)
        val secondFiles = inboxDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty()
        assertEquals(2, secondFiles.size)

        val audit = File(tool.filesDir, ".agents/artifacts/terminal_exec/runs/${out2.runId}.json").readText(Charsets.UTF_8)
        assertTrue("audit must not contain email password", !audit.contains("SUPER_SECRET_AUTH_CODE"))
    }

    @Test
    fun qqmail_send_supportsBodyStdin_andOutArtifact_andNeverEchoesSecrets() = runTest(
        setup = { context ->
            val agentsRoot = File(context.filesDir, ".agents")
            val env = File(agentsRoot, "skills/qqmail-cli/secrets/.env")
            env.parentFile?.mkdirs()
            env.writeText(
                """
                EMAIL_ADDRESS=test@qq.com
                EMAIL_PASSWORD=SUPER_SECRET_AUTH_CODE
                SMTP_SERVER=smtp.qq.com
                SMTP_PORT=465
                IMAP_SERVER=imap.qq.com
                IMAP_PORT=993
                """.trimIndent() + "\n",
                Charsets.UTF_8,
            )

            val fakeImap =
                object : QqMailImapClient {
                    override suspend fun fetchLatest(folder: String, limit: Int): List<QqMailMessage> = emptyList()
                }
            val fakeSmtp =
                object : QqMailSmtpClient {
                    override suspend fun send(req: QqMailSendRequest): QqMailSendResult {
                        return QqMailSendResult(messageId = "<sent@test>")
                    }
                }
            QqMailCommandTestHooks.install(imap = fakeImap, smtp = fakeSmtp)
            val teardown = { QqMailCommandTestHooks.clear() }
            teardown
        },
    ) { tool ->
        val outRel = "artifacts/qqmail/test-send.json"
        val out = tool.exec(
            command = "qqmail send --to a@example.com --subject hi --body-stdin --confirm --out $outRel",
            stdin = "hello from stdin",
        )
        assertEquals(0, out.exitCode)
        assertTrue(out.artifacts.contains(".agents/$outRel"))

        val outFile = File(tool.filesDir, ".agents/$outRel")
        assertTrue(outFile.exists())
        assertTrue(outFile.readText(Charsets.UTF_8).contains("\"saved_path\""))

        val sentDir = File(tool.filesDir, ".agents/workspace/qqmail/sent")
        assertTrue(sentDir.exists())
        val md = sentDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty()
        assertTrue(md.isNotEmpty())

        val audit = File(tool.filesDir, ".agents/artifacts/terminal_exec/runs/${out.runId}.json").readText(Charsets.UTF_8)
        assertTrue("audit must not contain email password", !audit.contains("SUPER_SECRET_AUTH_CODE"))
        assertTrue("audit must not contain stdin body", !audit.contains("hello from stdin"))
    }

    @Test
    fun qqmail_rejectsSensitiveArgv() = runTest(
        setup = { context ->
            val agentsRoot = File(context.filesDir, ".agents")
            val env = File(agentsRoot, "skills/qqmail-cli/secrets/.env")
            env.parentFile?.mkdirs()
            env.writeText(
                """
                EMAIL_ADDRESS=test@qq.com
                EMAIL_PASSWORD=SUPER_SECRET_AUTH_CODE
                """.trimIndent() + "\n",
                Charsets.UTF_8,
            )
            QqMailCommandTestHooks.install(
                imap =
                    object : QqMailImapClient {
                        override suspend fun fetchLatest(folder: String, limit: Int): List<QqMailMessage> = emptyList()
                    },
                smtp =
                    object : QqMailSmtpClient {
                        override suspend fun send(req: QqMailSendRequest): QqMailSendResult {
                            return QqMailSendResult(messageId = "<sent@test>")
                        }
                    },
            )
            val teardown = { QqMailCommandTestHooks.clear() }
            teardown
        },
    ) { tool ->
        val out = tool.exec("qqmail fetch --password SUPER_SECRET_AUTH_CODE")
        assertTrue(out.exitCode != 0)
        assertEquals("SensitiveArgv", out.errorCode)
    }

    @Test
    fun tar_create_extract_roundtrip() = runTest { tool ->
        val id = UUID.randomUUID().toString().replace("-", "").take(8)
        val srcRel = "workspace/tar-roundtrip-src-$id"
        val outRel = "workspace/tar-roundtrip-$id.tar"
        val destRel = "workspace/tar-roundtrip-dest-$id"

        val srcDir = File(tool.filesDir, ".agents/$srcRel")
        srcDir.mkdirs()
        File(srcDir, "a.txt").writeText("hello-tar", Charsets.UTF_8)

        val create = tool.exec("tar create --src $srcRel --out $outRel --confirm")
        assertEquals(0, create.exitCode)
        assertTrue(File(tool.filesDir, ".agents/$outRel").exists())

        val extract = tool.exec("tar extract --in $outRel --dest $destRel --confirm")
        assertEquals(0, extract.exitCode)
        val extracted = File(tool.filesDir, ".agents/$destRel/a.txt")
        assertTrue(extracted.exists())
        assertEquals("hello-tar", extracted.readText(Charsets.UTF_8))
    }

    private data class ExecOut(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val runId: String,
        val errorCode: String?,
        val result: JsonObject?,
        val artifacts: List<String>,
        val filesDir: File,
    )

    private fun runTest(
        setup: (android.content.Context) -> (() -> Unit) = { { } },
        block: suspend (TestHarness) -> Unit,
    ) {
        val context = RuntimeEnvironment.getApplication()
        val teardown = setup(context)
        AgentsWorkspace(context).ensureInitialized()

        try {
            val tool = TerminalExecTool(appContext = context)
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = File(context.filesDir, ".agents").absolutePath.replace('\\', '/').toPath())
            val harness = TestHarness(tool = tool, ctx = ctx, filesDir = context.filesDir)

            kotlinx.coroutines.runBlocking {
                block(harness)
            }
        } finally {
            teardown()
        }
    }

    private class TestHarness(
        private val tool: TerminalExecTool,
        private val ctx: ToolContext,
        val filesDir: File,
    ) {
        suspend fun exec(
            command: String,
            stdin: String? = null,
        ): ExecOut {
            val input =
                buildJsonObject {
                    put("command", JsonPrimitive(command))
                    if (stdin != null) put("stdin", JsonPrimitive(stdin))
                }
            val out0 = tool.run(input, ctx)
            val json = (out0 as ToolOutput.Json).value
            assertNotNull(json)
            val obj = json!!.jsonObject
            val resultObj =
                when (val r = obj["result"]) {
                    null, is JsonNull -> null
                    else -> r.jsonObject
                }
            val artifacts =
                obj["artifacts"]?.jsonArray?.mapNotNull { el ->
                    (el as? JsonObject)?.get("path")?.let { p ->
                        (p as? JsonPrimitive)?.content
                    }
                }.orEmpty()
            return ExecOut(
                exitCode = (obj["exit_code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1,
                stdout = (obj["stdout"] as? JsonPrimitive)?.content ?: "",
                stderr = (obj["stderr"] as? JsonPrimitive)?.content ?: "",
                runId = (obj["run_id"] as? JsonPrimitive)?.content ?: "",
                errorCode = (obj["error_code"] as? JsonPrimitive)?.content,
                result = resultObj,
                artifacts = artifacts,
                filesDir = filesDir,
            )
        }
    }

    private class CapturingFinnhubTransport(
        private val statusCode: Int,
        private val bodyText: String,
        private val headers: Map<String, String>,
    ) : FinnhubTransport {
        @Volatile var lastUrl: HttpUrl? = null
        @Volatile var lastHeaders: Map<String, String>? = null

        override suspend fun get(
            url: HttpUrl,
            headers: Map<String, String>,
        ): FinnhubHttpResponse {
            lastUrl = url
            lastHeaders = headers.toMap()
            return FinnhubHttpResponse(statusCode = statusCode, bodyText = bodyText, headers = this.headers)
        }
    }

    private class CapturingRssTransport(
        private val statusCode: Int,
        private val bodyText: String,
        private val headers: Map<String, String>,
    ) : RssTransport {
        @Volatile var lastUrl: HttpUrl? = null
        @Volatile var lastHeaders: Map<String, String>? = null

        override suspend fun get(
            url: HttpUrl,
            headers: Map<String, String>,
        ): RssHttpResponse {
            lastUrl = url
            lastHeaders = headers.toMap()
            return RssHttpResponse(statusCode = statusCode, bodyText = bodyText, headers = this.headers)
        }
    }
}
