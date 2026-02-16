package com.lsl.kotlin_agent_app.config

import android.content.SharedPreferences
import com.lsl.kotlin_agent_app.BuildConfig

class SharedPreferencesProxyConfigRepository(
    private val prefs: SharedPreferences,
) {
    fun get(): ProxyConfig {
        val stored =
            ProxyConfig(
                enabled = if (prefs.contains(AppPrefsKeys.PROXY_ENABLED)) prefs.getBoolean(AppPrefsKeys.PROXY_ENABLED, false) else false,
                httpProxy = prefs.getString(AppPrefsKeys.HTTP_PROXY, "") ?: "",
                httpsProxy = prefs.getString(AppPrefsKeys.HTTPS_PROXY, "") ?: "",
            )

        if (BuildConfig.DEBUG) {
            val seeded =
                stored.copy(
                    enabled = if (!prefs.contains(AppPrefsKeys.PROXY_ENABLED)) BuildConfig.DEFAULT_PROXY_ENABLED else stored.enabled,
                    httpProxy = stored.httpProxy.ifBlank { BuildConfig.DEFAULT_HTTP_PROXY },
                    httpsProxy = stored.httpsProxy.ifBlank { BuildConfig.DEFAULT_HTTPS_PROXY },
                )
            if (seeded != stored) {
                set(seeded)
                return seeded
            }
        }

        return stored
    }

    fun set(config: ProxyConfig) {
        prefs.edit()
            .putBoolean(AppPrefsKeys.PROXY_ENABLED, config.enabled)
            .putString(AppPrefsKeys.HTTP_PROXY, config.httpProxy)
            .putString(AppPrefsKeys.HTTPS_PROXY, config.httpsProxy)
            .apply()
    }
}

