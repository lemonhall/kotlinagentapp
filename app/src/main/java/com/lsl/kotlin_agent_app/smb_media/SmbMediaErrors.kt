package com.lsl.kotlin_agent_app.smb_media

import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.mssmb2.SMBApiException
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class SmbMediaErrorCode {
    Timeout,
    HostUnreachable,
    AuthFailed,
    PermissionDenied,
    ShareNotFound,
    FileNotFound,
    ConnectionReset,
    BufferUnderrun,
    Unknown,
}

class SmbMediaException(
    val code: SmbMediaErrorCode,
    message: String = code.name,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class SmbMediaBufferUnderrunException(
    message: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

object SmbMediaErrorMapper {

    fun toException(t: Throwable): SmbMediaException {
        if (t is SmbMediaException) return t
        val code = toCode(t)
        return SmbMediaException(code = code, message = safeMessage(code), cause = t)
    }

    private fun toCode(t: Throwable): SmbMediaErrorCode {
        when (t) {
            is SocketTimeoutException -> return SmbMediaErrorCode.Timeout
            is UnknownHostException -> return SmbMediaErrorCode.HostUnreachable
            is ConnectException -> return SmbMediaErrorCode.HostUnreachable
            is TransportException -> return SmbMediaErrorCode.HostUnreachable
            is FileNotFoundException -> return SmbMediaErrorCode.FileNotFound
            is SmbMediaBufferUnderrunException -> return SmbMediaErrorCode.BufferUnderrun
            is SMBApiException -> return classifySmbStatus(t.status?.toString().orEmpty())
        }

        val msg = (t.message ?: "").trim()
        if (msg.isNotBlank()) return classifySmbStatus(msg)
        return SmbMediaErrorCode.Unknown
    }

    private fun classifySmbStatus(statusOrMessage: String): SmbMediaErrorCode {
        val s = statusOrMessage.trim()
        if (s.isBlank()) return SmbMediaErrorCode.Unknown
        return when {
            s.contains("LOGON_FAILURE", ignoreCase = true) -> SmbMediaErrorCode.AuthFailed
            s.contains("ACCESS_DENIED", ignoreCase = true) -> SmbMediaErrorCode.PermissionDenied
            s.contains("BAD_NETWORK_NAME", ignoreCase = true) -> SmbMediaErrorCode.ShareNotFound
            s.contains("OBJECT_NAME_NOT_FOUND", ignoreCase = true) -> SmbMediaErrorCode.FileNotFound
            s.contains("CONNECTION_RESET", ignoreCase = true) -> SmbMediaErrorCode.ConnectionReset
            s.contains("NETWORK_NAME_DELETED", ignoreCase = true) -> SmbMediaErrorCode.ConnectionReset
            s.contains("TIMEOUT", ignoreCase = true) -> SmbMediaErrorCode.Timeout
            else -> SmbMediaErrorCode.Unknown
        }
    }

    private fun safeMessage(code: SmbMediaErrorCode): String {
        return code.name
    }
}

