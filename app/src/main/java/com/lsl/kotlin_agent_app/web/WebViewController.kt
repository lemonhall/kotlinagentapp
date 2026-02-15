package com.lsl.kotlin_agent_app.web

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.coroutines.resume

data class WebViewState(
    val url: String? = null,
    val title: String? = null,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val loading: Boolean = false,
    val progress: Int = 0,
)

class WebViewController {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(WebViewState())
    val state: StateFlow<WebViewState> = _state.asStateFlow()

    @Volatile
    private var boundWebView: WebView? = null

    @Volatile
    private var ui: UiBindings? = null

    fun isBound(): Boolean = boundWebView != null

    fun bind(
        webView: WebView,
        urlTextView: TextView,
        backButton: ImageButton,
        forwardButton: ImageButton,
        reloadButton: ImageButton,
    ) {
        if (boundWebView === webView) return
        boundWebView = webView
        ui = UiBindings(urlTextView, backButton, forwardButton, reloadButton)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadsImagesAutomatically = true
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true

        webView.webViewClient =
            object : WebViewClient() {
                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?,
                ) {
                    updateState(loading = true)
                }

                override fun onPageFinished(
                    view: WebView?,
                    url: String?,
                ) {
                    updateState(loading = false)
                }
            }

        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onProgressChanged(
                    view: WebView?,
                    newProgress: Int,
                ) {
                    updateState(progress = newProgress, loading = newProgress in 0..99)
                }

                override fun onReceivedTitle(
                    view: WebView?,
                    title: String?,
                ) {
                    updateState(title = title)
                }
            }

        _state.value = currentSnapshot()
        applyUiState(_state.value)

        backButton.setOnClickListener {
            val v = boundWebView ?: return@setOnClickListener
            if (v.canGoBack()) v.goBack()
            updateState()
        }
        forwardButton.setOnClickListener {
            val v = boundWebView ?: return@setOnClickListener
            if (v.canGoForward()) v.goForward()
            updateState()
        }
        reloadButton.setOnClickListener {
            val v = boundWebView ?: return@setOnClickListener
            v.reload()
            updateState(loading = true)
        }

        updateState()
    }

    suspend fun goto(url: String): WebViewState =
        withContext(Dispatchers.Main.immediate) {
            val view = requireWebView()
            view.loadUrl(normalizeUrl(url))
            updateState(loading = true)
            _state.value
        }

    suspend fun back(): WebViewState =
        withContext(Dispatchers.Main.immediate) {
            val view = requireWebView()
            if (view.canGoBack()) view.goBack()
            updateState()
            _state.value
        }

    suspend fun forward(): WebViewState =
        withContext(Dispatchers.Main.immediate) {
            val view = requireWebView()
            if (view.canGoForward()) view.goForward()
            updateState()
            _state.value
        }

    suspend fun reload(): WebViewState =
        withContext(Dispatchers.Main.immediate) {
            val view = requireWebView()
            view.reload()
            updateState(loading = true)
            _state.value
        }

    suspend fun getState(): WebViewState =
        withContext(Dispatchers.Main.immediate) {
            updateState()
            _state.value
        }

    suspend fun runScript(script: String): String =
        withContext(Dispatchers.Main.immediate) {
            val view = requireWebView()
            suspendCancellableCoroutine { cont ->
                view.evaluateJavascript(script) { value ->
                    cont.resume(value ?: "null")
                }
            }
        }

    suspend fun getDom(
        selector: String?,
        mode: String,
    ): String {
        val sel = selector?.trim().takeIf { !it.isNullOrEmpty() }
        val m = mode.trim().ifEmpty { "outerHTML" }
        val js =
            if (sel == null) {
                """
                (() => {
                  const el = document.documentElement;
                  if (!el) return null;
                  if ("$m" === "text") return el.innerText;
                  return el.outerHTML;
                })()
                """.trimIndent()
            } else {
                val selJson = json.encodeToString(sel)
                """
                (() => {
                  const el = document.querySelector($selJson);
                  if (!el) return null;
                  if ("$m" === "text") return el.innerText;
                  return el.outerHTML;
                })()
                """.trimIndent()
            }

        return runScript(js)
    }

    suspend fun capturePreviewBitmap(
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap? =
        withContext(Dispatchers.Main.immediate) {
            val view = boundWebView ?: return@withContext null
            val w = view.width
            val h = view.height
            if (w <= 0 || h <= 0) return@withContext null

            val bw = targetWidth.coerceAtLeast(1)
            val bh = targetHeight.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(bw.toFloat() / w.toFloat(), bh.toFloat() / h.toFloat())
            view.draw(canvas)
            bitmap
        }

    private fun requireWebView(): WebView {
        return boundWebView ?: error("WebView not bound. Open the Web tab once to initialize.")
    }

    private fun currentSnapshot(): WebViewState {
        val view = boundWebView
        return WebViewState(
            url = view?.url,
            title = view?.title,
            canGoBack = view?.canGoBack() == true,
            canGoForward = view?.canGoForward() == true,
            loading = false,
            progress = 0,
        )
    }

    private fun updateState(
        loading: Boolean? = null,
        title: String? = null,
        progress: Int? = null,
    ) {
        val view = boundWebView
        val prev = _state.value
        _state.value =
            prev.copy(
                url = view?.url ?: prev.url,
                title = title ?: view?.title ?: prev.title,
                canGoBack = view?.canGoBack() == true,
                canGoForward = view?.canGoForward() == true,
                loading = loading ?: prev.loading,
                progress = progress ?: prev.progress,
            )
        applyUiState(_state.value)
    }

    private fun normalizeUrl(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return "about:blank"
        val uri = runCatching { Uri.parse(t) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        if (scheme == null) return "https://$t"
        if (scheme == "http" || scheme == "https" || scheme == "about") return t
        return t
    }

    private fun applyUiState(state: WebViewState) {
        val b = ui ?: return
        b.backButton.isEnabled = state.canGoBack
        b.forwardButton.isEnabled = state.canGoForward
        val currentUrl = state.url?.takeIf { it.isNotBlank() } ?: "about:blank"
        b.urlTextView.text = currentUrl
    }

    private data class UiBindings(
        val urlTextView: TextView,
        val backButton: ImageButton,
        val forwardButton: ImageButton,
        val reloadButton: ImageButton,
    )
}

object WebViewControllerProvider {
    val instance: WebViewController = WebViewController()
}
