package com.lsl.kotlin_agent_app.asr

internal open class AsrException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal class AsrNetworkError(
    message: String,
    cause: Throwable? = null,
) : AsrException(message, cause)

internal class AsrUploadError(
    message: String,
    cause: Throwable? = null,
) : AsrException(message, cause)

internal class AsrRemoteError(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : AsrException(message, cause)

internal class AsrParseError(
    message: String,
    cause: Throwable? = null,
) : AsrException(message, cause)

internal class AsrTaskTimeout(
    message: String,
) : AsrException(message)

