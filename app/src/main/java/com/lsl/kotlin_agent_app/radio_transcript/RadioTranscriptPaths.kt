package com.lsl.kotlin_agent_app.radio_transcript

internal object RadioTranscriptPaths {
    private const val RECORDINGS_ROOT = ".agents/workspace/radio_recordings"

    fun transcriptsRootDir(sessionId: String): String = "$RECORDINGS_ROOT/${sessionId.trim()}/transcripts"

    fun tasksIndexJson(sessionId: String): String = "${transcriptsRootDir(sessionId)}/_tasks.index.json"

    fun taskDir(sessionId: String, taskId: String): String = "${transcriptsRootDir(sessionId)}/${taskId.trim()}"

    fun taskJson(sessionId: String, taskId: String): String = "${taskDir(sessionId, taskId)}/_task.json"

    fun taskStatusMd(sessionId: String, taskId: String): String = "${taskDir(sessionId, taskId)}/_STATUS.md"

    fun chunkTranscriptJson(
        sessionId: String,
        taskId: String,
        chunkIndex: Int,
    ): String {
        val idx = chunkIndex.coerceAtLeast(1)
        val name = "chunk_${idx.toString().padStart(3, '0')}.transcript.json"
        return "${taskDir(sessionId, taskId)}/$name"
    }
}

