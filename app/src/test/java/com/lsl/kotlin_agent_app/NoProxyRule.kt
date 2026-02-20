package com.lsl.kotlin_agent_app

import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Some developer environments set JVM/system proxy properties (or a default ProxySelector),
 * which can break MockWebServer tests by proxying localhost traffic.
 *
 * This rule forces direct connections for the duration of the test.
 */
class NoProxyRule : TestWatcher() {
    private val keys =
        listOf(
            "http.proxyHost",
            "http.proxyPort",
            "https.proxyHost",
            "https.proxyPort",
            "socksProxyHost",
            "socksProxyPort",
            "java.net.useSystemProxies",
        )

    private val oldProps = linkedMapOf<String, String?>()
    private var oldSelector: ProxySelector? = null

    override fun starting(description: Description) {
        for (k in keys) {
            oldProps[k] = System.getProperty(k)
            System.clearProperty(k)
        }

        oldSelector = ProxySelector.getDefault()
        ProxySelector.setDefault(
            object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> = listOf(Proxy.NO_PROXY)

                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {}
            },
        )
    }

    override fun finished(description: Description) {
        runCatching {
            val prev = oldSelector
            if (prev != null) ProxySelector.setDefault(prev)
        }
        oldSelector = null

        for ((k, v) in oldProps) {
            if (v == null) System.clearProperty(k) else System.setProperty(k, v)
        }
        oldProps.clear()
    }
}

