package com.lsl.kotlin_agent_app.agent.vfs.nas_smb

import com.lsl.kotlin_agent_app.agent.tools.mail.DotEnv

class NasSmbMountConfigLoader {
    fun loadFromEnvText(envText: String): List<NasSmbMountConfig> {
        val map = DotEnv.parse(envText)
        return loadFromMap(map)
    }

    fun loadFromMap(values: Map<String, String>): List<NasSmbMountConfig> {
        val mountsRaw = values["NAS_SMB_MOUNTS"].orEmpty().trim()
        if (mountsRaw.isBlank()) return emptyList()

        val ids =
            mountsRaw
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }

        val out = ArrayList<NasSmbMountConfig>(ids.size)
        for (rawId in ids) {
            val id = rawId.trim()
            val idUpper = id.uppercase()
            val idLower = id.lowercase()
            val prefix = "NAS_SMB_${idUpper}_"

            fun v(key: String): String? = values["$prefix$key"]?.trim()?.ifEmpty { null }

            val host = v("HOST") ?: throw NasSmbVfsException(NasSmbErrorCode.InvalidConfig, "Missing ${prefix}HOST for mount '$idLower'")
            val port =
                v("PORT")?.toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: 445
            val domain = v("DOMAIN")
            val share = v("SHARE") ?: throw NasSmbVfsException(NasSmbErrorCode.InvalidConfig, "Missing ${prefix}SHARE for mount '$idLower'")

            val guest = v("GUEST")?.toBooleanStrictOrNull() == true
            val username = v("USERNAME")
            val password = v("PASSWORD")
            if (!guest) {
                if (username.isNullOrBlank() || password.isNullOrBlank()) {
                    throw NasSmbVfsException(NasSmbErrorCode.MissingCredentials, "Missing credentials for mount '$idLower' (set ${prefix}USERNAME/${prefix}PASSWORD or ${prefix}GUEST=true)")
                }
            }

            val remoteDir = normalizeRemoteDir(v("REMOTE_DIR"))
            val mountName = (v("MOUNT_NAME") ?: idLower).trim()
            requireSafeMountName(mountName)

            val readOnly = v("READ_ONLY")?.toBooleanStrictOrNull() == true

            out.add(
                NasSmbMountConfig(
                    id = idLower,
                    mountName = mountName,
                    host = host,
                    port = port,
                    domain = domain,
                    username = username,
                    password = password,
                    share = share,
                    remoteDir = remoteDir,
                    guest = guest,
                    readOnly = readOnly,
                ),
            )
        }
        return out
    }

    private fun normalizeRemoteDir(raw: String?): String {
        val v = raw?.trim().orEmpty()
        if (v.isBlank() || v == "/") return ""
        val withSlash = if (v.startsWith("/")) v else "/$v"
        val trimmed = withSlash.trimEnd('/')
        val noLeading = trimmed.trimStart('/')
        if (noLeading.split('/').any { it == "." || it == ".." || it.isBlank() }) {
            throw NasSmbVfsException(NasSmbErrorCode.InvalidConfig, "Invalid remote dir (path traversal): '$raw'")
        }
        return noLeading
    }

    private fun requireSafeMountName(name: String) {
        val ok = Regex("^[a-z0-9][a-z0-9_-]{0,31}$").matches(name)
        if (!ok) throw NasSmbVfsException(NasSmbErrorCode.InvalidConfig, "Invalid mount name '$name' (expected: [a-z0-9][a-z0-9_-]{0,31})")
    }
}

