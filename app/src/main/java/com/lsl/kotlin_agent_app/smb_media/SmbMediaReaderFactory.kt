package com.lsl.kotlin_agent_app.smb_media

interface SmbMediaFileHandleFactory {
    fun open(
        mountName: String,
        relPath: String,
    ): SmbMediaFileHandle
}

class SmbMediaReaderFactory(
    private val fileHandleFactory: SmbMediaFileHandleFactory,
) {
    fun open(
        mountName: String,
        relPath: String,
    ): SmbRandomAccessReader {
        return SmbBackendRandomAccessReader(file = fileHandleFactory.open(mountName = mountName, relPath = relPath))
    }
}

