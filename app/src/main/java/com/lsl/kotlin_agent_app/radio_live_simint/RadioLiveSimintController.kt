package com.lsl.kotlin_agent_app.radio_live_simint

import android.content.Context
import com.lsl.kotlin_agent_app.radios.StreamResolutionClassification
import com.lsl.kotlin_agent_app.radios.StreamUrlResolver
import com.lsl.kotlin_agent_app.radios.RadioStationFileV1
import com.lsl.kotlin_agent_app.ui.simultaneous_interpretation.AliyunLiveTranslateClient
import com.lsl.kotlin_agent_app.ui.simultaneous_interpretation.AliyunLiveTranslateClientListener
import com.lsl.kotlin_agent_app.ui.simultaneous_interpretation.DashScopeAliyunLiveTranslateClient
import com.lsl.kotlin_agent_app.ui.simultaneous_interpretation.LiveTranslateAudioPlayer
import com.lsl.kotlin_agent_app.ui.simultaneous_interpretation.LiveTranslateAudioInputSource
import com.lsl.kotlin_agent_app.ui.simultaneous_interpretation.AudioTrackLiveTranslateAudioPlayer
import com.lsl.kotlin_agent_app.ui.simultaneous_interpretation.LiveTranslateSessionStartConfig
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class RadioLiveSimintState(
    val isConnecting: Boolean = false,
    val isRunning: Boolean = false,
    val agentsRadioPath: String? = null,
    val targetLanguageCode: String = "zh",
    val targetLanguageLabel: String = "中文",
    val errorMessage: String? = null,
)

internal class RadioLiveSimintController(
    context: Context,
    private val apiKeyProvider: () -> String,
    private val clientFactory: () -> AliyunLiveTranslateClient = { DashScopeAliyunLiveTranslateClient() },
    private val audioPlayerFactory: () -> LiveTranslateAudioPlayer = { AudioTrackLiveTranslateAudioPlayer() },
    private val inputSourceFactory: (String) -> LiveTranslateAudioInputSource = { url -> RadioPlaybackPcmTapInputSource(context, url) },
    private val streamUrlResolver: StreamUrlResolver = StreamUrlResolver(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _state = MutableStateFlow(RadioLiveSimintState())
    val state: StateFlow<RadioLiveSimintState> = _state.asStateFlow()

    private var sessionJob: Job? = null
    private var client: AliyunLiveTranslateClient? = null
    private var inputSource: LiveTranslateAudioInputSource? = null
    private var audioPlayer: LiveTranslateAudioPlayer? = null

    fun isActive(): Boolean = state.value.isConnecting || state.value.isRunning

    fun start(
        agentsRadioPath: String,
        targetLanguageCode: String,
        targetLanguageLabel: String,
    ) {
        if (isActive()) return
        val apiKey = apiKeyProvider.invoke().trim()
        if (apiKey.isBlank()) {
            _state.update { it.copy(errorMessage = "请先在 Settings 的 Voice Input 中填写 DASHSCOPE_API_KEY") }
            return
        }

        _state.update {
            it.copy(
                isConnecting = true,
                isRunning = false,
                agentsRadioPath = agentsRadioPath,
                targetLanguageCode = targetLanguageCode,
                targetLanguageLabel = targetLanguageLabel,
                errorMessage = null,
            )
        }

        val currentClient = clientFactory.invoke()
        val currentAudioPlayer = audioPlayerFactory.invoke()
        client = currentClient
        audioPlayer = currentAudioPlayer

        sessionJob =
            scope.launch {
                try {
                    val urlToPlay = resolveRadioStreamUrl(agentsRadioPath)
                    val currentInputSource = inputSourceFactory.invoke(urlToPlay)
                    inputSource = currentInputSource

                    currentClient.start(
                        config =
                            LiveTranslateSessionStartConfig(
                                apiKey = apiKey,
                                targetLanguageCode = targetLanguageCode,
                                targetLanguageLabel = targetLanguageLabel,
                            ),
                        listener =
                            object : AliyunLiveTranslateClientListener {
                                override fun onSessionCreated(sessionId: String) = Unit

                                override fun onSourceTranscriptCompleted(text: String) = Unit

                                override fun onTranslationDelta(delta: String) = Unit

                                override fun onAudioDelta(bytes: ByteArray) {
                                    currentAudioPlayer.writePcm(bytes)
                                }

                                override fun onResponseDone() = Unit

                                override fun onError(
                                    message: String,
                                    throwable: Throwable?,
                                ) {
                                    fail(message, throwable)
                                }

                                override fun onClosed(
                                    code: Int,
                                    reason: String,
                                ) {
                                    if (isActive()) {
                                        stopInternal(errorMessage = null)
                                    }
                                }
                            },
                    )

                    _state.update { it.copy(isConnecting = false, isRunning = true) }

                    currentInputSource.start(
                        onStarted = {},
                        onAudioFrame = { frame -> currentClient.appendAudioFrame(frame) },
                        onError = { t -> fail(t.message ?: "电台音频输入失败", t) },
                    )
                } catch (t: Throwable) {
                    fail(t.message ?: "Live 同传启动失败", t)
                }
            }
    }

    fun stop() {
        if (!isActive()) return
        stopInternal(errorMessage = null)
    }

    private fun fail(
        message: String,
        throwable: Throwable? = null,
    ) {
        val extra = throwable?.message?.trim()?.ifBlank { null }
        val full = listOfNotNull(message.trim().ifBlank { null }, extra).joinToString(": ").trim().ifBlank { message }
        _state.update { it.copy(errorMessage = full) }
        stopInternal(errorMessage = full)
    }

    private fun stopInternal(errorMessage: String?) {
        if (!isActive()) return
        _state.update { it.copy(isConnecting = false, isRunning = false, errorMessage = errorMessage) }
        scope.launch {
            val job = sessionJob
            sessionJob = null
            runCatching { inputSource?.stop() }
            inputSource = null
            runCatching { client?.stop() }
            client = null
            runCatching { audioPlayer?.close() }
            audioPlayer = null
            if (job != null) {
                runCatching { job.cancelAndJoin() }
            }
        }
    }

    private suspend fun resolveRadioStreamUrl(agentsRadioPath: String): String {
        return withContext(ioDispatcher) {
            val p = agentsRadioPath.replace('\\', '/').trim().trimStart('/')
            val file = File(appContext.filesDir, p)
            if (!file.exists() || !file.isFile) error("not a file: $p")
            val raw = file.readText(Charsets.UTF_8)
            val station = RadioStationFileV1.parse(raw)
            val resolved = runCatching { streamUrlResolver.resolve(station.streamUrl) }.getOrNull()
            when {
                resolved == null -> station.streamUrl
                resolved.classification == StreamResolutionClassification.Hls -> resolved.finalUrl
                resolved.candidates.isNotEmpty() -> resolved.candidates.first()
                resolved.finalUrl.isNotBlank() -> resolved.finalUrl
                else -> station.streamUrl
            }
        }
    }
}

