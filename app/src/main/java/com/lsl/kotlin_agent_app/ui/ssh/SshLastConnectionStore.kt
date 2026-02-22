package com.lsl.kotlin_agent_app.ui.ssh

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
internal data class SshLastConnection(
    val host: String,
    val port: Int,
    val user: String,
)

internal class SshLastConnectionStore(
    private val file: File,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true }

    fun readOrNull(): SshLastConnection? {
        if (!file.exists() || !file.isFile) return null
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull().orEmpty().trim()
        if (raw.isBlank()) return null
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val host = obj["host"]?.jsonPrimitive?.content?.trim().orEmpty()
        val port = obj["port"]?.jsonPrimitive?.content?.trim()?.toIntOrNull() ?: 0
        val user = obj["user"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (host.isBlank() || user.isBlank() || port !in 1..65535) return null
        return SshLastConnection(host = host, port = port, user = user)
    }

    fun write(conn: SshLastConnection) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val obj =
            buildJsonObject {
                put("schema", JsonPrimitive("kotlin-agent-app/ssh-last-connection@v1"))
                put("host", JsonPrimitive(conn.host))
                put("port", JsonPrimitive(conn.port))
                put("user", JsonPrimitive(conn.user))
                put("updatedAtSec", JsonPrimitive((System.currentTimeMillis() / 1000L).coerceAtLeast(0L)))
            }
        file.writeText(json.encodeToString(JsonObject.serializer(), obj) + "\n", Charsets.UTF_8)
    }
}

