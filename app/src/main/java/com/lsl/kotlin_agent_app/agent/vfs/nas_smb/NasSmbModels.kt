package com.lsl.kotlin_agent_app.agent.vfs.nas_smb

data class NasSmbDirEntry(
    val name: String,
    val isDirectory: Boolean,
)

data class NasSmbMetadata(
    val isRegularFile: Boolean,
    val isDirectory: Boolean,
    val size: Long? = null,
    val lastModifiedAtMillis: Long? = null,
)

data class NasSmbMountConfig(
    val id: String,
    val mountName: String,
    val host: String,
    val port: Int = 445,
    val domain: String? = null,
    val username: String? = null,
    val password: String? = null,
    val share: String,
    /**
     * Share-relative directory, '/'-separated, without a leading slash.
     * Root is "".
     */
    val remoteDir: String = "",
    val guest: Boolean = false,
    val readOnly: Boolean = false,
) {
    override fun toString(): String {
        return "NasSmbMountConfig(id=$id,mountName=$mountName,share=$share,remoteDir=$remoteDir,guest=$guest,readOnly=$readOnly)"
    }
}

