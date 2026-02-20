package com.lsl.kotlin_agent_app.smb_media

object SmbMediaMime {
    const val AUDIO_MPEG: String = "audio/mpeg"
    const val VIDEO_MP4: String = "video/mp4"
    const val IMAGE_JPEG: String = "image/jpeg"
    const val IMAGE_PNG: String = "image/png"
    const val IMAGE_WEBP: String = "image/webp"
    const val IMAGE_GIF: String = "image/gif"

    fun fromFileNameOrNull(name: String): String? {
        val n = name.trim()
        if (n.isBlank()) return null
        val ext = n.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return when (ext) {
            "mp3" -> AUDIO_MPEG
            "mp4" -> VIDEO_MP4
            "jpg", "jpeg" -> IMAGE_JPEG
            "png" -> IMAGE_PNG
            "webp" -> IMAGE_WEBP
            "gif" -> IMAGE_GIF
            else -> null
        }
    }
}
