package com.lsl.kotlin_agent_app.smb_media

object SmbMediaMime {
    const val AUDIO_MPEG: String = "audio/mpeg"
    const val VIDEO_MP4: String = "video/mp4"

    fun fromFileNameOrNull(name: String): String? {
        val n = name.trim()
        if (n.isBlank()) return null
        val ext = n.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return when (ext) {
            "mp3" -> AUDIO_MPEG
            "mp4" -> VIDEO_MP4
            else -> null
        }
    }
}

