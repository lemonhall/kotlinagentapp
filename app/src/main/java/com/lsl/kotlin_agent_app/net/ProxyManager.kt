package com.lsl.kotlin_agent_app.net

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.lsl.kotlin_agent_app.config.ProxyConfig as AppProxyConfig
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

object ProxyManager {
    @Volatile
    private var originalProxySelector: ProxySelector? = null

    fun apply(
        context: Context,
        config: AppProxyConfig,
    ) {
        val appContext = context.applicationContext

        val parsedHttp = parseProxy(config.httpProxy)
        val parsedHttps = parseProxy(config.httpsProxy)
        val effective =
            if (config.enabled && (parsedHttp != null || parsedHttps != null)) {
                AppProxyConfig(
                    enabled = true,
                    httpProxy = config.httpProxy.trim(),
                    httpsProxy = config.httpsProxy.trim(),
                )
            } else {
                AppProxyConfig(enabled = false)
            }

        applyJvmProxySelector(effective, parsedHttp, parsedHttps)
        applyWebViewProxy(appContext, effective)
    }

    private fun applyJvmProxySelector(
        config: AppProxyConfig,
        http: InetSocketAddress?,
        https: InetSocketAddress?,
    ) {
        if (originalProxySelector == null) {
            originalProxySelector = ProxySelector.getDefault()
        }

        if (!config.enabled) {
            ProxySelector.setDefault(originalProxySelector)
            clearSystemProxyProps()
            return
        }

        val httpProxy = http
        val httpsProxy = https ?: http
        if (httpProxy == null && httpsProxy == null) return

        setSystemProxyProps(http = httpProxy, https = httpsProxy)

        ProxySelector.setDefault(
            object : ProxySelector() {
                override fun select(uri: URI?): MutableList<Proxy> {
                    val scheme = uri?.scheme?.lowercase().orEmpty()
                    val addr =
                        when (scheme) {
                            "https" -> httpsProxy
                            "http" -> httpProxy
                            else -> null
                        } ?: httpProxy ?: httpsProxy
                    return if (addr != null) mutableListOf(Proxy(Proxy.Type.HTTP, addr)) else mutableListOf(Proxy.NO_PROXY)
                }

                override fun connectFailed(
                    uri: URI?,
                    sa: java.net.SocketAddress?,
                    ioe: java.io.IOException?,
                ) {
                }
            },
        )
    }

    private fun applyWebViewProxy(
        context: Context,
        config: AppProxyConfig,
    ) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return

        val executor = ContextCompat.getMainExecutor(context)
        val controller = ProxyController.getInstance()

        if (!config.enabled) {
            controller.clearProxyOverride(executor) {}
            return
        }

        val rules =
            buildList {
                val http = config.httpProxy.trim()
                val https = config.httpsProxy.trim()
                if (http.isNotBlank()) add(normalizeProxyRule(http))
                if (https.isNotBlank() && https != http) add(normalizeProxyRule(https))
            }
        if (rules.isEmpty()) {
            controller.clearProxyOverride(executor) {}
            return
        }

        val builder = ProxyConfig.Builder()
        for (r in rules) builder.addProxyRule(r)
        controller.setProxyOverride(builder.build(), executor) {}
    }

    private fun parseProxy(raw: String?): InetSocketAddress? {
        val s = raw?.trim().orEmpty()
        if (s.isBlank()) return null

        val normalized =
            if (s.contains("://")) s else "http://$s"
        val uri =
            runCatching { URI(normalized) }.getOrNull()
                ?: return null
        val host = uri.host?.trim().orEmpty()
        val port = uri.port
        if (host.isBlank() || port <= 0) return null
        return InetSocketAddress(host, port)
    }

    private fun normalizeProxyRule(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return s
        return if (s.contains("://")) s else "http://$s"
    }

    private fun setSystemProxyProps(
        http: InetSocketAddress?,
        https: InetSocketAddress?,
    ) {
        if (http != null) {
            System.setProperty("http.proxyHost", http.hostString)
            System.setProperty("http.proxyPort", http.port.toString())
        }
        if (https != null) {
            System.setProperty("https.proxyHost", https.hostString)
            System.setProperty("https.proxyPort", https.port.toString())
        }
    }

    private fun clearSystemProxyProps() {
        System.clearProperty("http.proxyHost")
        System.clearProperty("http.proxyPort")
        System.clearProperty("https.proxyHost")
        System.clearProperty("https.proxyPort")
    }
}
