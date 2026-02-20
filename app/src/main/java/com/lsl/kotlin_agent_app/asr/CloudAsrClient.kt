package com.lsl.kotlin_agent_app.asr

import java.io.File

internal interface CloudAsrClient {
    suspend fun transcribe(
        audioFile: File,
        mimeType: String,
        language: String?,
    ): AsrResult
}

