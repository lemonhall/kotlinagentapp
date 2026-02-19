package com.lsl.kotlin_agent_app.agent.vfs

import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.NasSmbVfs
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.NasSmbVfsException
import okio.Buffer
import okio.FileMetadata
import okio.FileSystem
import okio.ForwardingSink
import okio.ForwardingSource
import okio.Path
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer
import okio.sink
import okio.source
import java.io.ByteArrayOutputStream
import java.io.IOException

class AgentsVfsFileSystem(
    private val delegate: FileSystem,
    private val agentsRoot: Path,
    private val nasSmbVfs: NasSmbVfs,
) : FileSystem() {

    private data class NasHit(
        val mountName: String,
        val rel: String,
    )

    private fun nasHitOrNull(p: Path): NasHit? {
        val segs = safeRelativeSegmentsToAgentsRoot(p) ?: return null
        if (segs.isEmpty()) return null
        if (segs[0] != "nas_smb") return null
        if (segs.size < 2) return null
        val second = segs[1]
        if (second == "secrets") return null
        val mountName = second
        val relPath = if (segs.size <= 2) "" else segs.drop(2).joinToString("/")
        return NasHit(mountName = mountName, rel = relPath)
    }

    private fun safeRelativeSegmentsToAgentsRoot(p: Path): List<String>? {
        val a = agentsRoot.normalized()
        val b = p.normalized()
        val aSegs = a.segments
        val bSegs = b.segments
        val ok = bSegs.size >= aSegs.size && bSegs.subList(0, aSegs.size) == aSegs
        if (!ok) return null
        return bSegs.drop(aSegs.size)
    }

    override fun canonicalize(path: Path): Path {
        val hit = nasHitOrNull(path)
        if (hit != null) return path.normalized()
        return delegate.canonicalize(path)
    }

    override fun metadataOrNull(path: Path): FileMetadata? {
        val hit = nasHitOrNull(path)
        if (hit == null) return delegate.metadataOrNull(path)

        if (hit.rel.isBlank()) {
            return FileMetadata(
                isRegularFile = false,
                isDirectory = true,
                symlinkTarget = null,
                size = null,
                createdAtMillis = null,
                lastModifiedAtMillis = null,
                lastAccessedAtMillis = null,
                extras = emptyMap(),
            )
        }

        val meta = nasSmbVfs.metadataOrNull(hit.mountName, hit.rel) ?: return null
        return FileMetadata(
            isRegularFile = meta.isRegularFile,
            isDirectory = meta.isDirectory,
            symlinkTarget = null,
            size = meta.size,
            createdAtMillis = null,
            lastModifiedAtMillis = meta.lastModifiedAtMillis,
            lastAccessedAtMillis = null,
            extras = emptyMap(),
        )
    }

    override fun list(dir: Path): List<Path> {
        val hit = nasHitOrNull(dir)
        if (hit == null) return delegate.list(dir)

        val entries = nasSmbVfs.listDir(hit.mountName, hit.rel)
        return entries.map { dir.resolve(it.name) }
    }

    override fun listOrNull(dir: Path): List<Path>? {
        return try {
            list(dir)
        } catch (_: Throwable) {
            null
        }
    }

    override fun openReadOnly(file: Path): okio.FileHandle {
        val hit = nasHitOrNull(file)
        if (hit == null) return delegate.openReadOnly(file)
        return InMemoryNasFileHandle(
            readOnly = true,
            readBytes = { nasSmbVfs.readTextFile(hit.mountName, hit.rel, maxBytes = Long.MAX_VALUE).toByteArray(Charsets.UTF_8) },
            writeBytes = null,
        )
    }

    override fun openReadWrite(
        file: Path,
        mustCreate: Boolean,
        mustExist: Boolean,
    ): okio.FileHandle {
        val hit = nasHitOrNull(file)
        if (hit == null) return delegate.openReadWrite(file, mustCreate, mustExist)

        val exists = nasSmbVfs.metadataOrNull(hit.mountName, hit.rel) != null
        if (mustCreate && exists) throw IOException("File exists: $file")
        if (mustExist && !exists) throw IOException("File not found: $file")

        return InMemoryNasFileHandle(
            readOnly = false,
            readBytes = { nasSmbVfs.readTextFile(hit.mountName, hit.rel, maxBytes = Long.MAX_VALUE).toByteArray(Charsets.UTF_8) },
            writeBytes = { bytes -> nasSmbVfs.writeTextFile(hit.mountName, hit.rel, bytes.toString(Charsets.UTF_8)) },
        )
    }

    override fun source(file: Path): Source {
        val hit = nasHitOrNull(file)
        if (hit == null) return delegate.source(file)
        val bytes = nasSmbVfs.readTextFile(hit.mountName, hit.rel, maxBytes = Long.MAX_VALUE).toByteArray(Charsets.UTF_8)
        val src = bytes.inputStream().source()
        return object : ForwardingSource(src) {
            override fun timeout(): Timeout = Timeout.NONE
        }
    }

    override fun sink(
        file: Path,
        mustCreate: Boolean,
    ): Sink {
        val hit = nasHitOrNull(file)
        if (hit == null) return delegate.sink(file, mustCreate)

        val exists = nasSmbVfs.metadataOrNull(hit.mountName, hit.rel) != null
        if (mustCreate && exists) throw IOException("File exists: $file")

        val baos = ByteArrayOutputStream()
        val inner = baos.sink()
        return object : ForwardingSink(inner) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    nasSmbVfs.writeTextFile(hit.mountName, hit.rel, baos.toByteArray().toString(Charsets.UTF_8))
                }
            }
        }
    }

    override fun appendingSink(
        file: Path,
        mustExist: Boolean,
    ): Sink {
        val hit = nasHitOrNull(file)
        if (hit == null) return delegate.appendingSink(file, mustExist)

        val existing = nasSmbVfs.metadataOrNull(hit.mountName, hit.rel)
        if (mustExist && existing == null) throw IOException("File not found: $file")

        val existingBytes =
            if (existing != null && existing.isRegularFile) {
                nasSmbVfs.readTextFile(hit.mountName, hit.rel, maxBytes = Long.MAX_VALUE).toByteArray(Charsets.UTF_8)
            } else {
                ByteArray(0)
            }

        val baos = ByteArrayOutputStream()
        baos.write(existingBytes)
        val inner = baos.sink()
        return object : ForwardingSink(inner) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    nasSmbVfs.writeTextFile(hit.mountName, hit.rel, baos.toByteArray().toString(Charsets.UTF_8))
                }
            }
        }
    }

    override fun createDirectory(
        dir: Path,
        mustCreate: Boolean,
    ) {
        val hit = nasHitOrNull(dir)
        if (hit == null) return delegate.createDirectory(dir, mustCreate)

        val existing = nasSmbVfs.metadataOrNull(hit.mountName, hit.rel)
        if (existing != null) {
            if (mustCreate) throw IOException("Directory exists: $dir")
            return
        }
        nasSmbVfs.mkdirs(hit.mountName, hit.rel)
    }

    override fun atomicMove(
        source: Path,
        target: Path,
    ) {
        val srcHit = nasHitOrNull(source)
        val dstHit = nasHitOrNull(target)
        if (srcHit == null && dstHit == null) return delegate.atomicMove(source, target)

        if (srcHit != null && dstHit != null && srcHit.mountName.equals(dstHit.mountName, ignoreCase = true)) {
            nasSmbVfs.move(srcHit.mountName, fromRelPath = srcHit.rel, toRelPath = dstHit.rel, overwrite = true)
            return
        }

        // Fallback: best-effort copy+delete across backends.
        val data = read(source) { readByteArray() }
        sink(target, mustCreate = false).buffer().use { it.write(data) }
        delete(source, mustExist = false)
    }

    override fun delete(
        path: Path,
        mustExist: Boolean,
    ) {
        val hit = nasHitOrNull(path)
        if (hit == null) return delegate.delete(path, mustExist)

        val existing = nasSmbVfs.metadataOrNull(hit.mountName, hit.rel)
        if (existing == null) {
            if (mustExist) throw IOException("Not found: $path")
            return
        }
        nasSmbVfs.delete(hit.mountName, hit.rel, recursive = existing.isDirectory)
    }

    override fun createSymlink(
        source: Path,
        target: Path,
    ) {
        val srcHit = nasHitOrNull(source)
        val dstHit = nasHitOrNull(target)
        if (srcHit != null || dstHit != null) throw IOException("Symlinks are not supported under nas_smb")
        delegate.createSymlink(source, target)
    }

    private class InMemoryNasFileHandle(
        readOnly: Boolean,
        private val readBytes: () -> ByteArray,
        private val writeBytes: ((ByteArray) -> Unit)?,
    ) : okio.FileHandle(readOnly.not()) {
        private var buf: ByteArray? = null
        private var dirty: Boolean = false

        private fun ensureLoaded(): ByteArray {
            val existing = buf
            if (existing != null) return existing
            val loaded = readBytes()
            buf = loaded
            return loaded
        }

        override fun protectedRead(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int,
        ): Int {
            val data = ensureLoaded()
            if (fileOffset >= data.size) return -1
            val start = fileOffset.toInt()
            val n = minOf(byteCount, data.size - start)
            System.arraycopy(data, start, array, arrayOffset, n)
            return n
        }

        override fun protectedWrite(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int,
        ) {
            val cur = ensureLoaded()
            val start = fileOffset.toInt()
            val need = start + byteCount
            val next = if (need <= cur.size) cur else cur.copyOf(need)
            System.arraycopy(array, arrayOffset, next, start, byteCount)
            buf = next
            dirty = true
        }

        override fun protectedFlush() {
            val w = writeBytes ?: return
            if (!dirty) return
            w(buf ?: ByteArray(0))
            dirty = false
        }

        override fun protectedResize(size: Long) {
            val s = size.coerceAtLeast(0L).toInt()
            val cur = ensureLoaded()
            buf = cur.copyOf(s)
            dirty = true
        }

        override fun protectedSize(): Long {
            return ensureLoaded().size.toLong()
        }

        override fun protectedClose() {
            try {
                protectedFlush()
            } catch (_: NasSmbVfsException) {
            } finally {
                buf = null
            }
        }
    }
}
