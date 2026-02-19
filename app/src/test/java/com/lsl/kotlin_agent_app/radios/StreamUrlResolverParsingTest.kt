package com.lsl.kotlin_agent_app.radios

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUrlResolverParsingTest {

    @Test
    fun parsePlsCandidates_extractsFileN_andResolvesRelative() {
        val body =
            """
            [playlist]
            NumberOfEntries=2
            File1=https://example.com/live1.mp3
            Title1=Example 1
            File2=/relative/live2.aac
            """.trimIndent()

        val base = "https://example.com/radio/list.pls".toHttpUrl()
        val out = StreamUrlResolver.parsePlsCandidates(body, base)
        assertEquals(listOf("https://example.com/live1.mp3", "https://example.com/relative/live2.aac"), out)
    }

    @Test
    fun parseM3uCandidates_extractsHttpLines_andIgnoresComments() {
        val body =
            """
            #EXTM3U
            #EXTINF:-1, Station
            https://example.com/live1.mp3
            https://example.com/live1.mp3
            http://example.com/live2.aac
            """.trimIndent()

        val base = "https://example.com/playlist.m3u".toHttpUrl()
        val parsed = StreamUrlResolver.parseM3uCandidates(body, base)
        assertEquals(false, parsed.isHls)
        assertEquals(listOf("https://example.com/live1.mp3", "http://example.com/live2.aac"), parsed.candidates)
    }

    @Test
    fun parseM3uCandidates_detectsHls_andReturnsNoCandidates() {
        val body =
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            low/playlist.m3u8
            """.trimIndent()

        val base = "https://example.com/master.m3u8".toHttpUrl()
        val parsed = StreamUrlResolver.parseM3uCandidates(body, base)
        assertTrue(parsed.isHls)
        assertEquals(emptyList<String>(), parsed.candidates)
    }
}

