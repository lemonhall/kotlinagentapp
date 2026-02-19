package com.lsl.kotlin_agent_app.agent.vfs.nas_smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.Share
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

class SmbjNasSmbClient(
    connectTimeoutMs: Long = 8_000,
    socketTimeoutMs: Long = 15_000,
) : NasSmbClient {

    private val config =
        SmbConfig
            .builder()
            .withTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .withSoTimeout(socketTimeoutMs.toInt())
            .build()

    private val client = SMBClient(config)

    override fun listDir(
        mount: NasSmbMountConfig,
        remoteDir: String,
    ): List<NasSmbDirEntry> {
        return withDiskShare(mount) { share ->
            val path = toSharePath(remoteDir)
            val infos: List<FileIdBothDirectoryInformation> = share.list(path)
            infos
                .asSequence()
                .mapNotNull { info ->
                    val name = info.fileName ?: return@mapNotNull null
                    if (name == "." || name == "..") return@mapNotNull null
                    val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                    NasSmbDirEntry(name = name, isDirectory = isDir)
                }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .toList()
        }
    }

    override fun metadataOrNull(
        mount: NasSmbMountConfig,
        remotePath: String,
    ): NasSmbMetadata? {
        if (remotePath.isBlank()) return NasSmbMetadata(isRegularFile = false, isDirectory = true)
        return withDiskShare(mount) { share ->
            val p = toSharePath(remotePath)
            when {
                share.folderExists(p) -> NasSmbMetadata(isRegularFile = false, isDirectory = true)
                share.fileExists(p) -> {
                    val info = share.getFileInformation(p)
                    NasSmbMetadata(
                        isRegularFile = true,
                        isDirectory = false,
                        size = info.standardInformation.endOfFile,
                        lastModifiedAtMillis = info.basicInformation.lastWriteTime.toEpochMillis(),
                    )
                }
                else -> null
            }
        }
    }

    override fun readBytes(
        mount: NasSmbMountConfig,
        remotePath: String,
        maxBytes: Long,
    ): ByteArray {
        require(remotePath.isNotBlank()) { "remotePath is empty" }
        return withDiskShare(mount) { share ->
            val p = toSharePath(remotePath)
            val file =
                share.openFile(
                    p,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SHARE_ACCESS_ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
                )
            file.use { f ->
                val cap = (maxBytes.coerceAtLeast(0L)).let { if (it > Int.MAX_VALUE) Int.MAX_VALUE else it.toInt() } + 1
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                val input = f.inputStream
                while (out.size() < cap) {
                    val want = minOf(buf.size, cap - out.size())
                    val n = input.read(buf, 0, want)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                val data = out.toByteArray()
                if (data.size.toLong() > maxBytes) throw IllegalStateException("File too large (${data.size}B), max ${maxBytes}B")
                data
            }
        }
    }

    override fun writeBytes(
        mount: NasSmbMountConfig,
        remotePath: String,
        bytes: ByteArray,
    ) {
        require(remotePath.isNotBlank()) { "remotePath is empty" }
        withDiskShare(mount) { share ->
            val p = toSharePath(remotePath)
            val parentDir = remotePath.substringBeforeLast('/', missingDelimiterValue = "")
            if (parentDir.isNotBlank()) mkdirs(mount, parentDir)
            val file =
                share.openFile(
                    p,
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SHARE_ACCESS_ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
                )
            file.use { f ->
                val out = f.outputStream
                out.write(bytes)
                out.flush()
            }
        }
    }

    override fun mkdirs(
        mount: NasSmbMountConfig,
        remoteDir: String,
    ) {
        val parts =
            remoteDir
                .trim()
                .replace('\\', '/')
                .trim('/')
                .split('/')
                .filter { it.isNotBlank() }
        if (parts.isEmpty()) return
        withDiskShare(mount) { share ->
            var cur = ""
            for (p in parts) {
                cur = if (cur.isBlank()) p else "$cur\\$p"
                if (!share.folderExists(cur)) {
                    share.mkdir(cur)
                }
            }
        }
    }

    override fun delete(
        mount: NasSmbMountConfig,
        remotePath: String,
        recursive: Boolean,
    ) {
        require(remotePath.isNotBlank()) { "remotePath is empty" }
        withDiskShare(mount) { share ->
            val p = toSharePath(remotePath)
            if (share.fileExists(p)) {
                share.rm(p)
                return@withDiskShare
            }
            if (!share.folderExists(p)) return@withDiskShare
            if (!recursive) throw IllegalStateException("Refusing to delete directory without recursive=true")
            deleteDirRecursive(share, p)
        }
    }

    override fun move(
        mount: NasSmbMountConfig,
        fromRemotePath: String,
        toRemotePath: String,
        overwrite: Boolean,
    ) {
        require(fromRemotePath.isNotBlank() && toRemotePath.isNotBlank()) { "remotePath is empty" }
        withDiskShare(mount) { share ->
            val from = toSharePath(fromRemotePath)
            val to = toSharePath(toRemotePath)
            if (overwrite && (share.fileExists(to) || share.folderExists(to))) {
                delete(mount, toRemotePath, recursive = true)
            }
            val entry =
                share.open(
                    from,
                    EnumSet.of(AccessMask.DELETE),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SHARE_ACCESS_ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT),
                )
            entry.use { it.rename(to, overwrite) }
        }
    }

    private fun deleteDirRecursive(
        share: DiskShare,
        path: String,
    ) {
        val children = share.list(path)
        for (info in children) {
            val name = info.fileName ?: continue
            if (name == "." || name == "..") continue
            val child = if (path.isBlank()) name else "$path\\$name"
            val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            if (isDir) {
                deleteDirRecursive(share, child)
                share.rmdir(child, false)
            } else {
                share.rm(child)
            }
        }
        share.rmdir(path, false)
    }

    private fun <T> withDiskShare(
        mount: NasSmbMountConfig,
        block: (DiskShare) -> T,
    ): T {
        var session: Session? = null
        var share: Share? = null
        val connection = try {
            client.connect(mount.host, mount.port)
        } catch (t: Throwable) {
            throw classify(t)
        }
        try {
            val auth =
                if (mount.guest) {
                    AuthenticationContext("", CharArray(0), mount.domain)
                } else {
                    AuthenticationContext(mount.username.orEmpty(), (mount.password ?: "").toCharArray(), mount.domain)
                }
            session = connection.authenticate(auth)
            share = session.connectShare(mount.share)
            val disk = share as? DiskShare ?: throw NasSmbVfsException(NasSmbErrorCode.ShareNotFound, "Not a disk share")
            return block(disk)
        } catch (t: Throwable) {
            throw classify(t)
        } finally {
            try {
                share?.close()
            } catch (_: Throwable) {
            }
            try {
                session?.close()
            } catch (_: Throwable) {
            }
            try {
                connection.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun toSharePath(remote: String): String {
        return remote.trim().replace('\\', '/').trim('/').replace('/', '\\')
    }

    private fun classify(t: Throwable): RuntimeException {
        if (t is NasSmbVfsException) return t
        when (t) {
            is SocketTimeoutException -> return NasSmbVfsException(NasSmbErrorCode.Timeout, "Timeout", t)
            is TransportException -> return NasSmbVfsException(NasSmbErrorCode.HostUnreachable, "Host unreachable", t)
            is SMBApiException -> {
                val s = t.status?.toString().orEmpty()
                val code =
                    when {
                        s.contains("LOGON_FAILURE", ignoreCase = true) -> NasSmbErrorCode.AuthFailed
                        s.contains("ACCESS_DENIED", ignoreCase = true) -> NasSmbErrorCode.PermissionDenied
                        s.contains("BAD_NETWORK_NAME", ignoreCase = true) -> NasSmbErrorCode.ShareNotFound
                        else -> NasSmbErrorCode.HostUnreachable
                    }
                return NasSmbVfsException(code, code.name, t)
            }
        }
        val msg = (t.message ?: t.javaClass.simpleName).take(200)
        return NasSmbVfsException(NasSmbErrorCode.HostUnreachable, msg, t)
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
