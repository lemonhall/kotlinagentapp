package com.lsl.kotlin_agent_app.web

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object WebViewDataCleaner {
    suspend fun clearAll(context: Context) {
        withContext(Dispatchers.Main.immediate) {
            val cookieManager = CookieManager.getInstance()
            suspendCancellableCoroutine { cont ->
                cookieManager.removeAllCookies {
                    cont.resume(Unit)
                }
            }
            cookieManager.flush()

            WebStorage.getInstance().deleteAllData()
            WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
            WebViewDatabase.getInstance(context).clearFormData()
            WebViewDatabase.getInstance(context).clearUsernamePassword()

            runCatching { WebViewControllerProvider.instance.clearRuntimeData() }
        }
    }
}

