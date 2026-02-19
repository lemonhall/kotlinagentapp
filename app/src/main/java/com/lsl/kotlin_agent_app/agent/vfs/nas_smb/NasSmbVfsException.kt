package com.lsl.kotlin_agent_app.agent.vfs.nas_smb

class NasSmbVfsException(
    val errorCode: NasSmbErrorCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException("NasSmb[$errorCode] ${message.trim().ifBlank { "Error" }}", cause)

