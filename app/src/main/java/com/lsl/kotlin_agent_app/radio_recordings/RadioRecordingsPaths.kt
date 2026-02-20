package com.lsl.kotlin_agent_app.radio_recordings

internal object RadioRecordingsPaths {
    const val ROOT_DIR = ".agents/workspace/radio_recordings"
    const val ROOT_STATUS_MD = "$ROOT_DIR/_STATUS.md"
    const val ROOT_INDEX_JSON = "$ROOT_DIR/.recordings.index.json"

    fun sessionDir(sessionId: String): String = "$ROOT_DIR/$sessionId"

    fun sessionMetaJson(sessionId: String): String = "${sessionDir(sessionId)}/_meta.json"

    fun sessionStatusMd(sessionId: String): String = "${sessionDir(sessionId)}/_STATUS.md"

    fun chunkFile(sessionId: String, chunkIndex: Int): String {
        val idx = chunkIndex.coerceAtLeast(1)
        return "${sessionDir(sessionId)}/chunk_${idx.toString().padStart(3, '0')}.ogg"
    }
}

