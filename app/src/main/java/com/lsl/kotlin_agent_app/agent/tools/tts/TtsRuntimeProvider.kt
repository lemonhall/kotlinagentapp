package com.lsl.kotlin_agent_app.agent.tools.tts

import android.content.Context

internal object TtsRuntimeProvider {
    @Volatile private var override: TtsRuntime? = null
    @Volatile private var singleton: TtsRuntime? = null

    fun installForTests(runtime: TtsRuntime) {
        override = runtime
    }

    fun clearForTests() {
        override = null
        singleton = null
    }

    fun get(appContext: Context): TtsRuntime {
        override?.let { return it }
        singleton?.let { return it }
        synchronized(this) {
            override?.let { return it }
            val existing = singleton
            if (existing != null) return existing
            val created = AndroidTtsRuntime(appContext.applicationContext)
            singleton = created
            return created
        }
    }
}

