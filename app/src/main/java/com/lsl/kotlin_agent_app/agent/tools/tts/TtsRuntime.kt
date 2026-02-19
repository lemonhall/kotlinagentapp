package com.lsl.kotlin_agent_app.agent.tools.tts

internal data class TtsVoiceSummary(
    val name: String,
    val localeTag: String,
    val quality: Int? = null,
    val latency: Int? = null,
    val requiresNetwork: Boolean? = null,
)

internal enum class TtsQueueMode {
    Flush,
    Add,
}

internal data class TtsSpeakRequest(
    val text: String,
    val localeTag: String? = null,
    val rate: Float? = null,
    val pitch: Float? = null,
    val queueMode: TtsQueueMode = TtsQueueMode.Flush,
)

internal enum class TtsSpeakCompletion {
    Started,
    Done,
    Error,
    Stopped,
}

internal data class TtsSpeakResponse(
    val utteranceId: String,
    val completion: TtsSpeakCompletion,
)

internal data class TtsStopResponse(
    val stopped: Boolean,
)

internal interface TtsRuntime {
    suspend fun listVoices(): List<TtsVoiceSummary>

    suspend fun speak(
        req: TtsSpeakRequest,
        await: Boolean,
        timeoutMs: Long?,
    ): TtsSpeakResponse

    suspend fun stop(): TtsStopResponse
}

internal open class TtsFailure(
    val code: String,
    message: String,
) : RuntimeException(message)

internal class TtsInitFailed(
    message: String,
) : TtsFailure(code = "TtsInitFailed", message = message)

internal class TtsNotSupported(
    message: String,
) : TtsFailure(code = "NotSupported", message = message)

internal class TtsTimeout(
    message: String,
) : TtsFailure(code = "Timeout", message = message)

internal class TtsAudioFocusDenied(
    message: String,
) : TtsFailure(code = "AudioFocusDenied", message = message)

