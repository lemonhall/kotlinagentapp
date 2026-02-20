package com.lsl.kotlin_agent_app.smb_media

import com.lsl.kotlin_agent_app.BuildConfig

object SmbMediaUri {
    val AUTHORITY: String = BuildConfig.APPLICATION_ID + ".smbmedia"

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
