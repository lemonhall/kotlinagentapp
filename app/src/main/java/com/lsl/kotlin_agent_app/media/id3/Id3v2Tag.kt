package com.lsl.kotlin_agent_app.media.id3

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

data class Id3v2Header(
    val major: Int,
    val revision: Int,
    val flags: Int,
    val tagSizeBytesExcludingHeader: Int,
    val hasExtendedHeader: Boolean,
    val hasFooter: Boolean,
    val hasUnsynchronization: Boolean,
)

data class Id3v2Frame(
    val id: String,
    val flags: Int,
    val payload: ByteArray,
)

data class Id3v2ParsedTag(
    val header: Id3v2Header,
    val frames: List<Id3v2Frame>,
    val audioOffset: Int,
)

data class Id3Metadata(
    val tagVersion: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: String? = null,
    val discNumber: String? = null,
    val year: String? = null,
    val date: String? = null,
    val genre: String? = null,
    val comment: String? = null,
    val composer: String? = null,
    val lyricist: String? = null,
    val lyrics: String? = null,
    val coverArt: Id3CoverArt? = null,
)

data class Id3CoverArt(
    val mime: String,
    val bytesSize: Int,
)

data class Id3WriteResult(
    val before: Id3Metadata,
    val after: Id3Metadata,
    val tagVersionBefore: String?,
    val tagVersionAfter: String?,
)

data class Id3UpdateRequest(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: String? = null,
    val discNumber: String? = null,
    val year: String? = null,
    val date: String? = null,
    val genre: String? = null,
    val comment: String? = null,
    val composer: String? = null,
    val lyricist: String? = null,
    val lyrics: String? = null,
    val coverArtBytes: ByteArray? = null,
    val coverArtMime: String? = null,
)

object Id3v2TagEditor {
    private const val FLAG_UNSYNCHRONIZATION = 0x80
    private const val FLAG_EXTENDED_HEADER = 0x40
    private const val FLAG_FOOTER = 0x10

    fun looksLikeMp3OrId3(file: File): Boolean {
        if (!file.name.lowercase(Locale.US).endsWith(".mp3")) return false
        return try {
            val buf = ByteArray(10)
            val n = file.inputStream().use { it.read(buf, 0, buf.size).coerceAtLeast(0) }
            if (n < 2) return false
            if (n >= 3 && buf[0] == 'I'.code.toByte() && buf[1] == 'D'.code.toByte() && buf[2] == '3'.code.toByte()) return true
            val b0 = buf[0].toInt() and 0xFF
            val b1 = buf[1].toInt() and 0xFF
            b0 == 0xFF && (b1 and 0xE0) == 0xE0
        } catch (_: Throwable) {
            false
        }
    }

    fun readMetadata(file: File): Id3Metadata {
        val parsed = parseTagOrNull(file) ?: return Id3Metadata()
        val major = parsed.header.major
        val ver = "2.$major"
        val framesById = parsed.frames.groupBy { it.id }

        fun text(id: String): String? = framesById[id]?.firstOrNull()?.payload?.let { decodeTextFrame(it) }

        val title = text("TIT2")
        val artist = text("TPE1")
        val album = text("TALB")
        val albumArtist = text("TPE2")
        val trackNumber = text("TRCK")
        val discNumber = text("TPOS")
        val genre = text("TCON")
        val composer = text("TCOM")
        val lyricist = text("TEXT")

        val year =
            when (major) {
                3 -> text("TYER")
                4 -> text("TDRC")
                else -> null
            }
        val date =
            when (major) {
                3 -> text("TDAT") ?: text("TDRC")
                4 -> text("TDRC")
                else -> null
            }

        val comment = framesById["COMM"]?.firstOrNull()?.payload?.let { decodeCommentFrame(it) }
        val lyrics = framesById["USLT"]?.firstOrNull()?.payload?.let { decodeLyricsFrame(it) }
        val coverArt =
            framesById["APIC"]?.firstOrNull()?.payload?.let { payload ->
                decodeApicFrame(payload)
            }

        return Id3Metadata(
            tagVersion = ver,
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            trackNumber = trackNumber,
            discNumber = discNumber,
            year = year,
            date = date,
            genre = genre,
            comment = comment,
            composer = composer,
            lyricist = lyricist,
            lyrics = lyrics,
            coverArt = coverArt,
        )
    }

    fun writeMetadataAtomic(
        file: File,
        update: Id3UpdateRequest,
    ): Id3WriteResult {
        val beforeMd = readMetadata(file)
        val parsed = parseTagOrNull(file)
        val beforeVersion = parsed?.header?.major?.let { "2.$it" }
        val targetMajor = parsed?.header?.major?.takeIf { it == 3 || it == 4 } ?: 3
        val afterVersion = "2.$targetMajor"

        val originalBytes = file.readBytes()
        val audioOffset = parsed?.audioOffset ?: 0
        val audioBytes = if (audioOffset in 0..originalBytes.size) originalBytes.copyOfRange(audioOffset, originalBytes.size) else originalBytes

        val newTagBytes = buildTagBytes(major = targetMajor, parsed = parsed, update = update)
        val newFileBytes = newTagBytes + audioBytes

        val tmp = File(file.parentFile ?: error("missing parent"), file.name + ".tmp." + System.currentTimeMillis())
        tmp.writeBytes(newFileBytes)

        if (System.getProperty("kotlin-agent-app.music.atomic_replace.fail_for_test") == "1") {
            tmp.delete()
            throw IllegalStateException("forced replace failure for test")
        }

        val backup = File(file.parentFile ?: error("missing parent"), file.name + ".bak." + System.currentTimeMillis())
        if (!file.renameTo(backup)) {
            tmp.delete()
            throw IllegalStateException("replace failed: unable to move original to backup")
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            backup.renameTo(file)
            throw IllegalStateException("replace failed: unable to move temp into place")
        }
        backup.delete()

        val afterMd = readMetadata(file).copy(tagVersion = afterVersion)
        return Id3WriteResult(
            before = beforeMd.copy(tagVersion = beforeVersion),
            after = afterMd,
            tagVersionBefore = beforeVersion,
            tagVersionAfter = afterVersion,
        )
    }

    private fun parseTagOrNull(file: File): Id3v2ParsedTag? {
        val bytes =
            try {
                file.readBytes()
            } catch (_: Throwable) {
                return null
            }
        if (bytes.size < 10) return null
        if (!(bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte())) return null

        val major = bytes[3].toInt() and 0xFF
        val revision = bytes[4].toInt() and 0xFF
        if (major != 3 && major != 4) return null
        val flags = bytes[5].toInt() and 0xFF
        val size = decodeSynchsafeInt(bytes, 6)
        val hasUnsync = (flags and FLAG_UNSYNCHRONIZATION) != 0
        val hasExt = (flags and FLAG_EXTENDED_HEADER) != 0
        val hasFooter = (flags and FLAG_FOOTER) != 0

        val tagEnd = (10 + size).coerceAtMost(bytes.size)
        var audioOffset = tagEnd
        if (hasFooter && bytes.size >= tagEnd + 10) {
            if (bytes[tagEnd] == '3'.code.toByte() && bytes[tagEnd + 1] == 'D'.code.toByte() && bytes[tagEnd + 2] == 'I'.code.toByte()) {
                audioOffset = (tagEnd + 10).coerceAtMost(bytes.size)
            }
        }

        var offset = 10
        if (hasExt) {
            if (offset + 4 > tagEnd) return null
            val extSize =
                if (major == 4) decodeSynchsafeInt(bytes, offset) else decodeBigEndianInt(bytes, offset)
            val extTotal = (extSize + 4).coerceAtLeast(0)
            offset = (offset + extTotal).coerceAtMost(tagEnd)
        }

        val frames = mutableListOf<Id3v2Frame>()
        while (offset + 10 <= tagEnd) {
            val id = String(bytes, offset, 4, Charsets.ISO_8859_1)
            if (id.all { it == '\u0000' } || id.trim('\u0000').isEmpty()) break
            if (!id.all { it.isLetterOrDigit() }) break

            val frameSize =
                if (major == 4) decodeSynchsafeInt(bytes, offset + 4) else decodeBigEndianInt(bytes, offset + 4)
            if (frameSize <= 0) break
            if (offset + 10 + frameSize > tagEnd) break

            val f1 = bytes[offset + 8].toInt() and 0xFF
            val f2 = bytes[offset + 9].toInt() and 0xFF
            val frameFlags = (f1 shl 8) or f2
            val payload = bytes.copyOfRange(offset + 10, offset + 10 + frameSize)
            frames.add(Id3v2Frame(id = id, flags = frameFlags, payload = payload))
            offset += 10 + frameSize
        }

        return Id3v2ParsedTag(
            header =
                Id3v2Header(
                    major = major,
                    revision = revision,
                    flags = flags,
                    tagSizeBytesExcludingHeader = size,
                    hasExtendedHeader = hasExt,
                    hasFooter = hasFooter,
                    hasUnsynchronization = hasUnsync,
                ),
            frames = frames,
            audioOffset = audioOffset,
        )
    }

    private fun buildTagBytes(
        major: Int,
        parsed: Id3v2ParsedTag?,
        update: Id3UpdateRequest,
    ): ByteArray {
        val existingFrames = parsed?.frames.orEmpty()
        val toModify = linkedMapOf<String, Id3v2Frame?>()

        fun normalizeUpdateValue(v: String?): String? = v?.trim()?.ifBlank { null }

        val title = normalizeUpdateValue(update.title)
        val artist = normalizeUpdateValue(update.artist)
        val album = normalizeUpdateValue(update.album)
        val albumArtist = normalizeUpdateValue(update.albumArtist)
        val track = normalizeUpdateValue(update.trackNumber)
        val disc = normalizeUpdateValue(update.discNumber)
        val year = normalizeUpdateValue(update.year)
        val date = normalizeUpdateValue(update.date)
        val genre = normalizeUpdateValue(update.genre)
        val comment = normalizeUpdateValue(update.comment)
        val composer = normalizeUpdateValue(update.composer)
        val lyricist = normalizeUpdateValue(update.lyricist)
        val lyrics = normalizeUpdateValue(update.lyrics)

        title?.let { toModify["TIT2"] = makeTextFrame("TIT2", it, major) }
        artist?.let { toModify["TPE1"] = makeTextFrame("TPE1", it, major) }
        album?.let { toModify["TALB"] = makeTextFrame("TALB", it, major) }
        albumArtist?.let { toModify["TPE2"] = makeTextFrame("TPE2", it, major) }
        track?.let { toModify["TRCK"] = makeTextFrame("TRCK", it, major) }
        disc?.let { toModify["TPOS"] = makeTextFrame("TPOS", it, major) }
        genre?.let { toModify["TCON"] = makeTextFrame("TCON", it, major) }
        composer?.let { toModify["TCOM"] = makeTextFrame("TCOM", it, major) }
        lyricist?.let { toModify["TEXT"] = makeTextFrame("TEXT", it, major) }

        if (major == 4) {
            year?.let { toModify["TDRC"] = makeTextFrame("TDRC", it, major) }
            date?.let { toModify["TDRC"] = makeTextFrame("TDRC", it, major) }
        } else {
            year?.let { toModify["TYER"] = makeTextFrame("TYER", it, major) }
            date?.let { toModify["TDAT"] = makeTextFrame("TDAT", it, major) }
        }

        comment?.let { toModify["COMM"] = makeCommentFrame(it, major) }
        lyrics?.let { toModify["USLT"] = makeLyricsFrame(it, major) }

        if (update.coverArtBytes != null && !update.coverArtMime.isNullOrBlank()) {
            toModify["APIC"] = makeApicFrame(bytes = update.coverArtBytes, mime = update.coverArtMime, major = major)
        }

        val preserved = mutableListOf<Id3v2Frame>()
        for (f in existingFrames) {
            if (!toModify.containsKey(f.id)) preserved.add(f)
        }
        val updatedFrames = mutableListOf<Id3v2Frame>()
        updatedFrames.addAll(preserved)
        for ((_, frame) in toModify) {
            if (frame != null) updatedFrames.add(frame)
        }

        val bodyOut = ByteArrayOutputStream()
        for (frame in updatedFrames) {
            bodyOut.write(encodeFrame(frame, major))
        }
        val body = bodyOut.toByteArray()

        val header = ByteArray(10)
        header[0] = 'I'.code.toByte()
        header[1] = 'D'.code.toByte()
        header[2] = '3'.code.toByte()
        header[3] = major.toByte()
        header[4] = 0
        header[5] = 0
        encodeSynchsafeInt(body.size, header, 6)
        return header + body
    }

    private fun encodeFrame(
        frame: Id3v2Frame,
        major: Int,
    ): ByteArray {
        val idBytes = frame.id.toByteArray(Charsets.ISO_8859_1)
        val out = ByteArrayOutputStream()
        out.write(idBytes.copyOf(4))

        val sizeBytes = ByteArray(4)
        if (major == 4) {
            encodeSynchsafeInt(frame.payload.size, sizeBytes, 0)
        } else {
            encodeBigEndianInt(frame.payload.size, sizeBytes, 0)
        }
        out.write(sizeBytes)

        out.write(byteArrayOf(0, 0))
        out.write(frame.payload)
        return out.toByteArray()
    }

    private fun makeTextFrame(
        id: String,
        value: String,
        major: Int,
    ): Id3v2Frame {
        val trimmed = value.trim()
        val payload =
            if (major == 4) {
                byteArrayOf(3) + trimmed.toByteArray(Charsets.UTF_8)
            } else {
                val b = trimmed.toByteArray(Charsets.UTF_16)
                byteArrayOf(1) + b
            }
        return Id3v2Frame(id = id, flags = 0, payload = payload)
    }

    private fun makeCommentFrame(
        comment: String,
        major: Int,
    ): Id3v2Frame {
        val enc = if (major == 4) 3.toByte() else 1.toByte()
        val textBytes = if (major == 4) comment.toByteArray(Charsets.UTF_8) else comment.toByteArray(Charsets.UTF_16)
        val lang = byteArrayOf('e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte())
        val descTerm = if (enc.toInt() == 1) byteArrayOf(0, 0) else byteArrayOf(0)
        val payload = byteArrayOf(enc) + lang + descTerm + textBytes
        return Id3v2Frame(id = "COMM", flags = 0, payload = payload)
    }

    private fun makeLyricsFrame(
        lyrics: String,
        major: Int,
    ): Id3v2Frame {
        val enc = if (major == 4) 3.toByte() else 1.toByte()
        val textBytes = if (major == 4) lyrics.toByteArray(Charsets.UTF_8) else lyrics.toByteArray(Charsets.UTF_16)
        val lang = byteArrayOf('e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte())
        val descTerm = if (enc.toInt() == 1) byteArrayOf(0, 0) else byteArrayOf(0)
        val payload = byteArrayOf(enc) + lang + descTerm + textBytes
        return Id3v2Frame(id = "USLT", flags = 0, payload = payload)
    }

    private fun makeApicFrame(
        bytes: ByteArray,
        mime: String,
        major: Int,
    ): Id3v2Frame {
        val enc = if (major == 4) 3.toByte() else 0.toByte()
        val mimeBytes = mime.trim().ifBlank { "image/jpeg" }.toByteArray(Charsets.ISO_8859_1)
        val descTerm = if (enc.toInt() == 1) byteArrayOf(0, 0) else byteArrayOf(0)
        val payload =
            ByteArrayOutputStream().use { out ->
                out.write(byteArrayOf(enc))
                out.write(mimeBytes)
                out.write(0)
                out.write(3) // front cover
                out.write(descTerm)
                out.write(bytes)
                out.toByteArray()
            }
        return Id3v2Frame(id = "APIC", flags = 0, payload = payload)
    }

    private fun decodeTextFrame(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val enc = payload[0].toInt() and 0xFF
        val data = payload.copyOfRange(1, payload.size)
        val s =
            when (enc) {
                0 -> data.toString(Charsets.ISO_8859_1)
                1 -> data.toString(Charsets.UTF_16)
                2 -> data.toString(Charsets.UTF_16BE)
                3 -> data.toString(Charsets.UTF_8)
                else -> data.toString(Charsets.ISO_8859_1)
            }
        return s.trim('\u0000').trim().ifBlank { null }
    }

    private fun decodeCommentFrame(payload: ByteArray): String? {
        return decodeLangTextFrame(payload, expectLangPrefix = true)
    }

    private fun decodeLyricsFrame(payload: ByteArray): String? {
        return decodeLangTextFrame(payload, expectLangPrefix = true)
    }

    private fun decodeLangTextFrame(
        payload: ByteArray,
        expectLangPrefix: Boolean,
    ): String? {
        if (payload.isEmpty()) return null
        val enc = payload[0].toInt() and 0xFF
        var idx = 1
        if (expectLangPrefix) {
            if (payload.size < 4) return null
            idx += 3
        }
        val termLen = if (enc == 1 || enc == 2) 2 else 1
        val descEnd = findTerminator(payload, start = idx, termLen = termLen)
        val textStart = (descEnd + termLen).coerceAtMost(payload.size)
        val data = payload.copyOfRange(textStart, payload.size)
        val s =
            when (enc) {
                0 -> data.toString(Charsets.ISO_8859_1)
                1 -> data.toString(Charsets.UTF_16)
                2 -> data.toString(Charsets.UTF_16BE)
                3 -> data.toString(Charsets.UTF_8)
                else -> data.toString(Charsets.ISO_8859_1)
            }
        return s.trim('\u0000').trim().ifBlank { null }
    }

    private fun decodeApicFrame(payload: ByteArray): Id3CoverArt? {
        if (payload.isEmpty()) return null
        val enc = payload[0].toInt() and 0xFF
        var idx = 1
        val mimeEnd = indexOfByte(payload, 0.toByte(), from = idx).takeIf { it >= 0 } ?: return null
        val mime = payload.copyOfRange(idx, mimeEnd).toString(Charsets.ISO_8859_1).trim().ifBlank { "application/octet-stream" }
        idx = mimeEnd + 1
        if (idx >= payload.size) return null
        idx += 1 // picture type
        val termLen = if (enc == 1 || enc == 2) 2 else 1
        val descEnd = findTerminator(payload, start = idx, termLen = termLen)
        val dataStart = (descEnd + termLen).coerceAtMost(payload.size)
        val bytesSize = (payload.size - dataStart).coerceAtLeast(0)
        return Id3CoverArt(mime = mime, bytesSize = bytesSize)
    }

    private fun findTerminator(
        bytes: ByteArray,
        start: Int,
        termLen: Int,
    ): Int {
        if (termLen <= 1) {
            val i = indexOfByte(bytes, 0.toByte(), from = start)
            return if (i >= 0) i else bytes.size
        }
        var i = start
        while (i + 1 < bytes.size) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) return i
            i += 2
        }
        return bytes.size
    }

    private fun decodeSynchsafeInt(
        bytes: ByteArray,
        offset: Int,
    ): Int {
        if (offset + 4 > bytes.size) return 0
        val b0 = bytes[offset].toInt() and 0x7F
        val b1 = bytes[offset + 1].toInt() and 0x7F
        val b2 = bytes[offset + 2].toInt() and 0x7F
        val b3 = bytes[offset + 3].toInt() and 0x7F
        return (b0 shl 21) or (b1 shl 14) or (b2 shl 7) or b3
    }

    private fun encodeSynchsafeInt(
        value: Int,
        out: ByteArray,
        offset: Int,
    ) {
        val v = value.coerceAtLeast(0)
        out[offset] = ((v shr 21) and 0x7F).toByte()
        out[offset + 1] = ((v shr 14) and 0x7F).toByte()
        out[offset + 2] = ((v shr 7) and 0x7F).toByte()
        out[offset + 3] = (v and 0x7F).toByte()
    }

    private fun decodeBigEndianInt(
        bytes: ByteArray,
        offset: Int,
    ): Int {
        if (offset + 4 > bytes.size) return 0
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
    }

    private fun encodeBigEndianInt(
        value: Int,
        out: ByteArray,
        offset: Int,
    ) {
        val bb = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(value)
        val b = bb.array()
        out[offset] = b[0]
        out[offset + 1] = b[1]
        out[offset + 2] = b[2]
        out[offset + 3] = b[3]
    }

    private fun indexOfByte(
        bytes: ByteArray,
        needle: Byte,
        from: Int,
    ): Int {
        var i = from.coerceAtLeast(0)
        while (i < bytes.size) {
            if (bytes[i] == needle) return i
            i++
        }
        return -1
    }
}
