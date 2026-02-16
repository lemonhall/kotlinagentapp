package com.lsl.kotlin_agent_app.web

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.EditText
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val _state = MutableStateFlow(WebViewState())
    val state: StateFlow<WebViewState> = _state.asStateFlow()

    @Volatile
    private var boundWebView: WebView? = null

    @Volatile
    private var ui: UiBindings? = null

    fun isBound(): Boolean = boundWebView != null

    fun bind(
        webView: WebView,
        urlEditText: EditText,
        goButton: ImageButton,
        backButton: ImageButton,
        forwardButton: ImageButton,
        reloadButton: ImageButton,
    ) {
        if (boundWebView === webView) return
        boundWebView = webView
        ui = UiBindings(urlEditText, goButton, backButton, forwardButton, reloadButton)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadsImagesAutomatically = true
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true

        webView.webViewClient =
            object : WebViewClient() {
                private fun isAllowedScheme(url: String): Boolean {
                    val u = url.trim()
                    if (u.isEmpty()) return false
                    val lower = u.lowercase()
                    return lower.startsWith("http://") ||
                        lower.startsWith("https://") ||
                        lower.startsWith("file://") ||
                        lower.startsWith("about:")
                }

                private fun shouldBlockUrl(url: String): Boolean {
                    val lower = url.trim().lowercase()
                    if (lower.startsWith("baiduboxapp://")) return true
                    if (lower.startsWith("intent://")) return true
                    if (!isAllowedScheme(lower)) return true
                    return false
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val url = request?.url?.toString().orEmpty()
                    if (url.isBlank()) return false
                    return shouldBlockUrl(url)
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    val u = url.orEmpty()
                    if (u.isBlank()) return false
                    return shouldBlockUrl(u)
                }

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

        fun go() {
            val input = ui?.urlEditText?.text?.toString().orEmpty().trim()
            if (input.isBlank()) return
            webView.loadUrl(normalizeUrl(input))
            updateState(loading = true)
            hideIme(urlEditText)
            urlEditText.clearFocus()
        }

        goButton.setOnClickListener { go() }
        urlEditText.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (isGo || isEnter) {
                go()
                true
            } else {
                false
            }
        }

        webView.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) {
                hideIme(urlEditText)
                urlEditText.clearFocus()
            }
            false
        }

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
            canvas.drawColor(Color.WHITE)
            val sx = bw.toFloat() / w.toFloat()
            canvas.scale(sx, sx)
            view.draw(canvas)
            bitmap
        }

    suspend fun clearRuntimeData() {
        withContext(Dispatchers.Main.immediate) {
            val view = boundWebView ?: return@withContext
            view.clearHistory()
            view.clearFormData()
            view.clearMatches()
            view.clearCache(true)
        }
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

    private fun hideIme(view: android.view.View) {
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun applyUiState(state: WebViewState) {
        val b = ui ?: return
        b.backButton.isEnabled = state.canGoBack
        b.forwardButton.isEnabled = state.canGoForward
        val currentUrl = state.url?.takeIf { it.isNotBlank() } ?: "about:blank"
        if (!b.urlEditText.hasFocus()) {
            b.urlEditText.setText(currentUrl)
            b.urlEditText.setSelection(b.urlEditText.text?.length ?: 0)
        }
    }

    private data class UiBindings(
        val urlEditText: EditText,
        val goButton: ImageButton,
        val backButton: ImageButton,
        val forwardButton: ImageButton,
        val reloadButton: ImageButton,
    )
}

object WebViewControllerProvider {
    val instance: WebViewController = WebViewController()
}
