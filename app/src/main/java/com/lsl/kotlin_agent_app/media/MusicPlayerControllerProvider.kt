package com.lsl.kotlin_agent_app.media

import android.content.Context
import androidx.annotation.VisibleForTesting

object MusicPlayerControllerProvider {
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var instance: MusicPlayerController? = null

    @VisibleForTesting
    internal var factoryOverride: ((Context) -> MusicPlayerController)? = null

    fun installAppContext(context: Context) {
        appContext = context.applicationContext
    }

    fun get(): MusicPlayerController {
        val ctx = appContext ?: error("MusicPlayerControllerProvider.installAppContext() must be called first")
        return instance ?: synchronized(this) {
            instance ?: run {
                val created =
                    (factoryOverride ?: { c -> MusicPlayerController(c, transport = Media3MusicTransport(c)) })(ctx)
                instance = created
                created
            }
        }
    }

    @VisibleForTesting
    fun resetForTests() {
        instance?.close()
        instance = null
        factoryOverride = null
        appContext = null
    }
}
