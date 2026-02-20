package com.lsl.kotlin_agent_app.recordings

import com.lsl.kotlin_agent_app.agent.AgentsWorkspace

internal object RecordingSessionResolver {
    fun resolve(
        ws: AgentsWorkspace,
        sessionId: String,
    ): RecordingSessionRef? {
        val sid = sessionId.trim()
        if (sid.isBlank()) return null

        for (root in RecordingRoots.allRootDirsInLookupOrder()) {
            val ref = RecordingSessionRef(rootDir = root, sessionId = sid)
            if (ws.exists(ref.metaPath)) return ref
        }

        return null
    }
}

