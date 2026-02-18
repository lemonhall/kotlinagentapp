package com.lsl.kotlin_agent_app.agent.tools.rss

import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.resolveWithinAgents
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal data class RssSubscription(
    val name: String,
    val url: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

internal data class RssFetchState(
    val name: String,
    val url: String,
    val etag: String? = null,
    val lastModified: String? = null,
    val lastFetchMs: Long? = null,
    val lastStatus: Int? = null,
)

internal class RssStore(
    agentsRoot: File,
) {
    private val agentsRootCanonical: File = agentsRoot.canonicalFile

    private fun rssWorkspaceDir(): File = resolveWithinAgents(agentsRootCanonical, "workspace/rss")

    private fun subscriptionsFile(): File = resolveWithinAgents(agentsRootCanonical, "workspace/rss/subscriptions.json")

    private fun fetchStateFile(): File = resolveWithinAgents(agentsRootCanonical, "workspace/rss/fetch_state.json")

    fun upsertSubscription(
        name: String,
        url: String,
        nowMs: Long,
    ): RssSubscription {
        val normName = name.trim()
        val normUrl = url.trim()
        if (normName.isEmpty()) throw IllegalArgumentException("missing --name")
        if (normUrl.isEmpty()) throw IllegalArgumentException("missing --url")
        rssWorkspaceDir().mkdirs()

        val all = readSubscriptions()
        val prev = all.firstOrNull { it.name.equals(normName, ignoreCase = true) }
        val createdAt = prev?.createdAtMs ?: nowMs
        val updatedAt = nowMs
        val next = RssSubscription(name = normName, url = normUrl, createdAtMs = createdAt, updatedAtMs = updatedAt)

        val kept = all.filterNot { it.name.equals(normName, ignoreCase = true) }
        val merged = (kept + next).sortedByDescending { it.updatedAtMs }
        writeSubscriptions(merged)
        return next
    }

    fun listSubscriptionsSummary(max: Int): List<JsonObject> {
        val cap = max.coerceAtLeast(0)
        return readSubscriptions()
            .sortedByDescending { it.updatedAtMs }
            .take(cap)
            .map { sub ->
                buildJsonObject {
                    put("name", JsonPrimitive(sub.name))
                    put("url", JsonPrimitive(sub.url))
                    put("updated_at_ms", JsonPrimitive(sub.updatedAtMs))
                }
            }
    }

    fun readSubscriptionsFullJson(): JsonArray {
        val all = readSubscriptions().sortedByDescending { it.updatedAtMs }
        return buildJsonArray {
            for (s in all) {
                add(
                    buildJsonObject {
                        put("name", JsonPrimitive(s.name))
                        put("url", JsonPrimitive(s.url))
                        put("created_at_ms", JsonPrimitive(s.createdAtMs))
                        put("updated_at_ms", JsonPrimitive(s.updatedAtMs))
                    },
                )
            }
        }
    }

    fun removeSubscription(
        name: String,
    ): Boolean {
        val normName = name.trim()
        if (normName.isEmpty()) throw IllegalArgumentException("missing --name")
        val all = readSubscriptions()
        val kept = all.filterNot { it.name.equals(normName, ignoreCase = true) }
        if (kept.size == all.size) return false
        writeSubscriptions(kept.sortedByDescending { it.updatedAtMs })
        return true
    }

    fun getSubscriptionByName(name: String): RssSubscription? {
        val norm = name.trim()
        if (norm.isEmpty()) return null
        return readSubscriptions().firstOrNull { it.name.equals(norm, ignoreCase = true) }
    }

    fun getFetchStateByName(name: String): RssFetchState? {
        val norm = name.trim()
        if (norm.isEmpty()) return null
        return readFetchStates().firstOrNull { it.name.equals(norm, ignoreCase = true) }
    }

    fun upsertFetchState(next: RssFetchState) {
        rssWorkspaceDir().mkdirs()
        val all = readFetchStates()
        val kept = all.filterNot { it.name.equals(next.name, ignoreCase = true) }
        val merged = (kept + next).sortedBy { it.name.lowercase() }
        writeFetchStates(merged)
    }

    private fun readSubscriptions(): List<RssSubscription> {
        val f = subscriptionsFile()
        if (!f.exists() || !f.isFile) return emptyList()
        val el =
            try {
                Json.parseToJsonElement(f.readText(Charsets.UTF_8))
            } catch (_: Throwable) {
                return emptyList()
            }
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { parseSubscriptionOrNull(it) }
    }

    private fun writeSubscriptions(subs: List<RssSubscription>) {
        val f = subscriptionsFile()
        f.parentFile?.mkdirs()
        val arr =
            buildJsonArray {
                for (s in subs) {
                    add(
                        buildJsonObject {
                            put("name", JsonPrimitive(s.name))
                            put("url", JsonPrimitive(s.url))
                            put("created_at_ms", JsonPrimitive(s.createdAtMs))
                            put("updated_at_ms", JsonPrimitive(s.updatedAtMs))
                        },
                    )
                }
            }
        f.writeText(arr.toString() + "\n", Charsets.UTF_8)
    }

    private fun readFetchStates(): List<RssFetchState> {
        val f = fetchStateFile()
        if (!f.exists() || !f.isFile) return emptyList()
        val el =
            try {
                Json.parseToJsonElement(f.readText(Charsets.UTF_8))
            } catch (_: Throwable) {
                return emptyList()
            }
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { parseFetchStateOrNull(it) }
    }

    private fun writeFetchStates(states: List<RssFetchState>) {
        val f = fetchStateFile()
        f.parentFile?.mkdirs()
        val arr =
            buildJsonArray {
                for (s in states) {
                    add(
                        buildJsonObject {
                            put("name", JsonPrimitive(s.name))
                            put("url", JsonPrimitive(s.url))
                            if (!s.etag.isNullOrBlank()) put("etag", JsonPrimitive(s.etag))
                            if (!s.lastModified.isNullOrBlank()) put("last_modified", JsonPrimitive(s.lastModified))
                            if (s.lastFetchMs != null) put("last_fetch_ms", JsonPrimitive(s.lastFetchMs))
                            if (s.lastStatus != null) put("last_status", JsonPrimitive(s.lastStatus))
                        },
                    )
                }
            }
        f.writeText(arr.toString() + "\n", Charsets.UTF_8)
    }

    private fun parseSubscriptionOrNull(el: JsonElement): RssSubscription? {
        val obj = el as? JsonObject ?: return null
        val name = obj.stringOrNull("name")?.trim().orEmpty()
        val url = obj.stringOrNull("url")?.trim().orEmpty()
        if (name.isEmpty() || url.isEmpty()) return null
        val created = obj.longOrNull("created_at_ms") ?: 0L
        val updated = obj.longOrNull("updated_at_ms") ?: created
        return RssSubscription(name = name, url = url, createdAtMs = created, updatedAtMs = updated)
    }

    private fun parseFetchStateOrNull(el: JsonElement): RssFetchState? {
        val obj = el as? JsonObject ?: return null
        val name = obj.stringOrNull("name")?.trim().orEmpty()
        val url = obj.stringOrNull("url")?.trim().orEmpty()
        if (name.isEmpty() || url.isEmpty()) return null
        val etag = obj.stringOrNull("etag")?.trim()?.takeIf { it.isNotBlank() }
        val lastMod = obj.stringOrNull("last_modified")?.trim()?.takeIf { it.isNotBlank() }
        val lastFetch = obj.longOrNull("last_fetch_ms")
        val lastStatus = obj.intOrNull("last_status")
        return RssFetchState(name = name, url = url, etag = etag, lastModified = lastMod, lastFetchMs = lastFetch, lastStatus = lastStatus)
    }
}

private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNullSafe()

private fun JsonObject.longOrNull(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNullSafe()?.toLongOrNull()

private fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNullSafe()?.toIntOrNull()

private fun JsonPrimitive.contentOrNullSafe(): String? =
    try {
        this.content
    } catch (_: Throwable) {
        null
    }

