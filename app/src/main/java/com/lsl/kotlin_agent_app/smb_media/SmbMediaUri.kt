package com.lsl.kotlin_agent_app.smb_media

object SmbMediaUri {
    const val AUTHORITY: String = "com.lsl.kotlin_agent_app.smbmedia"

    fun build(
        token: String,
        displayName: String,
    ): String {
        val t = token.trim()
        require(t.isNotEmpty()) { "token is empty" }
        val safeName =
            displayName
                .trim()
                .ifBlank { "media" }
                .replace('/', '_')
                .replace('\\', '_')
        return "content://$AUTHORITY/v1/$t/$safeName"
    }
}

