package com.lsl.kotlin_agent_app.recordings

internal object RecordingRoots {
    const val WORKSPACE_ROOT = ".agents/workspace"

    const val RADIO_DIR_NAME = "radio_recordings"
    const val MICROPHONE_DIR_NAME = "recordings"

    const val RADIO_ROOT_DIR = "$WORKSPACE_ROOT/$RADIO_DIR_NAME"
    const val MICROPHONE_ROOT_DIR = "$WORKSPACE_ROOT/$MICROPHONE_DIR_NAME"

    fun allRootDirsInLookupOrder(): List<String> {
        // Prefer legacy radio_recordings for backwards-compat if IDs ever collide.
        return listOf(RADIO_ROOT_DIR, MICROPHONE_ROOT_DIR)
    }
}

