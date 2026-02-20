package com.lsl.kotlin_agent_app.smb_media

import java.net.SocketTimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbRandomAccessReaderTest {

    @Test
    fun contract_sizeAndReadAt_workAndTruncateAtEof() {
        val bytes = "hello world".toByteArray(Charsets.UTF_8)
        val reader = SmbBackendRandomAccessReader(file = FakeFileHandle(bytes))
        reader.use {
            assertEquals(bytes.size.toLong(), it.size())
            assertArrayEquals("hello".toByteArray(Charsets.UTF_8), it.readAt(0L, 5))
            assertArrayEquals("world".toByteArray(Charsets.UTF_8), it.readAt(6L, 5))
            assertArrayEquals("ld".toByteArray(Charsets.UTF_8), it.readAt(9L, 99))
        }
    }

    @Test
    fun contract_offsetBeyondEof_returnsEmpty() {
        val bytes = "abc".toByteArray(Charsets.UTF_8)
        val reader = SmbBackendRandomAccessReader(file = FakeFileHandle(bytes))
        reader.use {
            assertArrayEquals(ByteArray(0), it.readAt(3L, 10))
            assertArrayEquals(ByteArray(0), it.readAt(999L, 1))
        }
    }

    @Test
    fun contract_negativeOffset_throws() {
        val bytes = "abc".toByteArray(Charsets.UTF_8)
        val reader = SmbBackendRandomAccessReader(file = FakeFileHandle(bytes))
        val err = runCatching { reader.readAt(-1L, 1) }.exceptionOrNull()
        assertNotNull(err)
        assertTrue(err is IllegalArgumentException)
    }

    @Test
    fun errors_timeout_isMappedToStableCode() {
        val reader =
            SmbBackendRandomAccessReader(
                file =
                    object : SmbMediaFileHandle {
                        override fun size(): Long = 10L

                        override fun readAt(offset: Long, size: Int): ByteArray {
                            throw SocketTimeoutException("read timed out password=secret")
                        }

                        override fun close() = Unit
                    },
            )

        val err = runCatching { reader.readAt(0L, 1) }.exceptionOrNull()
        assertNotNull(err)
        assertTrue(err is SmbMediaException)
        assertEquals(SmbMediaErrorCode.Timeout, (err as SmbMediaException).code)
    }

    private class FakeFileHandle(private val bytes: ByteArray) : SmbMediaFileHandle {
        override fun size(): Long = bytes.size.toLong()

        override fun readAt(offset: Long, size: Int): ByteArray {
            val start = offset.toInt()
            if (start < 0) throw IllegalArgumentException("offset")
            if (size <= 0) return ByteArray(0)
            if (start >= bytes.size) return ByteArray(0)
            val end = minOf(bytes.size, start + size)
            return bytes.copyOfRange(start, end)
        }

        override fun close() = Unit
    }
}

