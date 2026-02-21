package com.lsl.kotlin_agent_app.smb_media

object SmbMediaMime {
    const val AUDIO_MPEG: String = "audio/mpeg"
    const val AUDIO_AAC: String = "audio/aac"
    const val AUDIO_MP4: String = "audio/mp4"
    const val AUDIO_FLAC: String = "audio/flac"
    const val AUDIO_WAV: String = "audio/wav"
    const val AUDIO_OGG: String = "audio/ogg"
    const val AUDIO_OPUS: String = "audio/opus"
    const val APPLICATION_PDF: String = "application/pdf"
    const val VIDEO_MP4: String = "video/mp4"
    const val VIDEO_MATROSKA: String = "video/x-matroska"
    const val VIDEO_WEBM: String = "video/webm"
    const val VIDEO_QUICKTIME: String = "video/quicktime"
    const val VIDEO_AVI: String = "video/x-msvideo"
    const val VIDEO_3GPP: String = "video/3gpp"
    const val VIDEO_MPEG_TS: String = "video/mp2t"
    const val IMAGE_JPEG: String = "image/jpeg"
    const val IMAGE_PNG: String = "image/png"
    const val IMAGE_WEBP: String = "image/webp"
    const val IMAGE_GIF: String = "image/gif"
    const val IMAGE_BMP: String = "image/bmp"
    const val IMAGE_HEIC: String = "image/heic"
    const val IMAGE_HEIF: String = "image/heif"

    fun isVideoMime(mime: String): Boolean {
        return mime.trim().lowercase().startsWith("video/")
    }

    fun isAudioMime(mime: String): Boolean {
        return mime.trim().lowercase().startsWith("audio/")
    }

    fun fromFileNameOrNull(name: String): String? {
        val n = name.trim()
        if (n.isBlank()) return null
        val ext = n.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return when (ext) {
            "mp3" -> AUDIO_MPEG
            "aac" -> AUDIO_AAC
            "m4a" -> AUDIO_MP4
            "flac" -> AUDIO_FLAC
            "wav" -> AUDIO_WAV
            "ogg" -> AUDIO_OGG
            "opus" -> AUDIO_OPUS
            "pdf" -> APPLICATION_PDF
            "mp4", "m4v" -> VIDEO_MP4
            "mkv" -> VIDEO_MATROSKA
            "webm" -> VIDEO_WEBM
            "mov" -> VIDEO_QUICKTIME
            "avi" -> VIDEO_AVI
            "3gp" -> VIDEO_3GPP
            "ts" -> VIDEO_MPEG_TS
            "jpg", "jpeg" -> IMAGE_JPEG
            "png" -> IMAGE_PNG
            "webp" -> IMAGE_WEBP
            "gif" -> IMAGE_GIF
            "bmp" -> IMAGE_BMP
            "heic" -> IMAGE_HEIC
            "heif" -> IMAGE_HEIF
            else -> null
        }
    }
}
