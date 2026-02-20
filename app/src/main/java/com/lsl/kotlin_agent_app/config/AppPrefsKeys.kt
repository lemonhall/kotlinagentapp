package com.lsl.kotlin_agent_app.config

object AppPrefsKeys {
    const val WEB_PREVIEW_ENABLED = "ui.web_preview_enabled"

    const val CHAT_SESSION_ID = "chat.session_id"

    const val LISTENING_HISTORY_ENABLED = "privacy.listening_history_enabled"

    const val PROXY_ENABLED = "net.proxy_enabled"
    const val HTTP_PROXY = "net.http_proxy"
    const val HTTPS_PROXY = "net.https_proxy"

    const val ASR_DASHSCOPE_API_KEY = "asr.dashscope_api_key"
    const val ASR_DASHSCOPE_BASE_URL = "asr.dashscope_base_url"
    const val ASR_MODEL = "asr.model"

    const val MUSIC_PLAYBACK_MODE = "music.playback_mode"
    const val MUSIC_VOLUME = "music.volume"
    const val MUSIC_MUTED = "music.muted"
    const val MUSIC_LAST_NONZERO_VOLUME = "music.last_nonzero_volume"
}
