package com.lsl.kotlin_agent_app.agent.tools.irc

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object IrcCursorStore {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    fun loadCursors(
        agentsRoot: File,
        sessionKey: String,
    ): MutableMap<String, String> {
        val f = cursorFile(agentsRoot, sessionKey)
        if (!f.exists() || !f.isFile) return linkedMapOf()
        return try {
            val el = json.parseToJsonElement(f.readText(Charsets.UTF_8))
            val obj = el.jsonObject
            obj.entries.associateTo(linkedMapOf()) { (k, v) ->
                k to runCatching { v.jsonPrimitive.content }.getOrNull().orEmpty()
            }
        } catch (_: Throwable) {
            linkedMapOf()
        }
    }

    fun saveCursors(
        agentsRoot: File,
        sessionKey: String,
        cursors: Map<String, String>,
    ) {
        val f = cursorFile(agentsRoot, sessionKey)
        val parent = f.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val obj =
            JsonObject(
                cursors.entries.associate { (k, v) ->
                    k to JsonPrimitive(v)
                },
            )
        f.writeText(obj.toString() + "\n", Charsets.UTF_8)
    }

    private fun cursorFile(
        agentsRoot: File,
        sessionKey: String,
    ): File {
        val safe = sessionKey.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(agentsRoot, "workspace/irc/sessions/$safe/cursors.json")
    }
}
