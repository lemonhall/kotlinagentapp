package com.lsl.kotlin_agent_app.media

import android.media.MediaMetadataRetriever
import com.lsl.kotlin_agent_app.media.id3.Id3v2TagEditor
import java.io.File
import java.nio.charset.Charset

data class Mp3NowPlayingExtras(
    val coverArtBytes: ByteArray? = null,
    val lyrics: String? = null,
)

class Mp3NowPlayingExtrasReader {
    fun readBestEffort(file: File): Mp3NowPlayingExtras {
        val coverBytes =
            try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    retriever.embeddedPicture
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
                null
            }

        val lyrics =
            try {
                Id3v2TagEditor.readMetadata(file).lyrics
            } catch (_: Throwable) {
                null
            }
                ?.trim()
                ?.ifBlank { null }

        val sidecarLyrics =
            if (lyrics == null) {
                readSidecarLrcBestEffort(file)
            } else {
                null
            }

        return Mp3NowPlayingExtras(
            coverArtBytes = coverBytes?.takeIf { it.isNotEmpty() },
            lyrics = (lyrics ?: sidecarLyrics)?.trim()?.ifBlank { null },
        )
    }

    private fun readSidecarLrcBestEffort(mp3: File): String? {
        val dir = mp3.parentFile ?: return null
        val lrc = File(dir, mp3.nameWithoutExtension + ".lrc")
        if (!lrc.exists() || !lrc.isFile) return null
        val bytes =
            try {
                lrc.readBytes()
            } catch (_: Throwable) {
                return null
            }
        if (bytes.isEmpty()) return null
        if (bytes.size > 512 * 1024) return null

        fun decode(cs: Charset): String? {
            return try {
                String(bytes, cs)
            } catch (_: Throwable) {
                null
            }
        }

        val utf8 = decode(Charsets.UTF_8)
        if (utf8 != null && !utf8.contains('\uFFFD')) return utf8

        val utf16 = decode(Charsets.UTF_16)
        if (utf16 != null && !utf16.contains('\uFFFD')) return utf16

        val gbk = decode(Charset.forName("GBK"))
        if (gbk != null && !gbk.contains('\uFFFD')) return gbk

        return utf8 ?: utf16 ?: gbk
    }
}
