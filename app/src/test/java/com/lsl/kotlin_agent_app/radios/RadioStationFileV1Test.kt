package com.lsl.kotlin_agent_app.radios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioStationFileV1Test {

    @Test
    fun parse_validRadioFile_ok() {
        val raw =
            """
            {
              "schema": "kotlin-agent-app/radio-station@v1",
              "id": "radio-browser:uuid-123",
              "name": "BBC World Service",
              "streamUrl": "https://example.com/stream",
              "votes": 42,
              "codec": "MP3",
              "bitrateKbps": 128
            }
            """.trimIndent()

        val st = RadioStationFileV1.parse(raw)
        assertEquals("radio-browser:uuid-123", st.id)
        assertEquals("BBC World Service", st.name)
        assertEquals("https://example.com/stream", st.streamUrl)
        assertEquals(42, st.votes)
    }

    @Test
    fun parse_missingRequiredField_throws() {
        val raw = """{"schema":"kotlin-agent-app/radio-station@v1","id":"x","name":"n"}"""
        val ex =
            runCatching { RadioStationFileV1.parse(raw) }
                .exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
        assertTrue(ex?.message.orEmpty().contains("streamUrl"))
    }

    @Test
    fun stationFileName_isPathSafe_andEndsWithRadio() {
        val name = RadioPathNaming.stationFileName(stationName = "A/B:C*?<>|", stationUuid = "uuid-1234-5678")
        assertTrue(name.endsWith(".radio"))
        assertTrue(!name.contains("/"))
        assertTrue(!name.contains("\\"))
        assertTrue(!name.contains(":"))
        assertTrue(name.contains("__"))
    }
}

