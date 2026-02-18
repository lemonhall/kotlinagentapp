package com.lsl.kotlin_agent_app.media

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class Mp3MetadataReaderTest {

    @Test
    fun readBestEffort_whenExtractorReturnsNull_fallsBackToFileName() {
        val reader = Mp3MetadataReader(extractor = Mp3MetadataExtractor { null })
        val md = reader.readBestEffort(File("hello_world.mp3"))
        assertEquals("hello_world", md.title)
        assertEquals(null, md.artist)
        assertEquals(null, md.album)
        assertEquals(null, md.durationMs)
    }

    @Test
    fun readBestEffort_whenExtractorThrows_doesNotCrashAndFallsBack() {
        val reader =
            Mp3MetadataReader(
                extractor =
                    Mp3MetadataExtractor {
                        error("boom")
                    }
            )
        val md = reader.readBestEffort(File("x.mp3"))
        assertEquals("x", md.title)
    }

    @Test
    fun readBestEffort_whenExtractorReturnsTitle_usesIt() {
        val reader =
            Mp3MetadataReader(
                extractor =
                    Mp3MetadataExtractor {
                        RawMp3Metadata(
                            title = "T",
                            artist = "A",
                            album = "B",
                            durationMs = 1234L,
                        )
                    }
            )
        val md = reader.readBestEffort(File("ignored.mp3"))
        assertEquals("T", md.title)
        assertEquals("A", md.artist)
        assertEquals("B", md.album)
        assertEquals(1234L, md.durationMs)
    }
}

