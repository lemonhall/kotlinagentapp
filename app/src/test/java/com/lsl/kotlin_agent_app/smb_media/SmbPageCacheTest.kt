package com.lsl.kotlin_agent_app.smb_media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbPageCacheTest {

    @Test
    fun read_hitsCacheWithinSamePage_andDoesNotReReadRemote() {
        val bytes = ByteArray(256) { i -> i.toByte() }
        val fake = FakeReader(bytes)
        val cache = SmbPageCache(reader = fake, pageSizeBytes = 64, maxCacheBytes = 128)

        val a = cache.read(offset = 0, size = 10)
        val b = cache.read(offset = 3, size = 10)

        assertArrayEquals(bytes.copyOfRange(0, 10), a)
        assertArrayEquals(bytes.copyOfRange(3, 13), b)
        assertEquals("Same page should only be fetched once", 1, fake.pageFetchCount)
    }

    @Test
    fun read_evictsLeastRecentlyUsedPageWhenOverBudget() {
        val bytes = ByteArray(256) { i -> i.toByte() }
        val fake = FakeReader(bytes)
        val cache = SmbPageCache(reader = fake, pageSizeBytes = 64, maxCacheBytes = 128) // 2 pages

        // Load page0 and page1 (budget full).
        cache.read(offset = 0, size = 1)
        cache.read(offset = 64, size = 1)
        assertEquals(2, fake.pageFetchCount)

        // Touch page0 to make page1 LRU.
        cache.read(offset = 1, size = 1)
        assertEquals(2, fake.pageFetchCount)

        // Load page2 -> must evict page1.
        cache.read(offset = 128, size = 1)
        assertEquals(3, fake.pageFetchCount)

        // Reading from page1 again should require re-fetch.
        cache.read(offset = 65, size = 1)
        assertEquals(4, fake.pageFetchCount)
    }

    @Test
    fun read_handlesLastPageSmallerThanPageSize_withoutPaddingOrOverread() {
        val bytes = ByteArray(250) { i -> (i % 251).toByte() } // size not multiple of 64
        val fake = FakeReader(bytes)
        val cache = SmbPageCache(reader = fake, pageSizeBytes = 64, maxCacheBytes = 256)

        val tail = cache.read(offset = 240, size = 32)

        assertEquals(10, tail.size)
        assertArrayEquals(bytes.copyOfRange(240, 250), tail)
        assertTrue("Should have fetched at least one page", fake.pageFetchCount >= 1)
    }

    private class FakeReader(private val bytes: ByteArray) : SmbPageCache.RandomAccessReader {
        var pageFetchCount: Int = 0

        override fun size(): Long = bytes.size.toLong()

        override fun readAt(
            offset: Long,
            size: Int,
        ): ByteArray {
            require(offset >= 0)
            require(size >= 0)
            if (offset >= bytes.size.toLong()) return ByteArray(0)
            val endExclusive = (offset + size.toLong()).coerceAtMost(bytes.size.toLong()).toInt()
            val start = offset.toInt()
            pageFetchCount++
            return bytes.copyOfRange(start, endExclusive)
        }
    }
}

