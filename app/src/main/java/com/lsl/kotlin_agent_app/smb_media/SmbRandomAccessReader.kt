package com.lsl.kotlin_agent_app.smb_media

import java.io.Closeable

interface SmbMediaFileHandle : Closeable {
    fun size(): Long
    fun readAt(
        offset: Long,
        size: Int,
    ): ByteArray
}

interface SmbRandomAccessReader : Closeable {
    fun size(): Long
    fun readAt(
        offset: Long,
        size: Int,
    ): ByteArray
}

class SmbBackendRandomAccessReader(
    private val file: SmbMediaFileHandle,
) : SmbRandomAccessReader {
    private var cachedSize: Long? = null

    override fun size(): Long {
        val cached = cachedSize
        if (cached != null) return cached
        val s =
            try {
                file.size()
            } catch (t: Throwable) {
                throw SmbMediaErrorMapper.toException(t)
            }.coerceAtLeast(0L)
        cachedSize = s
        return s
    }

    override fun readAt(
        offset: Long,
        size: Int,
    ): ByteArray {
        require(offset >= 0L) { "offset must be >= 0" }
        require(size >= 0) { "size must be >= 0" }
        if (size == 0) return ByteArray(0)

        val total = size()
        if (offset >= total) return ByteArray(0)

        val want = minOf(size.toLong(), total - offset).toInt().coerceAtLeast(0)
        if (want == 0) return ByteArray(0)

        return try {
            file.readAt(offset, want)
        } catch (t: Throwable) {
            throw SmbMediaErrorMapper.toException(t)
        }
    }

    override fun close() {
        try {
            file.close()
        } catch (_: Throwable) {
        }
    }
}

