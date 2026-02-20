package com.lsl.kotlin_agent_app.translation

internal sealed class TranslationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class LlmNetworkError(
    message: String,
    cause: Throwable? = null,
) : TranslationException(message, cause)

internal class LlmRemoteError(
    message: String,
    cause: Throwable? = null,
) : TranslationException(message, cause)

internal class LlmParseError(
    message: String,
    cause: Throwable? = null,
) : TranslationException(message, cause)

