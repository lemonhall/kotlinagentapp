package com.lsl.kotlin_agent_app.agent.tools

import com.lsl.kotlin_agent_app.web.WebViewControllerProvider
import com.lsl.kotlin_agent_app.web.WebViewState
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.tools.Tool
import me.lemonhall.openagentic.sdk.tools.ToolContext
import me.lemonhall.openagentic.sdk.tools.ToolInput
import me.lemonhall.openagentic.sdk.tools.ToolOutput

interface WebViewControllerApi {
    suspend fun goto(url: String): WebViewState

    suspend fun back(): WebViewState

    suspend fun forward(): WebViewState

    suspend fun reload(): WebViewState

    suspend fun getState(): WebViewState

    suspend fun runScript(script: String): String

    suspend fun getDom(
        selector: String?,
        mode: String,
    ): String
}

private class DefaultWebViewControllerApi : WebViewControllerApi {
    private val controller = WebViewControllerProvider.instance

    override suspend fun goto(url: String): WebViewState = controller.goto(url)

    override suspend fun back(): WebViewState = controller.back()

    override suspend fun forward(): WebViewState = controller.forward()

    override suspend fun reload(): WebViewState = controller.reload()

    override suspend fun getState(): WebViewState = controller.getState()

    override suspend fun runScript(script: String): String = controller.runScript(script)

    override suspend fun getDom(
        selector: String?,
        mode: String,
    ): String = controller.getDom(selector = selector, mode = mode)
}

class WebViewTool(
    private val api: WebViewControllerApi = DefaultWebViewControllerApi(),
) : Tool {
    override val name: String = "WebView"

    override val description: String =
        """
        Control the in-app persistent WebView (single instance). One tool, multiple actions.

        Input JSON:
        - action: string (required). One of: goto, get_state, get_dom, run_script, back, forward, reload
        - url: string (for goto)
        - script: string (for run_script)
        - selector: string? (for get_dom; optional CSS selector)
        - mode: string? (for get_dom; "outerHTML" (default) or "text")

        Output JSON:
        - ok: boolean
        - action: string
        - data: object|null
        - error: { message: string }|null
        """.trimIndent()

    override suspend fun run(
        input: ToolInput,
        ctx: ToolContext,
    ): ToolOutput {
        val action = input.string("action")?.trim().orEmpty()
        if (action.isEmpty()) {
            return ToolOutput.Json(error(action = "missing", message = "missing input.action"))
        }

        return try {
            when (action) {
                "goto" -> {
                    val url = input.string("url")?.trim().orEmpty()
                    if (url.isEmpty()) return ToolOutput.Json(error(action = action, message = "missing input.url"))
                    ToolOutput.Json(ok(action, dataState(api.goto(url))))
                }
                "get_state" -> ToolOutput.Json(ok(action, dataState(api.getState())))
                "back" -> ToolOutput.Json(ok(action, dataState(api.back())))
                "forward" -> ToolOutput.Json(ok(action, dataState(api.forward())))
                "reload" -> ToolOutput.Json(ok(action, dataState(api.reload())))
                "run_script" -> {
                    val script = input.string("script")?.trim().orEmpty()
                    if (script.isEmpty()) return ToolOutput.Json(error(action = action, message = "missing input.script"))
                    ToolOutput.Json(
                        ok(
                            action,
                            buildJsonObject {
                                put("result", JsonPrimitive(api.runScript(script)))
                            },
                        ),
                    )
                }
                "get_dom" -> {
                    val selector = input.string("selector")?.trim()
                    val mode = input.string("mode")?.trim().orEmpty().ifEmpty { "outerHTML" }
                    ToolOutput.Json(
                        ok(
                            action,
                            buildJsonObject {
                                put("mode", JsonPrimitive(mode))
                                put("selector", selector?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("result", JsonPrimitive(api.getDom(selector = selector, mode = mode)))
                            },
                        ),
                    )
                }
                else -> ToolOutput.Json(error(action = action, message = "unknown action: $action"))
            }
        } catch (t: Throwable) {
            ToolOutput.Json(error(action = action, message = t.message ?: t::class.java.simpleName))
        }
    }

    private fun ToolInput.string(key: String): String? {
        val el = this[key] as? JsonPrimitive ?: return null
        return el.content
    }

    private fun ok(
        action: String,
        data: JsonObject?,
    ): JsonObject =
        buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("action", JsonPrimitive(action))
            put("data", data ?: JsonNull)
            put("error", JsonNull)
        }

    private fun error(
        action: String,
        message: String,
    ): JsonObject =
        buildJsonObject {
            put("ok", JsonPrimitive(false))
            put("action", JsonPrimitive(action))
            put("data", JsonNull)
            put(
                "error",
                buildJsonObject {
                    put("message", JsonPrimitive(message))
                },
            )
        }

    private fun dataState(state: WebViewState): JsonObject =
        buildJsonObject {
            put("url", state.url?.let { JsonPrimitive(it) } ?: JsonNull)
            put("title", state.title?.let { JsonPrimitive(it) } ?: JsonNull)
            put("canGoBack", JsonPrimitive(state.canGoBack))
            put("canGoForward", JsonPrimitive(state.canGoForward))
            put("loading", JsonPrimitive(state.loading))
            put("progress", JsonPrimitive(state.progress))
        }
}
