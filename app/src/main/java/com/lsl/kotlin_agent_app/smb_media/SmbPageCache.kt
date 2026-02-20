package com.lsl.kotlin_agent_app.smb_media

/**
 * A small page-based cache for random-access readers.
 *
 * Note: Implementation is intentionally minimal and is driven by unit tests.
 */
class SmbPageCache(
    private val reader: RandomAccessReader,
    private val pageSizeBytes: Int,
    private val maxCacheBytes: Int,
) {
    init {
        require(pageSizeBytes > 0) { "pageSizeBytes must be > 0" }
        require(maxCacheBytes > 0) { "maxCacheBytes must be > 0" }
    }

    interface RandomAccessReader {
        fun size(): Long
        fun readAt(offset: Long, size: Int): ByteArray
    }

    private data class Page(
        val index: Long,
        val bytes: ByteArray,
    )

    private val pages: LinkedHashMap<Long, Page> = LinkedHashMap(16, 0.75f, true)
    private var cachedBytes: Int = 0

    fun read(
        offset: Long,
        size: Int,
    ): ByteArray {
        require(offset >= 0L) { "offset must be >= 0" }
        require(size >= 0) { "size must be >= 0" }

        if (size == 0) return ByteArray(0)
        val total = reader.size().coerceAtLeast(0L)
        if (offset >= total) return ByteArray(0)
        val endExclusive = (offset + size.toLong()).coerceAtMost(total)
        val wantTotal = (endExclusive - offset).toInt().coerceAtLeast(0)
        if (wantTotal == 0) return ByteArray(0)

        val startPage = offset / pageSizeBytes.toLong()
        val endPage = (endExclusive - 1L) / pageSizeBytes.toLong()

        val out = ByteArray(wantTotal)
        var outPos = 0
        var pageIndex = startPage
        while (pageIndex <= endPage) {
            val pageStart = pageIndex * pageSizeBytes.toLong()
            val page = getOrLoadPage(pageIndex = pageIndex, pageStart = pageStart, totalSize = total)

            val withinStart = (offset - pageStart).coerceAtLeast(0L).toInt()
            val withinEndExclusive =
                ((endExclusive - pageStart).coerceAtMost(page.bytes.size.toLong())).toInt().coerceAtLeast(withinStart)

            val chunkLen = (withinEndExclusive - withinStart).coerceAtLeast(0)
            if (chunkLen > 0) {
                page.bytes.copyInto(out, destinationOffset = outPos, startIndex = withinStart, endIndex = withinEndExclusive)
                outPos += chunkLen
            }
            pageIndex += 1
        }

        return if (outPos == out.size) out else out.copyOf(outPos.coerceAtLeast(0))
    }

    @Synchronized
    private fun getOrLoadPage(
        pageIndex: Long,
        pageStart: Long,
        totalSize: Long,
    ): Page {
        pages[pageIndex]?.let { return it }

        val maxReadable = (totalSize - pageStart).coerceAtLeast(0L)
        val want = minOf(pageSizeBytes.toLong(), maxReadable).toInt()
        val bytes = if (want <= 0) ByteArray(0) else reader.readAt(pageStart, want)
        val page = Page(index = pageIndex, bytes = bytes)

        pages[pageIndex] = page
        cachedBytes += bytes.size
        evictAsNeeded()
        return page
    }

    @Synchronized
    private fun evictAsNeeded() {
        val budget = maxCacheBytes.coerceAtLeast(pageSizeBytes)
        while (cachedBytes > budget && pages.isNotEmpty()) {
            val eldestKey = pages.entries.first().key
            val removed = pages.remove(eldestKey) ?: break
            cachedBytes -= removed.bytes.size
        }
        if (cachedBytes < 0) cachedBytes = 0
    }
}
