package com.lsl.kotlin_agent_app.smb_media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbMediaTicketStoreTest {

    @Test
    fun resolve_bindsCallingUidOnFirstAccess_andRejectsDifferentUidLater() {
        val clock = FakeClock(nowMs = 1_000L)
        val store = SmbMediaTicketStore(clock = clock)

        val ticket =
            store.issue(
                SmbMediaTicketSpec(
                    mountName = "home",
                    remotePath = "movies/a.mp4",
                    mime = "video/mp4",
                    sizeBytes = 123L,
                )
            )

        val bound = store.resolve(token = ticket.token, callingUid = 10001)
        assertEquals(10001, bound.boundUid)

        val again = store.resolve(token = ticket.token, callingUid = 10001)
        assertEquals(10001, again.boundUid)

        val err = runCatching { store.resolve(token = ticket.token, callingUid = 10002) }.exceptionOrNull()
        assertNotNull(err)
        assertTrue(err!!.message.orEmpty().lowercase().contains("uid"))
    }

    @Test
    fun resolve_expiresAfterIdleTimeout_usingInjectableClock() {
        val clock = FakeClock(nowMs = 0L)
        val store = SmbMediaTicketStore(clock = clock, idleTtlMs = 30L * 60L * 1000L)

        val ticket =
            store.issue(
                SmbMediaTicketSpec(
                    mountName = "home",
                    remotePath = "musics/a.mp3",
                    mime = "audio/mpeg",
                    sizeBytes = 10L,
                )
            )

        store.resolve(token = ticket.token, callingUid = 1)

        clock.nowMs += 29L * 60L * 1000L
        store.resolve(token = ticket.token, callingUid = 1)

        clock.nowMs += 29L * 60L * 1000L
        store.resolve(token = ticket.token, callingUid = 1)

        clock.nowMs += 31L * 60L * 1000L
        val err = runCatching { store.resolve(token = ticket.token, callingUid = 1) }.exceptionOrNull()
        assertNotNull(err)
        assertTrue(err!!.message.orEmpty().lowercase().contains("expired"))
    }

    @Test
    fun buildUri_doesNotContainMountNameOrRemotePath() {
        val uri = SmbMediaUri.build(token = "t123", displayName = "movie.mp4")
        assertTrue(uri.startsWith("content://"))
        assertTrue(uri.contains("/t123/"))
        assertTrue(!uri.contains("home"))
        assertTrue(!uri.contains("movies/a.mp4"))
    }

    private class FakeClock(var nowMs: Long) : SmbClock {
        override fun nowMs(): Long = nowMs
    }
}

