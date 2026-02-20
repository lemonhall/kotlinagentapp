package com.lsl.kotlin_agent_app.radio_bilingual.player

import android.media.MediaMetadataRetriever
import java.io.File

internal class AndroidMediaMetadataChunkDurationReader : BilingualSessionLoader.ChunkDurationReader {
    override fun readDurationMs(file: File): Long? {
        if (!file.exists() || !file.isFile) return null
        return try {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(file.absolutePath)
                val raw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.trim()
                raw?.toLongOrNull()?.takeIf { it > 0L }
            } finally {
                runCatching { mmr.release() }
            }
        } catch (_: Throwable) {
            null
        }
    }
}

