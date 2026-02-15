package com.lsl.kotlin_agent_app.ui.markdown

import android.content.Context
import io.noties.markwon.Markwon
import io.noties.markwon.linkify.LinkifyPlugin

object MarkwonProvider {
    @Volatile
    private var cached: Markwon? = null

    fun get(context: Context): Markwon {
        val existing = cached
        if (existing != null) return existing

        synchronized(this) {
            val existing2 = cached
            if (existing2 != null) return existing2
            val appContext = context.applicationContext
            val created =
                Markwon
                    .builder(appContext)
                    .usePlugin(LinkifyPlugin.create())
                    .build()
            cached = created
            return created
        }
    }
}

