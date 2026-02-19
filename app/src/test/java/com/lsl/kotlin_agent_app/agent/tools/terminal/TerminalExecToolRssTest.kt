package com.lsl.kotlin_agent_app.agent.tools.terminal

import com.lsl.kotlin_agent_app.agent.tools.rss.RssClientTestHooks
import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalExecToolRssTest {

    @Test
    fun rss_add_writesSubscriptions_and_list_readsBack() =
        runTerminalExecToolTest { tool ->
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
    fun rss_remove_missing_isNotFound() =
        runTerminalExecToolTest { tool ->
            val out = tool.exec("rss remove --name no_such_feed")
            assertTrue(out.exitCode != 0)
            assertEquals("NotFound", out.errorCode)
        }

    @Test
    fun rss_fetch_fileScheme_isRejected() =
        runTerminalExecToolTest { tool ->
            val out = tool.exec("rss fetch --url file:///etc/passwd --max-items 5")
            assertTrue(out.exitCode != 0)
            assertEquals("InvalidArgs", out.errorCode)
        }

    @Test
    fun rss_fetch_429_isRateLimited_andReturnsRetryAfterMs() {
        var transport: CapturingRssTransport? = null
        runTerminalExecToolTest(
            setup = {
                transport =
                    CapturingRssTransport(
                        statusCode = 429,
                        bodyText = "rate limited",
                        headers = mapOf("Retry-After" to "5"),
                    )
                RssClientTestHooks.install(transport!!)
                { RssClientTestHooks.clear() }
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

        runTerminalExecToolTest(
            setup = {
                transport =
                    CapturingRssTransport(
                        statusCode = 200,
                        bodyText = rssXml,
                        headers = mapOf("ETag" to "W/\"abc\"", "Last-Modified" to "Wed, 18 Feb 2026 01:30:32 GMT"),
                    )
                RssClientTestHooks.install(transport!!)
                { RssClientTestHooks.clear() }
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

            tool.exec("rss fetch --name test-feed --max-items 1")
            val lastHeaders = transport?.lastHeaders.orEmpty()
            assertTrue("If-None-Match should be set", lastHeaders.keys.any { it.equals("If-None-Match", ignoreCase = true) })
        }
    }
}

