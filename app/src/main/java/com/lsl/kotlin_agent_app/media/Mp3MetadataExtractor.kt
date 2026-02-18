package com.lsl.kotlin_agent_app.media

import java.io.File

data class RawMp3Metadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
)

fun interface Mp3MetadataExtractor {
    fun extract(file: File): RawMp3Metadata?
}

