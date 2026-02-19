package com.lsl.kotlin_agent_app.agent.tools.terminal

import com.lsl.kotlin_agent_app.agent.tools.exchange_rate.ExchangeRateTransport
import com.lsl.kotlin_agent_app.agent.tools.irc.IrcClient
import com.lsl.kotlin_agent_app.agent.tools.irc.IrcClientListener
import com.lsl.kotlin_agent_app.agent.tools.rss.RssHttpResponse
import com.lsl.kotlin_agent_app.agent.tools.rss.RssTransport
import com.lsl.kotlin_agent_app.agent.tools.stock.FinnhubHttpResponse
import com.lsl.kotlin_agent_app.agent.tools.stock.FinnhubTransport
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsRuntime
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsSpeakCompletion
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsSpeakRequest
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsSpeakResponse
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsStopResponse
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsTimeout
import com.lsl.kotlin_agent_app.agent.tools.tts.TtsVoiceSummary
import com.lsl.kotlin_agent_app.media.MusicPlaybackRequest
import com.lsl.kotlin_agent_app.media.MusicTransport
import com.lsl.kotlin_agent_app.media.MusicTransportPlaybackState
import com.lsl.kotlin_agent_app.media.MusicTransportSnapshot
import okhttp3.HttpUrl
import java.util.UUID

internal class CapturingIrcClient : IrcClient {
    companion object {
        @Volatile var last: CapturingIrcClient? = null
        @Volatile var connectCalls: Int = 0
    }

    @Volatile var listener: IrcClientListener? = null
    @Volatile private var connected: Boolean = false

    init {
        last = this
    }

    override val isConnected: Boolean
        get() = connected

    override suspend fun connect() {
        connectCalls += 1
        connected = true
    }

    override suspend fun disconnect(message: String?) {
        connected = false
    }

    override suspend fun join(
        channel: String,
        key: String?,
    ) {
        // no-op
    }

    override suspend fun privmsg(
        target: String,
        text: String,
    ) {
        // no-op
    }

    fun emitPrivmsg(
        channel: String,
        nick: String,
        text: String,
    ) {
        listener?.onPrivmsg(channel = channel, nick = nick, text = text, tsMs = System.currentTimeMillis())
    }
}

internal class CapturingFinnhubTransport(
    private val statusCode: Int,
    private val bodyText: String,
    private val headers: Map<String, String>,
) : FinnhubTransport {
    @Volatile var lastUrl: HttpUrl? = null
    @Volatile var lastHeaders: Map<String, String>? = null

    override suspend fun get(
        url: HttpUrl,
        headers: Map<String, String>,
    ): FinnhubHttpResponse {
        lastUrl = url
        lastHeaders = headers.toMap()
        return FinnhubHttpResponse(statusCode = statusCode, bodyText = bodyText, headers = this.headers)
    }
}

internal class CapturingRssTransport(
    private val statusCode: Int,
    private val bodyText: String,
    private val headers: Map<String, String>,
) : RssTransport {
    @Volatile var lastUrl: HttpUrl? = null
    @Volatile var lastHeaders: Map<String, String>? = null

    override suspend fun get(
        url: HttpUrl,
        headers: Map<String, String>,
    ): RssHttpResponse {
        lastUrl = url
        lastHeaders = headers.toMap()
        return RssHttpResponse(statusCode = statusCode, bodyText = bodyText, headers = this.headers)
    }
}

internal class FakeMusicTransport : MusicTransport {
    @Volatile private var playing: Boolean = false
    @Volatile private var playWhenReady: Boolean = false
    @Volatile private var playbackState: MusicTransportPlaybackState = MusicTransportPlaybackState.Idle
    @Volatile private var posMs: Long = 0L
    @Volatile private var durMs: Long? = 60_000L
    @Volatile private var vol: Float = 1.0f

    @Volatile var lastPlayedAgentsPath: String? = null
    @Volatile var lastPlayedUri: String? = null
    @Volatile var playCalls: Int = 0

    override suspend fun connect() {
        // no-op
    }

    override suspend fun play(request: MusicPlaybackRequest) {
        playCalls += 1
        lastPlayedAgentsPath = request.agentsPath
        lastPlayedUri = request.uri
        playWhenReady = true
        playing = true
        playbackState = MusicTransportPlaybackState.Ready
        posMs = 0L
        durMs = request.metadata.durationMs ?: durMs
    }

    override suspend fun pause() {
        playWhenReady = false
        playing = false
    }

    override suspend fun resume() {
        playWhenReady = true
        playing = true
    }

    override suspend fun stop() {
        playWhenReady = false
        playing = false
        playbackState = MusicTransportPlaybackState.Idle
        posMs = 0L
        lastPlayedAgentsPath = null
        lastPlayedUri = null
    }

    override suspend fun seekTo(positionMs: Long) {
        posMs = positionMs.coerceAtLeast(0L)
    }

    override fun snapshot(): MusicTransportSnapshot {
        return MusicTransportSnapshot(
            isConnected = true,
            playbackState = playbackState,
            playWhenReady = playWhenReady,
            isPlaying = playing,
            mediaId = lastPlayedAgentsPath,
            positionMs = posMs,
            durationMs = durMs,
        )
    }

    override fun currentPositionMs(): Long = posMs

    override fun durationMs(): Long? = durMs

    override fun isPlaying(): Boolean = playing

    override suspend fun setVolume(volume: Float) {
        vol = volume
    }

    override fun volume(): Float? = vol

    override fun setListener(listener: com.lsl.kotlin_agent_app.media.MusicTransportListener?) {
        // no-op (fake transport doesn't emit events)
    }
}

internal fun buildFakeMp3Bytes(): ByteArray {
    // Minimal "mp3-looking" bytes: frame sync + padding.
    // It's not meant to be playable; only to satisfy lightweight validation for unit tests.
    val header = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte())
    val body = ByteArray(4096) { 0 }
    return header + body
}

internal class FakeTtsRuntime(
    private val voices: List<TtsVoiceSummary> =
        listOf(
            TtsVoiceSummary(name = "fake-zh", localeTag = "zh-CN"),
        ),
) : TtsRuntime {
    @Volatile var speakCalls: Int = 0
    @Volatile var lastSpeak: TtsSpeakRequest? = null
    @Volatile var stopCalls: Int = 0

    override suspend fun listVoices(): List<TtsVoiceSummary> = voices

    override suspend fun speak(
        req: TtsSpeakRequest,
        await: Boolean,
        timeoutMs: Long?,
    ): TtsSpeakResponse {
        speakCalls += 1
        lastSpeak = req
        if (await && (timeoutMs ?: 0L) <= 1L) {
            throw TtsTimeout("fake timeout")
        }
        val utteranceId = "fake_" + UUID.randomUUID().toString().replace("-", "").take(10)
        val completion = if (await) TtsSpeakCompletion.Done else TtsSpeakCompletion.Started
        return TtsSpeakResponse(utteranceId = utteranceId, completion = completion)
    }

    override suspend fun stop(): TtsStopResponse {
        stopCalls += 1
        return TtsStopResponse(stopped = true)
    }
}

internal class CapturingExchangeRateTransport(
    private val statusCode: Int,
    private val bodyText: String,
    private val headers: Map<String, String>,
) : ExchangeRateTransport {
    @Volatile var lastUrl: HttpUrl? = null
    @Volatile var callCount: Int = 0

    override suspend fun get(url: HttpUrl): com.lsl.kotlin_agent_app.agent.tools.exchange_rate.ExchangeRateHttpResponse {
        lastUrl = url
        callCount += 1
        return com.lsl.kotlin_agent_app.agent.tools.exchange_rate.ExchangeRateHttpResponse(
            statusCode = statusCode,
            bodyText = bodyText,
            headers = headers,
        )
    }
}

internal fun fakeExchangeRateLatestCnyJson(nextUpdateUtc: String): String {
    return """
            {
              "result": "success",
              "base_code": "CNY",
              "time_last_update_utc": "Mon, 17 Feb 2025 00:00:01 +0000",
              "time_next_update_utc": "$nextUpdateUtc",
              "rates": {
                "CNY": 1,
                "USD": 0.1370,
                "EUR": 0.1318,
                "JPY": 20.89,
                "GBP": 0.1095,
                "HKD": 1.0667
              }
            }
        """.trimIndent()
}
