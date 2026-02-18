package com.lsl.kotlin_agent_app.agent.tools.ssh

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

internal class SshKnownHostsStore(
    private val file: File,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    private val hosts: LinkedHashMap<String, String> = linkedMapOf()

    fun load() {
        hosts.clear()
        if (!file.exists() || !file.isFile) return
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull().orEmpty().trim()
        if (text.isBlank()) return
        val obj =
            runCatching { json.parseToJsonElement(text).jsonObject }
                .getOrNull()
                ?: return
        val hs = obj["hosts"] as? JsonObject ?: return
        for ((k, v) in hs) {
            val fp = (v as? JsonPrimitive)?.content?.trim().orEmpty()
            if (k.isNotBlank() && fp.isNotBlank()) hosts[k.trim()] = fp
        }
    }

    fun getFingerprint(
        host: String,
        port: Int,
    ): String? {
        val key = normalizeKey(host, port)
        return hosts[key]
    }

    fun putFingerprint(
        host: String,
        port: Int,
        fingerprint: String,
    ) {
        val key = normalizeKey(host, port)
        hosts[key] = fingerprint.trim()
    }

    fun write() {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val el =
            buildJsonObject {
                put("version", JsonPrimitive(1))
                put(
                    "hosts",
                    buildJsonObject {
                        for ((k, v) in hosts) put(k, JsonPrimitive(v))
                    },
                )
            }
        file.writeText(el.toString() + "\n", Charsets.UTF_8)
    }

    private fun normalizeKey(
        host: String,
        port: Int,
    ): String {
        return host.trim().lowercase() + ":" + port.toString()
    }
}

