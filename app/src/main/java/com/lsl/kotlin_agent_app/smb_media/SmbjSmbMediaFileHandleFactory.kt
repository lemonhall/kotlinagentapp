package com.lsl.kotlin_agent_app.smb_media

import android.content.Context
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.hierynomus.smbj.share.Share
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.FileNasSmbMountsProvider
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.NasSmbMountConfig
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.NasSmbMountConfigLoader
import java.io.Closeable
import java.util.EnumSet
import java.util.concurrent.TimeUnit

class SmbjSmbMediaFileHandleFactory(
    context: Context,
    connectTimeoutMs: Long = 8_000,
    socketTimeoutMs: Long = 15_000,
) : SmbMediaFileHandleFactory {
    private val appContext = context.applicationContext
    private val mountsProvider =
        FileNasSmbMountsProvider(
            envFile = java.io.File(appContext.filesDir, ".agents/nas_smb/secrets/.env"),
            loader = NasSmbMountConfigLoader(),
        )

    private val pool = SmbjConnectionPool(connectTimeoutMs = connectTimeoutMs, socketTimeoutMs = socketTimeoutMs)

    override fun open(
        mountName: String,
        relPath: String,
    ): SmbMediaFileHandle {
        val mount = resolveMount(mountName)
        val remotePath = SmbMediaRemotePath.toRemotePath(mount, relPath)
        val sharePath = SmbMediaRemotePath.toSharePath(remotePath)
        return pool.openFile(mount, sharePath = sharePath)
    }

    private fun resolveMount(mountName: String): NasSmbMountConfig {
        val key = mountName.trim().lowercase()
        val mount = mountsProvider.mountsByName()[key]
        return mount ?: throw SmbMediaException(SmbMediaErrorCode.Unknown, "Unknown mount")
    }

    private class SmbjConnectionPool(
        connectTimeoutMs: Long,
        socketTimeoutMs: Long,
    ) : Closeable {
        private val config =
            SmbConfig
                .builder()
                .withTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .withSoTimeout(socketTimeoutMs.toInt())
                .build()
        private val client = SMBClient(config)

        private val runtimes = linkedMapOf<String, MountRuntime>()

        fun openFile(
            mount: NasSmbMountConfig,
            sharePath: String,
        ): SmbMediaFileHandle {
            val runtime = runtimeFor(mount)
            return runtime.openFile(sharePath)
        }

        @Synchronized
        private fun runtimeFor(mount: NasSmbMountConfig): MountRuntime {
            val key = mount.mountName.trim().lowercase()
            val existing = runtimes[key]
            if (existing != null) {
                if (existing.mount != mount) {
                    try {
                        existing.close()
                    } catch (_: Throwable) {
                    }
                    runtimes.remove(key)
                } else {
                    return existing
                }
            }
            val rt = MountRuntime(client = client, mount = mount)
            runtimes[key] = rt
            return rt
        }

        override fun close() {
            val all = synchronized(this) { runtimes.values.toList().also { runtimes.clear() } }
            for (rt in all) {
                try {
                    rt.close()
                } catch (_: Throwable) {
                }
            }
            try {
                client.close()
            } catch (_: Throwable) {
            }
        }
    }

    private class MountRuntime(
        private val client: SMBClient,
        val mount: NasSmbMountConfig,
    ) : Closeable {
        private var connection: com.hierynomus.smbj.connection.Connection? = null
        private var session: Session? = null
        private var share: Share? = null
        private var disk: DiskShare? = null
        private var refCount: Int = 0

        @Synchronized
        fun openFile(sharePath: String): SmbMediaFileHandle {
            val file = openFileLocked(sharePath = sharePath, incrementRefCount = true)
            return SmbjFileHandle(runtime = this, sharePath = sharePath, file = file)
        }

        @Synchronized
        fun reopenFileForExistingHandle(sharePath: String): File {
            return openFileLocked(sharePath = sharePath, incrementRefCount = false)
        }

        @Synchronized
        private fun openFileLocked(
            sharePath: String,
            incrementRefCount: Boolean,
        ): File {
            ensureConnected()
            val d = disk ?: throw SmbMediaException(SmbMediaErrorCode.ShareNotFound, "Share not found")
            val file =
                d.openFile(
                    sharePath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SHARE_ACCESS_ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
                )
            if (incrementRefCount) refCount += 1
            return file
        }

        @Synchronized
        private fun ensureConnected() {
            if (disk != null) return
            val conn = client.connect(mount.host, mount.port)
            val auth =
                if (mount.guest) {
                    AuthenticationContext("", CharArray(0), mount.domain)
                } else {
                    AuthenticationContext(mount.username.orEmpty(), (mount.password ?: "").toCharArray(), mount.domain)
                }
            val sess = conn.authenticate(auth)
            val sh = sess.connectShare(mount.share)
            val d = sh as? DiskShare ?: throw SmbMediaException(SmbMediaErrorCode.ShareNotFound, "Not a disk share")

            connection = conn
            session = sess
            share = sh
            disk = d
        }

        @Synchronized
        fun reconnect() {
            closeConnectionOnly()
            ensureConnected()
        }

        @Synchronized
        fun releaseFileHandle() {
            refCount -= 1
            if (refCount <= 0) {
                refCount = 0
                closeConnectionOnly()
            }
        }

        @Synchronized
        private fun closeConnectionOnly() {
            val sh = share
            val sess = session
            val conn = connection
            share = null
            session = null
            connection = null
            disk = null
            try {
                sh?.close()
            } catch (_: Throwable) {
            }
            try {
                sess?.close()
            } catch (_: Throwable) {
            }
            try {
                conn?.close()
            } catch (_: Throwable) {
            }
        }

        override fun close() {
            closeConnectionOnly()
        }
    }

    private class SmbjFileHandle(
        private val runtime: MountRuntime,
        private val sharePath: String,
        private var file: File,
    ) : SmbMediaFileHandle {
        private var closed: Boolean = false
        private var cachedSize: Long? = null

        override fun size(): Long {
            val cached = cachedSize
            if (cached != null) return cached
            val s =
                try {
                    file.getFileInformation().standardInformation.endOfFile
                } catch (t: Throwable) {
                    val ex = SmbMediaErrorMapper.toException(t)
                    if (ex.code == SmbMediaErrorCode.ConnectionReset || ex.code == SmbMediaErrorCode.HostUnreachable) {
                        try {
                            file.closeNoWait()
                        } catch (_: Throwable) {
                        }
                        runtime.reconnect()
                        file = runtime.reopenFileForExistingHandle(sharePath)
                        file.getFileInformation().standardInformation.endOfFile
                    } else {
                        throw t
                    }
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

            val buf = ByteArray(want)
            val n =
                try {
                    file.read(buf, offset, 0, want)
                } catch (t: Throwable) {
                    val ex = SmbMediaErrorMapper.toException(t)
                    if (ex.code == SmbMediaErrorCode.ConnectionReset || ex.code == SmbMediaErrorCode.HostUnreachable) {
                        try {
                            file.closeNoWait()
                        } catch (_: Throwable) {
                        }
                        runtime.reconnect()
                        this.file = runtime.reopenFileForExistingHandle(sharePath)
                        this.cachedSize = null
                        file.read(buf, offset, 0, want)
                    } else {
                        throw t
                    }
                }
            if (n <= 0) throw SmbMediaBufferUnderrunException("buffer underrun")
            return if (n == buf.size) buf else buf.copyOf(n.coerceAtLeast(0))
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                file.close()
            } catch (_: Throwable) {
            } finally {
                runtime.releaseFileHandle()
            }
        }
    }

    private companion object {
        private val SHARE_ACCESS_ALL: Set<SMB2ShareAccess> =
            EnumSet.of(
                SMB2ShareAccess.FILE_SHARE_READ,
                SMB2ShareAccess.FILE_SHARE_WRITE,
                SMB2ShareAccess.FILE_SHARE_DELETE,
            )
    }
}
