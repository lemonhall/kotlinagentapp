package com.lsl.kotlin_agent_app.voiceinput

data class VoiceInputConfig(
    val apiKey: String,
    val model: String = VoiceInputDefaults.MODEL,
    val websocketApiUrl: String = VoiceInputDefaults.WEBSOCKET_API_URL,
    val sampleRateHz: Int = VoiceInputDefaults.SAMPLE_RATE_HZ,
    val audioFormat: String = VoiceInputDefaults.AUDIO_FORMAT,
)

object VoiceInputDefaults {
    const val MODEL = "fun-asr-realtime"
    const val WEBSOCKET_API_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
    const val SAMPLE_RATE_HZ = 16_000
    const val AUDIO_FORMAT = "pcm"
}
