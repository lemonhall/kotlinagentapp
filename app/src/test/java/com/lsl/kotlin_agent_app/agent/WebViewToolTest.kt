package com.lsl.kotlin_agent_app.agent

import com.lsl.kotlin_agent_app.MainDispatcherRule
import com.lsl.kotlin_agent_app.agent.tools.WebViewControllerApi
import com.lsl.kotlin_agent_app.agent.tools.WebViewTool
import com.lsl.kotlin_agent_app.web.WebViewState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.tools.ToolContext
import me.lemonhall.openagentic.sdk.tools.ToolOutput
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WebViewToolTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun missingAction_returnsError() = runTest {
        val tool = WebViewTool(api = FakeApi())
        val out = tool.run(buildJsonObject {}, ctx())
        val json = (out as ToolOutput.Json).value as JsonObject
        assertEquals(false, (json["ok"] as JsonPrimitive).content.toBoolean())
    }

    @Test
    fun goto_returnsState() = runTest {
        val tool = WebViewTool(api = FakeApi())
        val out =
            tool.run(
                buildJsonObject {
                    put("action", JsonPrimitive("goto"))
                    put("url", JsonPrimitive("https://example.com"))
                },
                ctx(),
            )
        val json = (out as ToolOutput.Json).value as JsonObject
        assertEquals(true, (json["ok"] as JsonPrimitive).content.toBoolean())
        val data = json["data"] as JsonObject
        assertEquals("https://example.com", (data["url"] as JsonPrimitive).content)
    }

    @Test
    fun unknownAction_returnsError() = runTest {
        val tool = WebViewTool(api = FakeApi())
        val out =
            tool.run(
                buildJsonObject {
                    put("action", JsonPrimitive("nope"))
                },
                ctx(),
            )
        val json = (out as ToolOutput.Json).value as JsonObject
        assertEquals(false, (json["ok"] as JsonPrimitive).content.toBoolean())
        val err = json["error"] as JsonObject
        assertTrue((err["message"] as JsonPrimitive).content.contains("unknown action"))
    }

    private fun ctx(): ToolContext =
        ToolContext(
            fileSystem = FileSystem.SYSTEM,
            cwd = "/".toPath(),
            projectDir = "/".toPath(),
        )

    private class FakeApi : WebViewControllerApi {
        override suspend fun goto(url: String): WebViewState =
            WebViewState(
                url = url,
                title = "t",
                canGoBack = false,
                canGoForward = false,
                loading = true,
                progress = 1,
            )

        override suspend fun back(): WebViewState = goto("about:back")

        override suspend fun forward(): WebViewState = goto("about:fwd")

        override suspend fun reload(): WebViewState = goto("about:reload")

        override suspend fun getState(): WebViewState = goto("about:state")

        override suspend fun runScript(script: String): String = "\"ok\""

        override suspend fun getDom(
            selector: String?,
            mode: String,
        ): String = "\"<html/>\""
    }
}
