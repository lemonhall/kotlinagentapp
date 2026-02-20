package com.lsl.kotlin_agent_app.smb_media

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import java.io.FileNotFoundException

class SmbMediaContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? {
        val name = uri.lastPathSegment ?: return null
        return SmbMediaMime.fromFileNameOrNull(name) ?: "application/octet-stream"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        val token = parseTokenOrNull(uri) ?: return null
        val callingUid = Binder.getCallingUid()
        val store = SmbMediaRuntime.ticketStore(ctx)
        val ticket =
            runCatching { store.resolve(token = token, callingUid = callingUid) }
                .getOrElse { return null }

        val cols = projection?.toList()?.takeIf { it.isNotEmpty() }
            ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols.toTypedArray(), 1)

        val displayName = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "media"
        val sizeBytes =
            if (ticket.spec.sizeBytes > 0L) {
                ticket.spec.sizeBytes
            } else {
                runCatching {
                    SmbMediaRuntime.readerFactory(ctx).open(ticket.spec.mountName, ticket.spec.remotePath).use { it.size() }
                }.getOrNull() ?: -1L
            }

        val row = cursor.newRow()
        for (c in cols) {
            when (c) {
                OpenableColumns.DISPLAY_NAME -> row.add(displayName)
                OpenableColumns.SIZE -> row.add(sizeBytes)
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("no context")
        if (mode.contains('w', ignoreCase = true) || mode.contains('t', ignoreCase = true) || mode.contains('+')) {
            throw FileNotFoundException("read-only")
        }
        if (Build.VERSION.SDK_INT < 26) {
            throw FileNotFoundException("Requires Android 8.0+ (API 26) for seekable streaming")
        }

        val token = parseTokenOrNull(uri) ?: throw FileNotFoundException("bad uri")
        val callingUid = Binder.getCallingUid()
        val store = SmbMediaRuntime.ticketStore(ctx)
        val ticket =
            try {
                store.resolve(token = token, callingUid = callingUid)
            } catch (_: Throwable) {
                throw FileNotFoundException("expired")
            }

        val mime = ticket.spec.mime
        if (mime == SmbMediaMime.VIDEO_MP4) {
            SmbMediaStreamingService.requestSessionOpened(ctx)
        }

        val reader = SmbMediaRuntime.readerFactory(ctx).open(ticket.spec.mountName, ticket.spec.remotePath)
        val cache =
            SmbPageCache(
                reader = object : SmbPageCache.RandomAccessReader {
                    override fun size(): Long = reader.size()
                    override fun readAt(offset: Long, size: Int): ByteArray = reader.readAt(offset, size)
                },
                pageSizeBytes = 256 * 1024,
                maxCacheBytes = 64 * 1024 * 1024,
            )

        val callback =
            object : ProxyFileDescriptorCallback() {
                private var released: Boolean = false

                override fun onGetSize(): Long {
                    try {
                        return reader.size()
                    } catch (t: Throwable) {
                        throw ErrnoException("getSize", errnoFor(t))
                    }
                }

                override fun onRead(
                    offset: Long,
                    size: Int,
                    data: ByteArray,
                ): Int {
                    try {
                        val total = reader.size()
                        if (offset >= total) return -1
                        val bytes = cache.read(offset = offset, size = size)
                        if (bytes.isEmpty()) return -1
                        bytes.copyInto(data, destinationOffset = 0, startIndex = 0, endIndex = bytes.size)
                        return bytes.size
                    } catch (t: Throwable) {
                        throw ErrnoException("read", errnoFor(t))
                    }
                }

                override fun onRelease() {
                    if (released) return
                    released = true
                    try {
                        reader.close()
                    } catch (_: Throwable) {
                    }
                    if (mime == SmbMediaMime.VIDEO_MP4) {
                        SmbMediaStreamingService.requestSessionReleased(ctx)
                    }
                }
            }

        val sm = ctx.getSystemService(StorageManager::class.java) ?: throw FileNotFoundException("no storage manager")
        return sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, proxyHandler())
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun parseTokenOrNull(uri: Uri): String? {
        if (uri.authority != SmbMediaUri.AUTHORITY) return null
        val segs = uri.pathSegments ?: return null
        if (segs.size < 2) return null
        if (segs[0] != "v1") return null
        return segs[1].trim().takeIf { it.isNotBlank() }
    }

    private fun errnoFor(t: Throwable): Int {
        val ex = SmbMediaErrorMapper.toException(t)
        return when (ex.code) {
            SmbMediaErrorCode.Timeout -> OsConstants.ETIMEDOUT
            SmbMediaErrorCode.HostUnreachable -> OsConstants.EHOSTUNREACH
            SmbMediaErrorCode.AuthFailed -> OsConstants.EACCES
            SmbMediaErrorCode.PermissionDenied -> OsConstants.EACCES
            SmbMediaErrorCode.ShareNotFound -> OsConstants.ENOENT
            SmbMediaErrorCode.FileNotFound -> OsConstants.ENOENT
            SmbMediaErrorCode.ConnectionReset -> OsConstants.ECONNRESET
            SmbMediaErrorCode.BufferUnderrun -> OsConstants.EIO
            SmbMediaErrorCode.Unknown -> OsConstants.EIO
        }
    }

    private companion object {
        @Volatile
        private var thread: HandlerThread? = null

        private fun proxyHandler(): Handler {
            val existing = thread
            if (existing != null) return Handler(existing.looper)
            synchronized(this) {
                val again = thread
                if (again != null) return Handler(again.looper)
                val ht = HandlerThread("SmbMediaProxyFd")
                ht.start()
                thread = ht
                return Handler(ht.looper)
            }
        }
    }
}
