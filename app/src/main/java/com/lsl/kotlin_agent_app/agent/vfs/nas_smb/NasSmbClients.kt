package com.lsl.kotlin_agent_app.agent.vfs.nas_smb

object NasSmbClients {
    val disabled: NasSmbClient =
        object : NasSmbClient {
            override fun listDir(
                mount: NasSmbMountConfig,
                remoteDir: String,
            ): List<NasSmbDirEntry> {
                throw NasSmbVfsException(NasSmbErrorCode.HostUnreachable, "SMB client is disabled in this build")
            }

            override fun metadataOrNull(
                mount: NasSmbMountConfig,
                remotePath: String,
            ): NasSmbMetadata? = null

            override fun readBytes(
                mount: NasSmbMountConfig,
                remotePath: String,
                maxBytes: Long,
            ): ByteArray {
                throw NasSmbVfsException(NasSmbErrorCode.HostUnreachable, "SMB client is disabled in this build")
            }

            override fun writeBytes(
                mount: NasSmbMountConfig,
                remotePath: String,
                bytes: ByteArray,
            ) {
                throw NasSmbVfsException(NasSmbErrorCode.HostUnreachable, "SMB client is disabled in this build")
            }

            override fun mkdirs(
                mount: NasSmbMountConfig,
                remoteDir: String,
            ) {
                throw NasSmbVfsException(NasSmbErrorCode.HostUnreachable, "SMB client is disabled in this build")
            }

            override fun delete(
                mount: NasSmbMountConfig,
                remotePath: String,
                recursive: Boolean,
            ) {
                throw NasSmbVfsException(NasSmbErrorCode.HostUnreachable, "SMB client is disabled in this build")
            }

            override fun move(
                mount: NasSmbMountConfig,
                fromRemotePath: String,
                toRemotePath: String,
                overwrite: Boolean,
            ) {
                throw NasSmbVfsException(NasSmbErrorCode.HostUnreachable, "SMB client is disabled in this build")
            }

            override fun copy(
                mount: NasSmbMountConfig,
                fromRemotePath: String,
                toRemotePath: String,
                overwrite: Boolean,
            ) {
                throw NasSmbVfsException(NasSmbErrorCode.HostUnreachable, "SMB client is disabled in this build")
            }
        }
}
