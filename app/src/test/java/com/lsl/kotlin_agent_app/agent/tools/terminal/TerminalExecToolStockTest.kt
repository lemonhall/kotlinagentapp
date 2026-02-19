package com.lsl.kotlin_agent_app.agent.tools.terminal

import com.lsl.kotlin_agent_app.agent.tools.stock.FinnhubClientTestHooks
import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalExecToolStockTest {

    @Test
    fun stock_symbols_withoutOut_isRejectedWithOutRequired() =
        runTerminalExecToolTest { tool ->
            val out = tool.exec("stock symbols --exchange US")
            assertTrue(out.exitCode != 0)
            assertEquals("OutRequired", out.errorCode)
        }

    @Test
    fun stock_outPathWithDotDot_isRejected() =
        runTerminalExecToolTest { tool ->
            val out = tool.exec("stock symbols --exchange US --out ../pwn.json")
            assertTrue(out.exitCode != 0)
            assertEquals("PathEscapesAgentsRoot", out.errorCode)
        }

    @Test
    fun stock_missingCredentials_isRejected() =
        runTerminalExecToolTest { tool ->
            val out = tool.exec("stock quote --symbol AAPL")
            assertTrue(out.exitCode != 0)
            assertEquals("MissingCredentials", out.errorCode)
        }

    @Test
    fun stock_candle_isNotSupported() =
        runTerminalExecToolTest { tool ->
            val out = tool.exec("stock candle --symbol AAPL")
            assertTrue(out.exitCode != 0)
            assertEquals("NotSupported", out.errorCode)
        }

    @Test
    fun stock_quote_happyPath_makesRequest_andDoesNotLeakToken() {
        var transport: CapturingFinnhubTransport? = null
        val token = "TEST_FINNHUB_TOKEN_123"
        runTerminalExecToolTest(
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
                FinnhubClientTestHooks.install(transport!!);
                { FinnhubClientTestHooks.clear() }
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
        runTerminalExecToolTest(
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
                FinnhubClientTestHooks.install(transport!!);
                { FinnhubClientTestHooks.clear() }
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
        runTerminalExecToolTest(
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
                FinnhubClientTestHooks.install(transport!!);
                { FinnhubClientTestHooks.clear() }
            },
        ) { tool ->
            val out = tool.exec("stock quote --symbol AAPL")
            assertTrue(out.exitCode != 0)
            assertEquals("RateLimited", out.errorCode)
            val retryAfterMs = (out.result?.get("retry_after_ms") as? JsonPrimitive)?.content?.toLongOrNull()
            assertEquals(2_000L, retryAfterMs)
        }
    }
}
