package com.lsl.kotlin_agent_app.agent.tools.terminal

import com.lsl.kotlin_agent_app.agent.tools.exchange_rate.ExchangeRateClientTestHooks
import java.io.File
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalExecToolExchangeRateTest {

    @Test
    fun exchangeRate_help_forms_exit0() =
        runTerminalExecToolTest { tool ->
            val forms =
                listOf(
                    "exchange-rate --help",
                    "exchange-rate help",
                    "exchange-rate latest --help",
                    "exchange-rate help latest",
                    "exchange-rate convert --help",
                    "exchange-rate help convert",
                )
            for (cmd in forms) {
                val out = tool.exec(cmd)
                assertEquals("help should exit 0 for: $cmd", 0, out.exitCode)
                val r = out.result ?: error("missing result for: $cmd")
                assertEquals(true, r["ok"]!!.jsonPrimitive.content.toBooleanStrict())
                assertTrue("stdout should be non-empty for: $cmd", out.stdout.isNotBlank())
            }
        }

    @Test
    fun exchangeRate_latest_withSymbols_onlyReturnsRequestedKeys() {
        var transport: CapturingExchangeRateTransport? = null
        runTerminalExecToolTest(
            setup = { context ->
                val cache = File(context.filesDir, ".agents/cache/exchange-rate/latest-CNY.json")
                if (cache.exists()) cache.delete()

                transport =
                    CapturingExchangeRateTransport(
                        statusCode = 200,
                        bodyText = fakeExchangeRateLatestCnyJson(nextUpdateUtc = "Tue, 18 Feb 2099 00:00:01 +0000"),
                        headers = emptyMap(),
                    )
                ExchangeRateClientTestHooks.install(transport!!);
                { ExchangeRateClientTestHooks.clear() }
            },
        ) { t ->
            val out = t.exec("exchange-rate latest --base CNY --symbols USD,EUR")
            assertEquals(0, out.exitCode)
            val r = out.result ?: error("missing result")
            assertEquals("exchange-rate latest", r["command"]!!.jsonPrimitive.content)
            val rates = r["rates"]!!.jsonObject
            assertEquals(setOf("USD", "EUR"), rates.keys)

            val url = transport!!.lastUrl
            assertNotNull(url)
            assertTrue(url!!.encodedPath.endsWith("/v6/latest/CNY"))
        }
    }

    @Test
    fun exchangeRate_latest_withOut_writesArtifact() {
        var transport: CapturingExchangeRateTransport? = null
        runTerminalExecToolTest(
            setup = { context ->
                val cache = File(context.filesDir, ".agents/cache/exchange-rate/latest-CNY.json")
                if (cache.exists()) cache.delete()

                val outFile = File(context.filesDir, ".agents/artifacts/exchange-rate/latest-CNY.json")
                if (outFile.exists()) outFile.delete()

                transport =
                    CapturingExchangeRateTransport(
                        statusCode = 200,
                        bodyText = fakeExchangeRateLatestCnyJson(nextUpdateUtc = "Tue, 18 Feb 2099 00:00:01 +0000"),
                        headers = emptyMap(),
                    )
                ExchangeRateClientTestHooks.install(transport!!);
                { ExchangeRateClientTestHooks.clear() }
            },
        ) { t ->
            val out = t.exec("exchange-rate latest --base CNY --out artifacts/exchange-rate/latest-CNY.json")
            assertEquals(0, out.exitCode)
            assertTrue(out.artifacts.contains(".agents/artifacts/exchange-rate/latest-CNY.json"))
            assertTrue(File(t.filesDir, ".agents/artifacts/exchange-rate/latest-CNY.json").exists())
        }
    }

    @Test
    fun exchangeRate_latest_usesCache_onSecondCall_andNoCacheForcesNetwork() {
        var transport: CapturingExchangeRateTransport? = null
        runTerminalExecToolTest(
            setup = { context ->
                val cache = File(context.filesDir, ".agents/cache/exchange-rate/latest-CNY.json")
                if (cache.exists()) cache.delete()

                transport =
                    CapturingExchangeRateTransport(
                        statusCode = 200,
                        bodyText = fakeExchangeRateLatestCnyJson(nextUpdateUtc = "Tue, 18 Feb 2099 00:00:01 +0000"),
                        headers = emptyMap(),
                    )
                ExchangeRateClientTestHooks.install(transport!!);
                { ExchangeRateClientTestHooks.clear() }
            },
        ) { tool ->
            val out1 = tool.exec("exchange-rate latest --base CNY --symbols USD")
            assertEquals(0, out1.exitCode)
            assertEquals(false, out1.result!!["cached"]!!.jsonPrimitive.content.toBooleanStrict())
            assertEquals(1, transport!!.callCount)

            val out2 = tool.exec("exchange-rate latest --base CNY --symbols USD")
            assertEquals(0, out2.exitCode)
            assertEquals(true, out2.result!!["cached"]!!.jsonPrimitive.content.toBooleanStrict())
            assertEquals(1, transport!!.callCount)

            val out3 = tool.exec("exchange-rate latest --base CNY --symbols USD --no-cache")
            assertEquals(0, out3.exitCode)
            assertEquals(false, out3.result!!["cached"]!!.jsonPrimitive.content.toBooleanStrict())
            assertEquals(2, transport!!.callCount)

            val out4 = tool.exec("exchange-rate latest --base CNY --symbols USD --no-cache")
            assertEquals(0, out4.exitCode)
            assertEquals(false, out4.result!!["cached"]!!.jsonPrimitive.content.toBooleanStrict())
            assertEquals(3, transport!!.callCount)
        }
    }

    @Test
    fun exchangeRate_remoteError_isMapped() {
        var transport: CapturingExchangeRateTransport? = null
        runTerminalExecToolTest(
            setup = {
                transport =
                    CapturingExchangeRateTransport(
                        statusCode = 200,
                        bodyText = """{"result":"error","error-type":"invalid-base"}""",
                        headers = emptyMap(),
                    )
                ExchangeRateClientTestHooks.install(transport!!);
                { ExchangeRateClientTestHooks.clear() }
            },
        ) { tool ->
            val out = tool.exec("exchange-rate latest --base XXX --symbols USD")
            assertTrue(out.exitCode != 0)
            assertEquals("RemoteError", out.errorCode)
        }
    }

    @Test
    fun exchangeRate_convert_invalidAmount_isRejected() =
        runTerminalExecToolTest { tool ->
            val out = tool.exec("exchange-rate convert --from CNY --to USD --amount abc")
            assertTrue(out.exitCode != 0)
            assertEquals("InvalidArgs", out.errorCode)
        }
}
