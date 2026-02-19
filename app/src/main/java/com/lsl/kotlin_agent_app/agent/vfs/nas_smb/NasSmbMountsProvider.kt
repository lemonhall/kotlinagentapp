package com.lsl.kotlin_agent_app.agent.vfs.nas_smb

import com.lsl.kotlin_agent_app.agent.tools.mail.DotEnv
import java.io.File
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

interface NasSmbMountsProvider {
    fun mountsByName(): Map<String, NasSmbMountConfig>
}

class FileNasSmbMountsProvider(
    private val envFile: File,
    private val loader: NasSmbMountConfigLoader = NasSmbMountConfigLoader(),
) : NasSmbMountsProvider {
    override fun mountsByName(): Map<String, NasSmbMountConfig> {
        val values = DotEnv.load(envFile)
        val mounts = loader.loadFromMap(values)
        return mounts.associateBy { it.mountName.lowercase() }
    }
}

class OkioNasSmbMountsProvider(
    private val fileSystem: FileSystem,
    private val agentsRoot: Path,
    private val loader: NasSmbMountConfigLoader = NasSmbMountConfigLoader(),
) : NasSmbMountsProvider {
    override fun mountsByName(): Map<String, NasSmbMountConfig> {
        val envPath = agentsRoot.resolve("nas_smb/secrets/.env".toPath())
        val text =
            try {
                fileSystem.read(envPath) { readUtf8() }
            } catch (_: Throwable) {
                ""
            }
        if (text.isBlank()) return emptyMap()
        val mounts = loader.loadFromEnvText(text)
        return mounts.associateBy { it.mountName.lowercase() }
    }
}

