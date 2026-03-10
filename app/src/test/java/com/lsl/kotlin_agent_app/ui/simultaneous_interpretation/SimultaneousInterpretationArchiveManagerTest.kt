package com.lsl.kotlin_agent_app.ui.simultaneous_interpretation

import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimultaneousInterpretationArchiveManagerTest {
    @Test
    fun startNewSession_createsChineseNamedArchiveDir() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, ".agents/workspace/simultaneous_interpretation").deleteRecursively()
        val manager =
            SimultaneousInterpretationArchiveManager(
                appContext = context,
                nowProvider = {
                    ZonedDateTime.of(2026, 3, 9, 22, 1, 0, 0, ZoneId.of("Asia/Shanghai"))
                },
            )

        val rel = manager.startNewSession(targetLanguageCode = "en", targetLanguageLabel = "英语")

        assertEquals(".agents/workspace/simultaneous_interpretation/2026年03月09日 晚22点01分", rel)
        assertTrue(File(context.filesDir, rel).isDirectory)
    }

    @Test
    fun appendArtifactsAndFinishSession_persistsAudioTextAndWave() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, ".agents/workspace/simultaneous_interpretation").deleteRecursively()
        val manager =
            SimultaneousInterpretationArchiveManager(
                appContext = context,
                nowProvider = {
                    ZonedDateTime.of(2026, 3, 9, 22, 1, 0, 0, ZoneId.of("Asia/Shanghai"))
                },
            )

        val rel = manager.startNewSession(targetLanguageCode = "en", targetLanguageLabel = "英语")
        val dir = File(context.filesDir, rel)

        manager.appendEvent("test.event", "ok")
        manager.appendInputAudio(byteArrayOf(1, 2, 3, 4))
        manager.appendOutputAudio(byteArrayOf(5, 6, 7, 8))
        manager.appendSegment(sourceText = "你好", translatedText = "Hello")
        manager.finishSession()

        assertTrue(File(dir, "meta.json").isFile)
        assertTrue(File(dir, "events.jsonl").readText(Charsets.UTF_8).contains("test.event"))
        assertTrue(File(dir, "segments.jsonl").readText(Charsets.UTF_8).contains("translatedText"))
        assertTrue(File(dir, "input_audio.pcm").readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertTrue(File(dir, "source.md").readText(Charsets.UTF_8).contains("你好"))
        assertTrue(File(dir, "translation.md").readText(Charsets.UTF_8).contains("Hello"))

        val wav = File(dir, "translated_audio.wav")
        assertTrue(wav.isFile)
        val header = wav.readBytes().take(4).toByteArray()
        assertTrue(header.contentEquals("RIFF".toByteArray(Charsets.US_ASCII)))
        assertFalse(File(dir, "translated_audio.pcm").exists())
        assertNotEquals(0L, wav.length())
    }
}
