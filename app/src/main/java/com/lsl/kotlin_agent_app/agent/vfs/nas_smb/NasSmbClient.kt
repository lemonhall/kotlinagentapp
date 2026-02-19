package com.lsl.kotlin_agent_app.agent.vfs.nas_smb

interface NasSmbClient {
    fun listDir(
        mount: NasSmbMountConfig,
        remoteDir: String,
    ): List<NasSmbDirEntry>

    fun metadataOrNull(
        mount: NasSmbMountConfig,
        remotePath: String,
    ): NasSmbMetadata?

    fun readBytes(
        mount: NasSmbMountConfig,
        remotePath: String,
        maxBytes: Long,
    ): ByteArray

    fun writeBytes(
        mount: NasSmbMountConfig,
        remotePath: String,
        bytes: ByteArray,
    )

    fun mkdirs(
        mount: NasSmbMountConfig,
        remoteDir: String,
    )

    fun delete(
        mount: NasSmbMountConfig,
        remotePath: String,
        recursive: Boolean,
    )

    fun move(
        mount: NasSmbMountConfig,
        fromRemotePath: String,
        toRemotePath: String,
        overwrite: Boolean,
    )
}

