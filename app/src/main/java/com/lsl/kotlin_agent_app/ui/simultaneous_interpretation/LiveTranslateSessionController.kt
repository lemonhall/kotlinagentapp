package com.lsl.kotlin_agent_app.ui.simultaneous_interpretation

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

internal interface LiveTranslateSessionListener {
    fun onSessionConnecting()

    fun onSessionStarted(
        sessionPath: String,
        isHeadsetConnected: Boolean,
    )

    fun onSourceTranscriptCompleted(text: String)

    fun onTranslationDelta(text: String)

    fun onSegmentFinal(
        sourceText: String,
        translatedText: String,
    )

    fun onError(message: String)

    fun onSessionStopped()
}

internal interface LiveTranslateSessionController {
    fun bind(listener: LiveTranslateSessionListener)

    fun start(config: LiveTranslateSessionStartConfig)

    fun stop()
}

internal class DefaultLiveTranslateSessionController(
    context: Context,
    private val archiveManager: SimultaneousInterpretationArchiveManager,
    private val clientFactory: () -> AliyunLiveTranslateClient = { DashScopeAliyunLiveTranslateClient() },
    private val audioInputSourceFactory: () -> LiveTranslateAudioInputSource = { MicrophoneLiveTranslateAudioInputSource() },
    private val audioPlayerFactory: () -> LiveTranslateAudioPlayer = { AudioTrackLiveTranslateAudioPlayer() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LiveTranslateSessionController {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val pendingSources = ArrayDeque<String>()
    private val translationBuffer = StringBuilder()

    @Volatile
    private var listener: LiveTranslateSessionListener? = null

    @Volatile
    private var isRunning: Boolean = false

    private var sessionJob: Job? = null
    private var client: AliyunLiveTranslateClient? = null
    private var inputSource: LiveTranslateAudioInputSource? = null
    private var audioPlayer: LiveTranslateAudioPlayer? = null

    override fun bind(listener: LiveTranslateSessionListener) {
        this.listener = listener
    }

    override fun start(config: LiveTranslateSessionStartConfig) {
        if (isRunning) return
        isRunning = true
        synchronized(this) {
            pendingSources.clear()
            translationBuffer.setLength(0)
        }
        listener?.onSessionConnecting()
        val sessionPath = archiveManager.startNewSession(config.targetLanguageCode, config.targetLanguageLabel)
        val headsetConnected = isHeadsetConnected(appContext)
        val currentClient = clientFactory.invoke()
        val currentInputSource = audioInputSourceFactory.invoke()
        val currentAudioPlayer = audioPlayerFactory.invoke()
        client = currentClient
        inputSource = currentInputSource
        audioPlayer = currentAudioPlayer

        sessionJob =
            scope.launch {
                try {
                    currentClient.start(
                        config = config,
                        listener =
                            object : AliyunLiveTranslateClientListener {
                                override fun onSessionCreated(sessionId: String) = Unit

                                override fun onSourceTranscriptCompleted(text: String) {
                                    val transcript = text.trim()
                                    if (transcript.isBlank()) return
                                    synchronized(this@DefaultLiveTranslateSessionController) {
                                        pendingSources.addLast(transcript)
                                    }
                                    listener?.onSourceTranscriptCompleted(transcript)
                                }

                                override fun onTranslationDelta(delta: String) {
                                    val preview =
                                        synchronized(this@DefaultLiveTranslateSessionController) {
                                            translationBuffer.append(delta)
                                            translationBuffer.toString()
                                        }
                                    listener?.onTranslationDelta(preview)
                                }

                                override fun onAudioDelta(bytes: ByteArray) {
                                    currentAudioPlayer.writePcm(bytes)
                                }

                                override fun onResponseDone() {
                                    val segment =
                                        synchronized(this@DefaultLiveTranslateSessionController) {
                                            val source = (if (pendingSources.isEmpty()) "" else pendingSources.removeFirst()).trim()
                                            val translated = translationBuffer.toString().trim()
                                            translationBuffer.setLength(0)
                                            if (source.isBlank() || translated.isBlank()) {
                                                null
                                            } else {
                                                source to translated
                                            }
                                        }
                                    if (segment != null) {
                                        listener?.onSegmentFinal(segment.first, segment.second)
                                    }
                                }

                                override fun onError(
                                    message: String,
                                    throwable: Throwable?,
                                ) {
                                    fail(message)
                                }

                                override fun onClosed(
                                    code: Int,
                                    reason: String,
                                ) {
                                    if (isRunning) {
                                        stopInternal(notifyStopped = true)
                                    }
                                }
                            },
                    )
                    listener?.onSessionStarted(sessionPath = sessionPath, isHeadsetConnected = headsetConnected)
                    currentInputSource.start(
                        onStarted = {},
                        onAudioFrame = { bytes ->
                            currentClient.appendAudioFrame(bytes)
                        },
                        onError = { t ->
                            fail(t.message ?: "麦克风采集失败")
                        },
                    )
                } catch (t: Throwable) {
                    fail(t.message ?: "同声传译启动失败")
                }
            }
    }

    override fun stop() {
        stopInternal(notifyStopped = true)
    }

    private fun fail(message: String) {
        listener?.onError(message)
        stopInternal(notifyStopped = true)
    }

    private fun stopInternal(notifyStopped: Boolean) {
        if (!isRunning) return
        isRunning = false
        scope.launch {
            val job = sessionJob
            sessionJob = null
            runCatching { inputSource?.stop() }
            inputSource = null
            runCatching { client?.stop() }
            client = null
            runCatching { audioPlayer?.close() }
            audioPlayer = null
            synchronized(this@DefaultLiveTranslateSessionController) {
                pendingSources.clear()
                translationBuffer.setLength(0)
            }
            if (job != null) {
                runCatching { job.cancelAndJoin() }
            }
            if (notifyStopped) {
                listener?.onSessionStopped()
            }
        }
    }
}

private fun isHeadsetConnected(context: Context): Boolean {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
    } else {
        @Suppress("DEPRECATION")
        audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn
    }
}