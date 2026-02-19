package com.lsl.kotlin_agent_app.agent.vfs.nas_smb

class NasSmbVfs(
    private val mountsProvider: NasSmbMountsProvider,
    private val client: NasSmbClient,
) {
    fun mountsByName(): Map<String, NasSmbMountConfig> = mountsProvider.mountsByName()

    fun listDir(
        mountName: String,
        relDir: String,
    ): List<NasSmbDirEntry> {
        val mount = resolveMount(mountName)
        val remoteDir = toRemotePath(mount, relDir, expectDir = true)
        return client.listDir(mount, remoteDir)
    }

    fun metadataOrNull(
        mountName: String,
        relPath: String,
    ): NasSmbMetadata? {
        val mount = resolveMount(mountName)
        val remotePath = toRemotePath(mount, relPath, expectDir = false)
        return client.metadataOrNull(mount, remotePath)
    }

    fun readTextFile(
        mountName: String,
        relPath: String,
        maxBytes: Long,
    ): String {
        val mount = resolveMount(mountName)
        val remotePath = toRemotePath(mount, relPath, expectDir = false)
        val bytes = client.readBytes(mount, remotePath, maxBytes = maxBytes)
        return bytes.toString(Charsets.UTF_8)
    }

    fun writeTextFile(
        mountName: String,
        relPath: String,
        content: String,
    ) {
        val mount = resolveMount(mountName)
        if (mount.readOnly) throw NasSmbVfsException(NasSmbErrorCode.PermissionDenied, "Mount is read-only: ${mount.mountName}")
        val remotePath = toRemotePath(mount, relPath, expectDir = false)
        client.writeBytes(mount, remotePath, content.toByteArray(Charsets.UTF_8))
    }

    fun mkdirs(
        mountName: String,
        relDir: String,
    ) {
        val mount = resolveMount(mountName)
        if (mount.readOnly) throw NasSmbVfsException(NasSmbErrorCode.PermissionDenied, "Mount is read-only: ${mount.mountName}")
        val remoteDir = toRemotePath(mount, relDir, expectDir = true)
        client.mkdirs(mount, remoteDir)
    }

    fun delete(
        mountName: String,
        relPath: String,
        recursive: Boolean,
    ) {
        val mount = resolveMount(mountName)
        if (mount.readOnly) throw NasSmbVfsException(NasSmbErrorCode.PermissionDenied, "Mount is read-only: ${mount.mountName}")
        val remotePath = toRemotePath(mount, relPath, expectDir = false)
        client.delete(mount, remotePath, recursive = recursive)
    }

    fun move(
        mountName: String,
        fromRelPath: String,
        toRelPath: String,
        overwrite: Boolean,
    ) {
        val mount = resolveMount(mountName)
        if (mount.readOnly) throw NasSmbVfsException(NasSmbErrorCode.PermissionDenied, "Mount is read-only: ${mount.mountName}")
        val fromRemote = toRemotePath(mount, fromRelPath, expectDir = false)
        val toRemote = toRemotePath(mount, toRelPath, expectDir = false)
        client.move(mount, fromRemotePath = fromRemote, toRemotePath = toRemote, overwrite = overwrite)
    }

    private fun resolveMount(mountName: String): NasSmbMountConfig {
        val key = mountName.trim().lowercase()
        val mount = mountsProvider.mountsByName()[key]
        return mount ?: throw NasSmbVfsException(NasSmbErrorCode.InvalidConfig, "Unknown mount: '$mountName'")
    }

    private fun toRemotePath(
        mount: NasSmbMountConfig,
        rel: String,
        expectDir: Boolean,
    ): String {
        val rel2 = normalizeRel(rel, allowEmpty = true)
        val base = mount.remoteDir.trim().trim('/').ifBlank { "" }
        val combined =
            when {
                base.isBlank() && rel2.isBlank() -> ""
                base.isBlank() -> rel2
                rel2.isBlank() -> base
                else -> "$base/$rel2"
            }
        if (!expectDir && combined.isBlank()) {
            throw NasSmbVfsException(NasSmbErrorCode.InvalidConfig, "Refusing to treat mount root as a file: ${mount.mountName}")
        }
        return combined
    }

    private fun normalizeRel(
        rel: String,
        allowEmpty: Boolean,
    ): String {
        val raw = rel.replace('\\', '/').trim().trimStart('/').trimEnd('/')
        if (raw.isBlank()) return if (allowEmpty) "" else throw NasSmbVfsException(NasSmbErrorCode.InvalidConfig, "Empty path")
        val segs = raw.split('/').filter { it.isNotBlank() }
        if (segs.any { it == "." || it == ".." }) throw NasSmbVfsException(NasSmbErrorCode.InvalidConfig, "Path traversal is not allowed")
        return segs.joinToString("/")
    }
}

