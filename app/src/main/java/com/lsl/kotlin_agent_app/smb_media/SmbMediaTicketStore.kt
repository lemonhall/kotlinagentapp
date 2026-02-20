package com.lsl.kotlin_agent_app.smb_media

import java.util.UUID

data class SmbMediaTicketSpec(
    val mountName: String,
    val remotePath: String,
    val mime: String,
    val sizeBytes: Long,
)

data class SmbMediaTicket(
    val token: String,
    val spec: SmbMediaTicketSpec,
    val createdAtMs: Long,
    val lastAccessAtMs: Long,
    val boundUid: Int? = null,
) {
    override fun toString(): String {
        val t = token.take(8)
        return "SmbMediaTicket(token=$t...,mime=${spec.mime},sizeBytes=${spec.sizeBytes})"
    }
}

class SmbMediaTicketStore(
    private val clock: SmbClock = SystemSmbClock,
    private val idleTtlMs: Long = 30L * 60L * 1000L,
) {
    private val tickets = linkedMapOf<String, SmbMediaTicket>()

    fun issue(spec: SmbMediaTicketSpec): SmbMediaTicket {
        val now = clock.nowMs().coerceAtLeast(0L)
        val token = UUID.randomUUID().toString().replace("-", "")
        val ticket =
            SmbMediaTicket(
                token = token,
                spec = spec,
                createdAtMs = now,
                lastAccessAtMs = now,
                boundUid = null,
            )
        synchronized(this) {
            tickets[token] = ticket
        }
        return ticket
    }

    fun resolve(
        token: String,
        callingUid: Int,
    ): SmbMediaTicket {
        val t = token.trim()
        require(t.isNotEmpty()) { "token is empty" }
        val now = clock.nowMs().coerceAtLeast(0L)

        synchronized(this) {
            val existing = tickets[t] ?: throw IllegalStateException("expired")

            val idle = (now - existing.lastAccessAtMs).coerceAtLeast(0L)
            if (idle >= idleTtlMs) {
                tickets.remove(t)
                throw IllegalStateException("expired")
            }

            val bound =
                existing.boundUid?.let { uid ->
                    if (uid != callingUid) throw IllegalStateException("uid mismatch")
                    uid
                } ?: callingUid

            val updated =
                existing.copy(
                    boundUid = bound,
                    lastAccessAtMs = now,
                )
            tickets[t] = updated
            return updated
        }
    }
}

