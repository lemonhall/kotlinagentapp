package com.lsl.kotlin_agent_app.radio_recordings

import android.content.Context
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lsl.kotlin_agent_app.BuildConfig
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.recordings.RecordingSessionRef
import com.lsl.kotlin_agent_app.radios.StreamResolutionClassification
import com.lsl.kotlin_agent_app.radios.StreamUrlResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class RecordingSession(
    context: Context,
    private val sessionId: String,
) {
    private val appContext = context.applicationContext
    private val ws = AgentsWorkspace(appContext)
    private val store = RadioRecordingsStore(ws)
    private val resolver = StreamUrlResolver()

    private var player: ExoPlayer? = null
    private var chunkWriter: ChunkWriter? = null
    private var job: Job? = null

    fun start(
        scope: CoroutineScope,
        onStopped: (sessionId: String) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < 29) {
            markFailed(code = "UnsupportedSdk", message = "recording requires API 29+ (Android 10)")
            onStopped(sessionId)
            return
        }

        val metaPath = RadioRecordingsPaths.sessionMetaJson(sessionId)
        if (!ws.exists(metaPath)) {
            markFailed(code = "NotFound", message = "missing _meta.json for session: $sessionId")
            onStopped(sessionId)
            return
        }

        job =
            scope.launch(Dispatchers.Main.immediate) {
                val metaRaw =
                    withContext(Dispatchers.IO) {
                        ws.readTextFile(metaPath, maxBytes = 2L * 1024L * 1024L)
                    }
                val meta = RecordingMetaV1.parse(metaRaw)
                val st0 = meta.state.trim()
                if (st0 == "completed" || st0 == "cancelled" || st0 == "failed") {
                    store.writeSessionStatus(sessionId, ok = true, note = "ignored: state=$st0")
                    onStopped(sessionId)
                    return@launch
                }
                val station =
                    meta.station
                        ?: run {
                            markFailed(code = "InvalidMeta", message = "missing station in meta for session: $sessionId")
                            stopInternal(finalState = "failed", note = "InvalidMeta")
                            onStopped(sessionId)
                            return@launch
                        }
                val streamUrlRaw = station.streamUrl.trim()
                val urlToPlay =
                    withContext(Dispatchers.IO) {
                        val resolved = runCatching { resolver.resolve(streamUrlRaw) }.getOrNull()
                        when {
                            resolved == null -> streamUrlRaw
                            resolved.classification == StreamResolutionClassification.Hls -> resolved.finalUrl
                            resolved.candidates.isNotEmpty() -> resolved.candidates.first()
                            resolved.finalUrl.isNotBlank() -> resolved.finalUrl
                            else -> streamUrlRaw
                        }
                    }

                val ref = RecordingSessionRef(rootDir = RadioRecordingsPaths.ROOT_DIR, sessionId = sessionId)
                val writer =
                    ChunkWriter(
                        ws = ws,
                        store = store,
                        sessionRef = ref,
                        chunkDurationMinProvider = { _ -> meta.chunkDurationMin.coerceAtLeast(1) },
                    )
                chunkWriter = writer

                val sink =
                    object : TeeAudioProcessor.AudioBufferSink {
                        override fun flush(
                            sampleRateHz: Int,
                            channelCount: Int,
                            encoding: Int,
                        ) {
                            writer.configurePcmFormat(sampleRateHz = sampleRateHz, channelCount = channelCount, pcmEncoding = encoding)
                        }

                        override fun handleBuffer(buffer: java.nio.ByteBuffer) {
                            writer.offerPcm(buffer)
                        }
                    }

                val tee = TeeAudioProcessor(sink)
                val audioSink: AudioSink =
                    DefaultAudioSink.Builder(appContext)
                        .setEnableFloatOutput(false)
                        .setAudioProcessors(arrayOf(tee))
                        .build()

                val renderersFactory =
                    object : DefaultRenderersFactory(appContext) {
                        override fun buildAudioSink(
                            context: Context,
                            enableFloatOutput: Boolean,
                            enableAudioTrackPlaybackParams: Boolean,
                        ): AudioSink {
                            return audioSink
                        }
                    }

                val ua = "kotlin-agent-app/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.SDK_INT})"
                val http =
                    DefaultHttpDataSource.Factory()
                        .setUserAgent(ua)
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(15_000)
                        .setReadTimeoutMs(20_000)
                val dataSourceFactory = DefaultDataSource.Factory(appContext, http)
                val mediaSourceFactory = DefaultMediaSourceFactory(appContext).setDataSourceFactory(dataSourceFactory)

                val p =
                    ExoPlayer.Builder(appContext, renderersFactory)
                        .setMediaSourceFactory(mediaSourceFactory)
                        .build()
                player = p

                p.addListener(
                    object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            val msg = error.message ?: "player error"
                            markFailed(code = "PlayerError", message = msg)
                            stopInternal(finalState = "failed", note = "PlayerError: $msg")
                            onStopped(sessionId)
                        }
                    },
                )

                val aa =
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build()
                p.setAudioAttributes(aa, false)
                p.volume = 0f
                p.setMediaItem(MediaItem.fromUri(urlToPlay))
                p.prepare()
                p.playWhenReady = true

                store.writeSessionStatus(sessionId, ok = true, note = "recording")
            }
    }

    fun stopCompleted(note: String) {
        stopInternal(finalState = "completed", note = note)
    }

    fun stopCancelled(note: String) {
        stopInternal(finalState = "cancelled", note = note)
    }

    private fun stopInternal(finalState: String, note: String) {
        try {
            job?.cancel()
        } catch (_: Throwable) {
        }
        job = null

        try {
            player?.release()
        } catch (_: Throwable) {
        }
        player = null

        try {
            chunkWriter?.stop(finalState = finalState)
        } catch (_: Throwable) {
        }
        chunkWriter = null

        try {
            store.writeSessionStatus(sessionId, ok = true, note = note)
        } catch (_: Throwable) {
        }
    }

    private fun markFailed(code: String, message: String) {
        try {
            val metaPath = RadioRecordingsPaths.sessionMetaJson(sessionId)
            if (ws.exists(metaPath)) {
                val raw = ws.readTextFile(metaPath, maxBytes = 2L * 1024L * 1024L)
                val prev = RecordingMetaV1.parse(raw)
                val next =
                    prev.copy(
                        state = "failed",
                        updatedAt = RecordingMetaV1.nowIso(),
                        error = RecordingMetaV1.ErrorInfo(code = code, message = message),
                    )
                store.writeSessionMeta(sessionId, next)
            }
        } catch (_: Throwable) {
        }
        try {
            store.writeSessionStatus(sessionId, ok = false, note = "$code: $message")
        } catch (_: Throwable) {
        }
    }
}
