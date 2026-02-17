package com.lsl.kotlin_agent_app.agent.tools.web

import android.content.Context
import android.graphics.Bitmap
import com.lsl.agentbrowser.ActionKind
import com.lsl.agentbrowser.AgentBrowser
import com.lsl.agentbrowser.FillPayload
import com.lsl.agentbrowser.PageKind
import com.lsl.agentbrowser.PagePayload
import com.lsl.agentbrowser.QueryKind
import com.lsl.agentbrowser.QueryPayload
import com.lsl.agentbrowser.RenderOptions
import com.lsl.agentbrowser.SelectPayload
import com.lsl.agentbrowser.SnapshotJsOptions
import com.lsl.agentbrowser.TypePayload
import com.lsl.agentbrowser.openai.WebToolsOpenAiSchema
import com.lsl.kotlin_agent_app.web.WebViewControllerProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.lemonhall.openagentic.sdk.tools.OpenAiSchemaTool
import me.lemonhall.openagentic.sdk.tools.Tool
import me.lemonhall.openagentic.sdk.tools.ToolContext
import me.lemonhall.openagentic.sdk.tools.ToolInput
import me.lemonhall.openagentic.sdk.tools.ToolOutput
import me.lemonhall.openagentic.sdk.tools.ToolRegistry

internal class WebToolRuntime(
    private val appContext: Context,
    private val allowEval: Boolean,
) {
    private val controller = WebViewControllerProvider.instance
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val scriptSha256: String by lazy {
        val script = AgentBrowser.getScript()
        val dig = MessageDigest.getInstance("SHA-256").digest(script.toByteArray(Charsets.UTF_8))
        dig.joinToString("") { b -> "%02x".format(b) }
    }

    @Volatile
    private var lastSnapshotSha256: String? = null

    fun snapshotSha256(text: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return dig.joinToString("") { b -> "%02x".format(b) }
    }

    fun updateAndCheckSameSnapshot(currentSha256: String): Boolean {
        val prev = lastSnapshotSha256
        lastSnapshotSha256 = currentSha256
        return prev != null && prev == currentSha256
    }

    fun scriptVersionSha256(): String = scriptSha256

    fun isBound(): Boolean = controller.isBound()

    fun isEvalEnabled(): Boolean = allowEval

    suspend fun goto(url: String) {
        controller.goto(url)
    }

    suspend fun back() {
        controller.back()
    }

    suspend fun forward() {
        controller.forward()
    }

    suspend fun reload() {
        controller.reload()
    }

    suspend fun eval(script: String): String = controller.runScript(script)

    fun parseJsonElementFromJsEval(raw: String): JsonElement {
        val normalized = AgentBrowser.normalizeJsEvalResult(raw)
        return json.parseToJsonElement(normalized)
    }

    suspend fun ensureInjected(): Boolean {
        if (!isBound()) return false
        val expectedSha = scriptSha256
        val check =
            "(function(){ try { return (typeof window!=='undefined' && !!window.__agentBrowser && window.__agentBrowser.__script_sha256==='$expectedSha'); } catch(e){ return false; } })()"
        val ok0 = eval(check).trim() == "true"
        if (ok0) return true

        val script = AgentBrowser.getScript()
        val inject =
            script +
                "\n;try{ window.__agentBrowser && (window.__agentBrowser.__script_sha256='" +
                expectedSha +
                "'); }catch(e){}"
        runCatching { eval(inject) }

        val ok1 = eval(check).trim() == "true"
        return ok1
    }

    suspend fun waitForIdle(timeoutMs: Int): Boolean {
        val timeout = timeoutMs.coerceAtLeast(0)
        if (timeout == 0) return true
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < timeout) {
            val s = controller.state.value
            if (!s.loading) return true
            delay(100)
        }
        return false
    }

    suspend fun captureScreenshot(label: String?): ScreenshotArtifact? {
        if (!isBound()) return null
        val bmp = controller.capturePreviewBitmap(targetWidth = 900, targetHeight = 520) ?: return null
        val ts = System.currentTimeMillis()
        val safeLabel =
            label
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.replace(Regex("[^a-zA-Z0-9._-]+"), "_")
                ?.take(40)
                ?: "shot"

        val dir = File(File(appContext.filesDir, ".agents"), "artifacts/web_screenshots")
        dir.mkdirs()
        val file = File(dir, "${ts}_${safeLabel}.png")
        withContext(Dispatchers.IO) {
            file.outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        return ScreenshotArtifact(
            path = file.absolutePath.replace('\\', '/'),
            width = bmp.width,
            height = bmp.height,
        )
    }
}

internal data class ScreenshotArtifact(
    val path: String,
    val width: Int,
    val height: Int,
)

internal object OpenAgenticWebTools {
    fun all(
        appContext: Context,
        allowEval: Boolean = false,
    ): List<Tool> {
        val runtime = WebToolRuntime(appContext = appContext.applicationContext, allowEval = allowEval)
        return listOf(
            WebOpenTool(runtime),
            WebBackTool(runtime),
            WebForwardTool(runtime),
            WebReloadTool(runtime),
            WebSnapshotTool(runtime),
            WebClickTool(runtime),
            WebDoubleClickTool(runtime),
            WebFillTool(runtime),
            WebTypeTool(runtime),
            WebSelectTool(runtime),
            WebCheckTool(runtime),
            WebUncheckTool(runtime),
            WebScrollTool(runtime),
            WebPressKeyTool(runtime),
            WebWaitTool(runtime),
            WebHoverTool(runtime),
            WebScrollIntoViewTool(runtime),
            WebQueryTool(runtime),
            WebScreenshotTool(runtime),
            WebEvalTool(runtime),
            WebCloseTool(runtime),
        )
    }
}

private abstract class BaseWebTool(
    protected val runtime: WebToolRuntime,
    private val spec: WebToolsOpenAiSchema.ToolSpec,
) : Tool, OpenAiSchemaTool {
    override val name: String = spec.name
    override val description: String = spec.description

    override fun openAiSchema(ctx: ToolContext, registry: ToolRegistry?): JsonObject = spec.openAiSchema

    protected fun requiredString(input: ToolInput, key: String): String? =
        input[key]?.asString()?.trim()?.takeIf { it.isNotEmpty() }

    protected fun optionalString(input: ToolInput, key: String): String? =
        input[key]?.asString()

    protected fun optionalBool(input: ToolInput, key: String): Boolean? =
        input[key]?.asBoolean()

    protected fun optionalInt(input: ToolInput, key: String): Int? =
        input[key]?.asInt()

    protected fun mustBeBound(): ToolOutput.Json? {
        if (runtime.isBound()) return null
        return error("not_bound", "WebView not bound. Open the Web tab once to initialize.")
    }

    protected fun error(
        code: String,
        message: String,
    ): ToolOutput.Json =
        ToolOutput.Json(
            value =
                buildJsonObject {
                    put("ok", JsonPrimitive(false))
                    put(
                        "error",
                        buildJsonObject {
                            put("code", JsonPrimitive(code))
                            put("message", JsonPrimitive(message))
                        },
                    )
                },
        )
}

private fun isBlockedUrlScheme(url: String): Boolean {
    val lower = url.trim().lowercase()
    if (lower.startsWith("javascript:")) return true
    if (lower.startsWith("data:")) return true
    if (lower.startsWith("vbscript:")) return true
    return false
}

private fun isAllowedOpenUrl(url: String): Boolean {
    val u = url.trim()
    if (u.isEmpty()) return false
    if (isBlockedUrlScheme(u)) return false
    if (u == "about:blank") return true
    return u.startsWith("http://") || u.startsWith("https://") || u.startsWith("file://")
}

private class WebOpenTool(runtime: WebToolRuntime) :
    BaseWebTool(
        runtime = runtime,
        spec = WebToolsOpenAiSchema.WEB_OPEN,
    ) {
    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
        mustBeBound()?.let { return it }
        val url = requiredString(input, "url") ?: return error("missing_url", "web_open requires url")
        if (!isAllowedOpenUrl(url)) return error("invalid_url", "blocked url scheme")

        runtime.goto(url)
        val ok = runtime.waitForIdle(timeoutMs = 12_000)
        if (!ok) return error("timeout", "web_open timed out")
        if (!runtime.ensureInjected()) return error("not_injected", "agent-browser.js not injected")

        val infoRaw = runtime.eval(AgentBrowser.pageJs(PageKind.INFO, PagePayload()))
        val info = AgentBrowser.parsePageSafe(infoRaw)
        return ToolOutput.Json(
            value =
                buildJsonObject {
                    put("ok", JsonPrimitive(info.ok))
                    put("url", JsonPrimitive(info.url ?: url))
                    put("title", JsonPrimitive(info.title ?: ""))
                },
        )
    }
}

private class WebBackTool(runtime: WebToolRuntime) :
    BaseWebTool(runtime = runtime, spec = WebToolsOpenAiSchema.WEB_BACK) {
    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
        mustBeBound()?.let { return it }
        runtime.back()
        runtime.waitForIdle(timeoutMs = 10_000)
        if (!runtime.ensureInjected()) return error("not_injected", "agent-browser.js not injected")
        return ToolOutput.Json(value = runtime.parseJsonElementFromJsEval(runtime.eval(AgentBrowser.pageJs(PageKind.INFO))))
    }
}

private class WebForwardTool(runtime: WebToolRuntime) :
    BaseWebTool(runtime = runtime, spec = WebToolsOpenAiSchema.WEB_FORWARD) {
    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
        mustBeBound()?.let { return it }
        runtime.forward()
        runtime.waitForIdle(timeoutMs = 10_000)
        if (!runtime.ensureInjected()) return error("not_injected", "agent-browser.js not injected")
        return ToolOutput.Json(value = runtime.parseJsonElementFromJsEval(runtime.eval(AgentBrowser.pageJs(PageKind.INFO))))
    }
}

private class WebReloadTool(runtime: WebToolRuntime) :
    BaseWebTool(runtime = runtime, spec = WebToolsOpenAiSchema.WEB_RELOAD) {
    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
        mustBeBound()?.let { return it }
        runtime.reload()
        runtime.waitForIdle(timeoutMs = 12_000)
        if (!runtime.ensureInjected()) return error("not_injected", "agent-browser.js not injected")
        return ToolOutput.Json(value = runtime.parseJsonElementFromJsEval(runtime.eval(AgentBrowser.pageJs(PageKind.INFO))))
    }
}

private class WebSnapshotTool(runtime: WebToolRuntime) :
    BaseWebTool(runtime = runtime, spec = WebToolsOpenAiSchema.WEB_SNAPSHOT) {
    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
        mustBeBound()?.let { return it }
        if (!runtime.ensureInjected()) return error("not_injected", "agent-browser.js not injected")

        val interactiveOnly = optionalBool(input, "interactive_only") ?: true
        val cursorInteractive = optionalBool(input, "cursor_interactive") ?: false
        val scopeRaw = optionalString(input, "scope")?.trim()
        val scope =
            when {
                scopeRaw.isNullOrBlank() -> null
                scopeRaw == "document.body" -> null
                else -> scopeRaw
            }

        // Budget tuning notes:
        // - JS side controls DOM traversal + ref assignment (maxNodes).
        // - Kotlin side controls snapshot_text visibility to the model (maxDepth/maxNodes/maxCharsTotal).
        // Keep output bounded to avoid context overflows, but large enough for "套娃" DOM sites.
        val jsMaxNodes = if (interactiveOnly) 1600 else 5200
        val renderMaxDepth = if (interactiveOnly) 22 else 30
        val renderMaxNodes = if (interactiveOnly) 520 else 900
        val renderMaxCharsTotal = if (interactiveOnly) 18_000 else 28_000

        val raw =
            runtime.eval(
                AgentBrowser.snapshotJs(
                    SnapshotJsOptions(
                        maxNodes = jsMaxNodes,
                        maxTextPerNode = 140,
                        maxAttrValueLen = 96,
                        interactiveOnly = interactiveOnly,
                        cursorInteractive = cursorInteractive,
                        scope = scope,
                    ),
                ),
            )
        val parsed = AgentBrowser.parseSnapshotSafe(raw)
        if (!parsed.ok) {
            return error(code = parsed.error?.code ?: "snapshot_failed", message = parsed.error?.message ?: "snapshot failed")
        }

        val rendered =
            AgentBrowser.renderSnapshot(
                raw,
                RenderOptions(
                    maxCharsTotal = renderMaxCharsTotal,
                    maxNodes = renderMaxNodes,
                    maxDepth = renderMaxDepth,
                    compact = true,
                ),
            )
        val snapshotSha = runtime.snapshotSha256(rendered.text)
        val sameAsPrev = runtime.updateAndCheckSameSnapshot(snapshotSha)

        return ToolOutput.Json(
            value =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("url", JsonPrimitive(parsed.meta?.url ?: ""))
                    put("agent_browser_js_sha256", JsonPrimitive(runtime.scriptVersionSha256()))
                    put("snapshot_sha256", JsonPrimitive(snapshotSha))
                    put("same_as_prev", JsonPrimitive(sameAsPrev))
                    put("snapshot_text", JsonPrimitive(rendered.text))
                    put("truncated", JsonPrimitive(rendered.stats.truncated))
                    put("truncate_reasons", JsonArray(rendered.stats.truncateReasons.map { JsonPrimitive(it) }))
                    put("refs_count", JsonPrimitive(parsed.refs.size))
                },
        )
    }
}

private class WebClickTool(runtime: WebToolRuntime) :
    BaseWebTool(runtime = runtime, spec = WebToolsOpenAiSchema.WEB_CLICK) {
    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
        mustBeBound()?.let { return it }
        if (!runtime.ensureInjected()) return error("not_injected", "agent-browser.js not injected")
        val ref = requiredString(input, "ref") ?: return error("missing_ref", "web_click requires ref")
        val raw = runtime.eval(AgentBrowser.actionJs(ref, ActionKind.CLICK))
        return ToolOutput.Json(value = runtime.parseJsonElementFromJsEval(raw))
    }
}

private class WebDoubleClickTool(runtime: WebToolRuntime) :
    BaseWebTool(runtime = runtime, spec = WebToolsOpenAiSchema.WEB_DBLCLICK) {
    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
        mustBeBound()?.let { return it }
        if (!runtime.ensureInjected()) return error("not_injected", "agent-browser.js not injected")
        val ref = requiredString(input, "ref") ?: return error("missing_ref", "web_dblclick requires ref")
        val firstRaw = runtime.eval(AgentBrowser.actionJs(ref, ActionKind.CLICK))
        val first = AgentBrowser.parseActionSafe(firstRaw)
        if (!first.ok) return ToolOutput.Json(value = runtime.parseJsonElementFromJsEval(firstRaw))
        delay(80)
        val secondRaw = runtime.eval(AgentBrowser.actionJs(ref, ActionKind.CLICK))
        return ToolOutput.Json(value = runtime.parseJsonElementFromJsEval(secondRaw))
    }
}

private class WebFillTool(runtime: WebToolRuntime) :
    BaseWebTool(runtime = runtime, spec = WebToolsOpenAiSchema.WEB_FILL) {
    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
        mustBeBound()?.let { return it }
        if (!runtime.ensureInjected()) return error("not_injected", "agent-browser.js not injected")
        val ref = requiredString(input, "ref") ?: return error("missing_ref", "web_fill requires ref")
        val value = requiredString(input, "value") ?: return error("missing_value", "web_fill requires value")
        val raw = runtime.eval(AgentBrowser.actionJs(ref, ActionKind.FILL, FillPayload(value)))
        return ToolOutput.Json(value = runtime.parseJsonElementFromJsEval(raw))
    }
}

private class WebTypeTool(runtime: WebToolRuntime) :
    BaseWebTool(runtime = runtime, spec = WebToolsOpenAiSchema.WEB_TYPE) {
    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
        mustBeBound()?.let { return it }
        if (!runtime.ensureInjected()) return error("not_injected", "agent-browser.js not injected")
        val ref = requiredString(input, "ref") ?: return error("missing_ref", "web_type requires ref")
        val text = requiredString(input, "text") ?: return error("missing_text", "web_type requires text")
        val raw = runtime.eval(AgentBrowser.actionJs(ref, ActionKind.TYPE, TypePayload(text)))
        return ToolOutput.Json(value = runtime.parseJsonElementFromJsEval(raw))
    }
}

private class WebSelectTool(runtime: WebToolRuntime) :
    BaseWebTool(runtime = runtime, spec = WebToolsOpenAiSchema.WEB_SELECT) {
    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
        mustBeBound()?.let { return it }
        if (!runtime.ensureInjected()) return error("not_injected", "agent-browser.js not injected")
        val ref = requiredString(input, "ref") ?: return error("missing_ref", "web_select requires ref")
        val values =
            (input["values"] as? JsonArray)
                ?.mapNotNull { it.asString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
                .orEmpty()
        if (values.isEmpty()) return error("missing_values", "web_select requires values[]")
        val raw = runtime.eval(AgentBrowser.actionJs(ref, ActionKind.SELECT, SelectPayload(values = values)))
        return ToolOutput.Json(value = runtime.parseJsonElementFromJsEval(raw))
    }
}

private class WebCheckTool(runtime: WebToolRuntime) :
    BaseWebTool(runtime = runtime, spec = WebToolsOpenAiSchema.WEB_CHECK) {
    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
        mustBeBound()?.let { return it }
        if (!runtime.ensureInjected()) return error("not_injected", "agent-browser.js not injected")
        val ref = requiredString(input, "ref") ?: return error("missing_ref", "web_check requires ref")
        val raw = runtime.eval(AgentBrowser.actionJs(ref, ActionKind.CHECK))
        return ToolOutput.Json(value = runtime.parseJsonElementFromJsEval(raw))
    }
}

private class WebUncheckTool(runtime: WebToolRuntime) :
    BaseWebTool(runtime = runtime, spec = WebToolsOpenAiSchema.WEB_UNCHECK) {
    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
        mustBeBound()?.let { return it }
        if (!runtime.ensureInjected()) return error("not_injected", "agent-browser.js not injected")
        val ref = requiredString(input, "ref") ?: return error("missing_ref", "web_uncheck requires ref")
        val raw = runtime.eval(AgentBrowser.actionJs(ref, ActionKind.UNCHECK))
        return ToolOutput.Json(value = runtime.parseJsonElementFromJsEval(raw))
    }
}

private class WebScrollTool(runtime: WebToolRuntime) :
    BaseWebTool(runtime = runtime, spec = WebToolsOpenAiSchema.WEB_SCROLL) {
    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
        mustBeBound()?.let { return it }
        if (!runtime.ensureInjected()) return error("not_injected", "agent-browser.js not injected")
        val direction = requiredString(input, "direction") ?: return error("missing_direction", "web_scroll requires direction")
        val amount = (optionalInt(input, "amount") ?: 300).coerceAtLeast(1)
        val raw = runtime.eval(AgentBrowser.scrollJs(direction, amount))
        return ToolOutput.Json(value = runtime.parseJsonElementFromJsEval(raw))
    }
}

private class WebPressKeyTool(runtime: WebToolRuntime) :
    BaseWebTool(runtime = runtime, spec = WebToolsOpenAiSchema.WEB_PRESS_KEY) {
    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
        mustBeBound()?.let { return it }
        if (!runtime.ensureInjected()) return error("not_injected", "agent-browser.js not injected")
        val key = requiredString(input, "key") ?: return error("missing_key", "web_press_key requires key")
        val raw = runtime.eval(AgentBrowser.pageJs(PageKind.PRESS_KEY, PagePayload(key = key)))
        return ToolOutput.Json(value = runtime.parseJsonElementFromJsEval(raw))
    }
}

private class WebWaitTool(runtime: WebToolRuntime) :
    BaseWebTool(runtime = runtime, spec = WebToolsOpenAiSchema.WEB_WAIT) {
    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
        mustBeBound()?.let { return it }
        val ms = (optionalInt(input, "ms") ?: 0).coerceAtLeast(0)
        val timeoutMs = (optionalInt(input, "timeout_ms") ?: 5000).coerceAtLeast(0)
        val pollMs = (optionalInt(input, "poll_ms") ?: 100).coerceAtLeast(25)
        val selector = optionalString(input, "selector")?.trim().orEmpty()
        val text = optionalString(input, "text")?.trim().orEmpty()
        val url = optionalString(input, "url")?.trim().orEmpty()

        if (ms > 0) {
            delay(ms.toLong())
            return ToolOutput.Json(value = buildJsonObject { put("ok", JsonPrimitive(true)); put("waited_ms", JsonPrimitive(ms)) })
        }

        if (selector.isEmpty() && text.isEmpty() && url.isEmpty()) {
            return error("invalid_wait", "web_wait requires one of: ms/selector/text/url")
        }

        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < timeoutMs) {
            val selectorOk =
                if (selector.isNotEmpty()) {
                    val selEsc = selector.replace("\\", "\\\\").replace("'", "\\'")
                    val script =
                        "(function(){ try { return !!document.querySelector('$selEsc'); } catch(e){ return false; } })()"
                    runtime.eval(script).trim() == "true"
                } else {
                    true
                }

            val textOk =
                if (text.isNotEmpty()) {
                    val textEsc = text.replace("\\", "\\\\").replace("'", "\\'")
                    val script =
                        "(function(){ try { var b=document.body; var t=b?(b.innerText||b.textContent||''):''; return t.indexOf('$textEsc')>=0; } catch(e){ return false; } })()"
                    runtime.eval(script).trim() == "true"
                } else {
                    true
                }

            val urlOk =
                if (url.isNotEmpty()) {
                    val urlEsc = url.replace("\\", "\\\\").replace("'", "\\'")
                    val script =
                        "(function(){ try { return (location && location.href ? String(location.href) : '').indexOf('$urlEsc')>=0; } catch(e){ return false; } })()"
                    runtime.eval(script).trim() == "true"
                } else {
                    true
                }

            if (selectorOk && textOk && urlOk) {
                val waited = (System.currentTimeMillis() - started).toInt().coerceAtLeast(0)
                return ToolOutput.Json(value = buildJsonObject { put("ok", JsonPrimitive(true)); put("waited_ms", JsonPrimitive(waited)) })
            }
            delay(pollMs.toLong())
        }

        return error("timeout", "web_wait timed out")
    }
}

private class WebHoverTool(runtime: WebToolRuntime) :
    BaseWebTool(runtime = runtime, spec = WebToolsOpenAiSchema.WEB_HOVER) {
    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
        mustBeBound()?.let { return it }
        if (!runtime.ensureInjected()) return error("not_injected", "agent-browser.js not injected")
        val ref = requiredString(input, "ref") ?: return error("missing_ref", "web_hover requires ref")
        val raw = runtime.eval(AgentBrowser.actionJs(ref, ActionKind.HOVER))
        return ToolOutput.Json(value = runtime.parseJsonElementFromJsEval(raw))
    }
}

private class WebScrollIntoViewTool(runtime: WebToolRuntime) :
    BaseWebTool(runtime = runtime, spec = WebToolsOpenAiSchema.WEB_SCROLL_INTO_VIEW) {
    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
        mustBeBound()?.let { return it }
        if (!runtime.ensureInjected()) return error("not_injected", "agent-browser.js not injected")
        val ref = requiredString(input, "ref") ?: return error("missing_ref", "web_scroll_into_view requires ref")
        val raw = runtime.eval(AgentBrowser.actionJs(ref, ActionKind.SCROLL_INTO_VIEW))
        return ToolOutput.Json(value = runtime.parseJsonElementFromJsEval(raw))
    }
}

private class WebQueryTool(runtime: WebToolRuntime) :
    BaseWebTool(runtime = runtime, spec = WebToolsOpenAiSchema.WEB_QUERY) {
    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
        mustBeBound()?.let { return it }
        if (!runtime.ensureInjected()) return error("not_injected", "agent-browser.js not injected")
        val ref = requiredString(input, "ref") ?: return error("missing_ref", "web_query requires ref")
        val kind = requiredString(input, "kind") ?: return error("missing_kind", "web_query requires kind")
        val maxLength = (optionalInt(input, "max_length") ?: 4000).coerceAtLeast(64)
        val queryKind =
            when (kind.trim()) {
                "text" -> QueryKind.TEXT
                "html" -> QueryKind.HTML
                "outerHTML" -> QueryKind.OUTER_HTML
                "value" -> QueryKind.VALUE
                "attrs" -> QueryKind.ATTRS
                "computed_styles" -> QueryKind.COMPUTED_STYLES
                "isvisible" -> QueryKind.IS_VISIBLE
                "isenabled" -> QueryKind.IS_ENABLED
                "ischecked" -> QueryKind.IS_CHECKED
                else -> null
            } ?: return error("invalid_kind", "web_query kind must be one of: text/html/outerHTML/value/attrs/computed_styles/isvisible/isenabled/ischecked")
        val raw = runtime.eval(AgentBrowser.queryJs(ref, queryKind, QueryPayload(limitChars = maxLength)))
        return ToolOutput.Json(value = runtime.parseJsonElementFromJsEval(raw))
    }
}

private class WebScreenshotTool(runtime: WebToolRuntime) :
    BaseWebTool(runtime = runtime, spec = WebToolsOpenAiSchema.WEB_SCREENSHOT) {
    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
        mustBeBound()?.let { return it }
        val label = optionalString(input, "label")?.trim()
        val shot = runtime.captureScreenshot(label) ?: return error("screenshot_failed", "cannot capture screenshot")
        return ToolOutput.Json(
            value =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("path", JsonPrimitive(shot.path))
                    put("width", JsonPrimitive(shot.width))
                    put("height", JsonPrimitive(shot.height))
                },
        )
    }
}

private class WebEvalTool(runtime: WebToolRuntime) :
    BaseWebTool(runtime = runtime, spec = WebToolsOpenAiSchema.WEB_EVAL) {
    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
        mustBeBound()?.let { return it }
        if (!runtime.isEvalEnabled()) return error("disabled", "web_eval is disabled")
        if (!runtime.ensureInjected()) return error("not_injected", "agent-browser.js not injected")
        val js = requiredString(input, "js") ?: return error("missing_js", "web_eval requires js")
        val maxLen = (optionalInt(input, "max_length") ?: 8000).coerceAtLeast(64)
        val script = "(function(){ try { return ($js); } catch(e){ return 'ERROR: ' + String(e); } })()"
        val raw = runtime.eval(script)
        val normalized = AgentBrowser.normalizeJsEvalResult(raw)
        val truncated = normalized.length > maxLen
        val value = if (truncated) normalized.take(maxLen) else normalized

        val valueJson =
            if (!truncated) {
                val t = value.trim()
                if (t.startsWith("{") || t.startsWith("[")) {
                    runCatching { runtime.parseJsonElementFromJsEval(raw) }.getOrNull()
                } else {
                    null
                }
            } else {
                null
            }
        return ToolOutput.Json(
            value =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("value", JsonPrimitive(value))
                    put("truncated", JsonPrimitive(truncated))
                    if (valueJson != null) put("value_json", valueJson)
                },
        )
    }
}

private class WebCloseTool(runtime: WebToolRuntime) :
    BaseWebTool(runtime = runtime, spec = WebToolsOpenAiSchema.WEB_CLOSE) {
    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
        mustBeBound()?.let { return it }
        runtime.goto("about:blank")
        runtime.waitForIdle(timeoutMs = 8_000)
        return ToolOutput.Json(value = buildJsonObject { put("ok", JsonPrimitive(true)) })
    }
}

private fun JsonElement.asString(): String? =
    (this as? JsonPrimitive)?.content

private fun JsonElement.asBoolean(): Boolean? =
    (this as? JsonPrimitive)?.booleanOrNull

private fun JsonElement.asInt(): Int? =
    (this as? JsonPrimitive)?.intOrNull
