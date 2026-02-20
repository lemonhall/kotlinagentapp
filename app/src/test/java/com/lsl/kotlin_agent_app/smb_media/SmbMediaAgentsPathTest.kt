package com.lsl.kotlin_agent_app.smb_media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmbMediaAgentsPathTest {

    @Test
    fun parseNasSmbFilePath_extractsMountAndRelPath() {
        val p = SmbMediaAgentsPath.parseNasSmbFile(".agents/nas_smb/home/movies/a.mp4")
        assertEquals("home", p!!.mountName)
        assertEquals("movies/a.mp4", p.relPath)
    }

    @Test
    fun parseNasSmbFilePath_rejectsSecretsAndMountMeta() {
        assertNull(SmbMediaAgentsPath.parseNasSmbFile(".agents/nas_smb/secrets/.env"))
        assertNull(SmbMediaAgentsPath.parseNasSmbFile(".agents/nas_smb/home/.mount.json"))
    }
}

