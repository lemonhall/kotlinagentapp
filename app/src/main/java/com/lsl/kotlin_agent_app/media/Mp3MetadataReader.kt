package com.lsl.kotlin_agent_app.media

import java.io.File

class Mp3MetadataReader(
    private val extractor: Mp3MetadataExtractor = AndroidMp3MetadataExtractor(),
) {
    fun readBestEffort(file: File): Mp3Metadata {
        val fallbackTitle =
            file.nameWithoutExtension.trim().ifBlank {
                file.name.trim().ifBlank { "unknown" }
            }

        val raw =
            try {
                extractor.extract(file)
            } catch (_: Throwable) {
                null
            }

        val title = raw?.title?.trim()?.ifBlank { null } ?: fallbackTitle
        val durationMs = raw?.durationMs?.takeIf { it > 0L }

        return Mp3Metadata(
            title = title,
            artist = raw?.artist?.trim()?.ifBlank { null },
            album = raw?.album?.trim()?.ifBlank { null },
            durationMs = durationMs,
        )
    }
}

