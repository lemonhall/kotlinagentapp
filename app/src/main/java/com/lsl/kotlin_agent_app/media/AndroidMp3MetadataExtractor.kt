package com.lsl.kotlin_agent_app.media

import android.media.MediaMetadataRetriever
import java.io.File

class AndroidMp3MetadataExtractor : Mp3MetadataExtractor {
    override fun extract(file: File): RawMp3Metadata? {
        if (!file.exists() || !file.isFile) return null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.ifBlank { null }
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()?.ifBlank { null }
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()?.ifBlank { null }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.trim()?.toLongOrNull()
            return RawMp3Metadata(
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs?.takeIf { it > 0L },
            )
        } catch (_: Throwable) {
            return null
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }
}

