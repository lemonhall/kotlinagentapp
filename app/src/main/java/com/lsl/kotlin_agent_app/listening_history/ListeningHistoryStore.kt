package com.lsl.kotlin_agent_app.listening_history

import android.content.Context
import com.lsl.kotlin_agent_app.config.AppPrefsKeys
import java.io.File
import java.time.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal object ListeningHistoryPaths {
    const val EVENTS_AGENTS_PATH: String = ".agents/artifacts/listening_history/events.jsonl"
}

internal class ListeningHistoryStore(
    context: Context,
) {
    private val ctx = context.applicationContext

    fun isEnabled(): Boolean {
        val prefs = ctx.getSharedPreferences("kotlin-agent-app", Context.MODE_PRIVATE)
        return prefs.getBoolean(AppPrefsKeys.LISTENING_HISTORY_ENABLED, false)
    }

    fun setEnabled(enabled: Boolean) {
        val prefs = ctx.getSharedPreferences("kotlin-agent-app", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(AppPrefsKeys.LISTENING_HISTORY_ENABLED, enabled).apply()
    }

    fun eventsFile(): File {
        return File(ctx.filesDir, ListeningHistoryPaths.EVENTS_AGENTS_PATH)
    }

    fun appendEvent(
        source: String,
        action: String,
        item: JsonObject,
        userInitiated: Boolean,
        errorCode: String? = null,
        errorMessage: String? = null,
    ) {
        if (!isEnabled()) return

        val ev =
            buildJsonObject {
                put("schema", JsonPrimitive("kotlin-agent-app/listening-event@v1"))
                put("ts", JsonPrimitive(Instant.now().toString()))
                put("source", JsonPrimitive(source))
                put("action", JsonPrimitive(action))
                put("item", item)
                put("userInitiated", JsonPrimitive(userInitiated))
                if (!errorCode.isNullOrBlank() || !errorMessage.isNullOrBlank()) {
                    put(
                        "error",
                        buildJsonObject {
                            put("code", errorCode?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("message", errorMessage?.let { JsonPrimitive(it) } ?: JsonNull)
                        },
                    )
                } else {
                    put("error", JsonNull)
                }
            }

        val f = eventsFile()
        val dir = f.parentFile
        if (dir != null && !dir.exists()) dir.mkdirs()
        f.appendText(ev.toString() + "\n", Charsets.UTF_8)
    }

    /**
     * Clear local listening history. This is a destructive action; callers should perform a user-visible
     * confirmation step before invoking it.
     */
    fun clear(confirm: Boolean): Boolean {
        if (!confirm) return false
        val f = eventsFile()
        if (!f.exists()) return true
        return runCatching {
            f.delete() || runCatching { f.writeText("", Charsets.UTF_8); true }.getOrDefault(false)
        }.getOrDefault(false)
    }
}
