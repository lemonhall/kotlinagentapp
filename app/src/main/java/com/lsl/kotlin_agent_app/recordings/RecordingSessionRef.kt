package com.lsl.kotlin_agent_app.recordings

internal data class RecordingSessionRef(
    val rootDir: String,
    val sessionId: String,
) {
    val sessionDir: String = "${rootDir.trimEnd('/')}/${sessionId.trim()}"
    val metaPath: String = "$sessionDir/_meta.json"

    val transcriptsDir: String = "$sessionDir/transcripts"
    val translationsDir: String = "$sessionDir/translations"

    fun chunkOggPath(chunkIndex: Int): String {
        val idx = chunkIndex.coerceAtLeast(1)
        return "$sessionDir/chunk_${idx.toString().padStart(3, '0')}.ogg"
    }

    fun transcriptChunkPath(chunkIndex: Int): String {
        val idx = chunkIndex.coerceAtLeast(1)
        return "$transcriptsDir/chunk_${idx.toString().padStart(3, '0')}.transcript.json"
    }

    fun translationChunkPath(chunkIndex: Int): String {
        val idx = chunkIndex.coerceAtLeast(1)
        return "$translationsDir/chunk_${idx.toString().padStart(3, '0')}.translation.json"
    }

    val transcriptTasksIndexPath: String = "$transcriptsDir/_tasks.index.json"

    fun transcriptTaskDir(taskId: String): String = "$transcriptsDir/${taskId.trim()}"

    fun transcriptTaskJson(taskId: String): String = "${transcriptTaskDir(taskId)}/_task.json"

    fun transcriptTaskStatusMd(taskId: String): String = "${transcriptTaskDir(taskId)}/_STATUS.md"

    fun transcriptTaskChunkPath(
        taskId: String,
        chunkIndex: Int,
    ): String {
        val idx = chunkIndex.coerceAtLeast(1)
        return "${transcriptTaskDir(taskId)}/chunk_${idx.toString().padStart(3, '0')}.transcript.json"
    }
}

