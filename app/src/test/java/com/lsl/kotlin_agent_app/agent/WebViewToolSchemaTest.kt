package com.lsl.kotlin_agent_app.agent

import com.lsl.kotlin_agent_app.agent.tools.WebViewTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.lemonhall.openagentic.sdk.tools.OpenAiToolSchemas
import me.lemonhall.openagentic.sdk.tools.ToolContext
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewToolSchemaTest {
    @Test
    fun webViewTool_schemaIsIncludedInResponsesSchemas() {
        val registry = ToolRegistry(listOf(WebViewTool(api = FakeWebApi())))
        val ctx =
            ToolContext(
                fileSystem = FileSystem.SYSTEM,
                cwd = "/".toPath(),
                projectDir = "/".toPath(),
            )

        val schemas = OpenAiToolSchemas.forResponses(toolNames = listOf("WebView"), registry = registry, ctx = ctx)
        assertEquals(1, schemas.size)
        val schema = schemas.first()
        assertEquals("function", (schema["type"] as JsonPrimitive).content)
        assertEquals("WebView", (schema["name"] as JsonPrimitive).content)
        val params = schema["parameters"] as JsonObject
        val props = params["properties"] as JsonObject
        assertTrue(props.containsKey("action"))
    }

    private class FakeWebApi : com.lsl.kotlin_agent_app.agent.tools.WebViewControllerApi {
        override suspend fun goto(url: String) = com.lsl.kotlin_agent_app.web.WebViewState(url = url)

        override suspend fun back() = com.lsl.kotlin_agent_app.web.WebViewState(url = "about:back")

        override suspend fun forward() = com.lsl.kotlin_agent_app.web.WebViewState(url = "about:fwd")

        override suspend fun reload() = com.lsl.kotlin_agent_app.web.WebViewState(url = "about:reload")

        override suspend fun getState() = com.lsl.kotlin_agent_app.web.WebViewState(url = "about:state")

        override suspend fun runScript(script: String) = "\"ok\""

        override suspend fun getDom(selector: String?, mode: String) = "\"<html/>\""
    }
}

