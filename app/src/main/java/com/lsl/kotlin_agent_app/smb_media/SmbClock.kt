package com.lsl.kotlin_agent_app.smb_media

interface SmbClock {
    fun nowMs(): Long
}

object SystemSmbClock : SmbClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

