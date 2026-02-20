package com.lsl.kotlin_agent_app.smb_media

import com.hierynomus.protocol.transport.TransportException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SmbMediaErrorMapperTest {

    @Test
    fun classify_socketTimeout_isTimeout() {
        val ex = SocketTimeoutException("read timed out")
        val e = SmbMediaErrorMapper.toException(ex)
        assertEquals(SmbMediaErrorCode.Timeout, e.code)
    }

    @Test
    fun classify_transportException_isHostUnreachable() {
        val ex = TransportException("connect failed")
        val e = SmbMediaErrorMapper.toException(ex)
        assertEquals(SmbMediaErrorCode.HostUnreachable, e.code)
    }

    @Test
    fun classify_messageHints_areMappedToStableCodes() {
        assertEquals(SmbMediaErrorCode.AuthFailed, SmbMediaErrorMapper.toException(IllegalStateException("STATUS_LOGON_FAILURE")).code)
        assertEquals(SmbMediaErrorCode.PermissionDenied, SmbMediaErrorMapper.toException(IllegalStateException("STATUS_ACCESS_DENIED")).code)
        assertEquals(SmbMediaErrorCode.ShareNotFound, SmbMediaErrorMapper.toException(IllegalStateException("STATUS_BAD_NETWORK_NAME")).code)
        assertEquals(SmbMediaErrorCode.FileNotFound, SmbMediaErrorMapper.toException(IllegalStateException("STATUS_OBJECT_NAME_NOT_FOUND")).code)
        assertEquals(SmbMediaErrorCode.ConnectionReset, SmbMediaErrorMapper.toException(IllegalStateException("CONNECTION_RESET")).code)
    }

    @Test
    fun classify_bufferUnderrun_isBufferUnderrun() {
        val ex = SmbMediaBufferUnderrunException("slow network")
        val e = SmbMediaErrorMapper.toException(ex)
        assertEquals(SmbMediaErrorCode.BufferUnderrun, e.code)
    }

    @Test
    fun safeMessage_neverLeaksSensitiveStrings() {
        val ex = IllegalStateException("LOGON_FAILURE user=alice password=secret host=192.168.50.250 share=movies")
        val e = SmbMediaErrorMapper.toException(ex)
        val msg = e.message.orEmpty()
        assertFalse(msg.contains("alice", ignoreCase = true))
        assertFalse(msg.contains("secret", ignoreCase = true))
        assertFalse(msg.contains("192.168.50.250", ignoreCase = true))
        assertFalse(msg.contains("movies", ignoreCase = true))
    }
}

