package com.lsl.kotlin_agent_app.ui.instant_translation

import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstantTranslationArchiveManagerTest {
    @Test
    fun startNewSession_createsChineseNamedArchiveDir() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, ".agents/workspace/instant_translation").deleteRecursively()
        val manager =
            InstantTranslationArchiveManager(
                appContext = context,
                nowProvider = {
                    ZonedDateTime.of(2026, 3, 9, 22, 1, 0, 0, ZoneId.of("Asia/Shanghai"))
                },
            )

        val rel = manager.startNewSession(targetLanguageCode = "en", targetLanguageLabel = "\u82f1\u8bed", sampleRateHz = 16_000)

        assertEquals(".agents/workspace/instant_translation/2026\u5e7403\u670809\u65e5 \u665a22\u70b901\u5206", rel)
        assertTrue(File(context.filesDir, rel).isDirectory)
    }

    @Test
    fun appendAudioFrameAndWriteTurns_persistsArtifacts() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, ".agents/workspace/instant_translation").deleteRecursively()
        val manager =
            InstantTranslationArchiveManager(
                appContext = context,
                nowProvider = {
                    ZonedDateTime.of(2026, 3, 9, 22, 1, 0, 0, ZoneId.of("Asia/Shanghai"))
                },
            )

        val rel = manager.startNewSession(targetLanguageCode = "ja", targetLanguageLabel = "\u65e5\u672c\u8bed", sampleRateHz = 16_000)
        manager.appendAudioFrame(byteArrayOf(1, 2, 3, 4))
        manager.writeTurns(
            listOf(
                InstantTranslationTurn(
                    id = 1L,
                    sourceText = "\u4f60\u597d",
                    targetLanguageCode = "ja",
                    targetLanguageLabel = "\u65e5\u672c\u8bed",
                    translatedText = "\u3053\u3093\u306b\u3061\u306f",
                    isPending = false,
                ),
            ),
        )
        manager.finishSession()

        val dir = File(context.filesDir, rel)
        assertTrue(File(dir, "recording.pcm").readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertTrue(File(dir, "transcript.md").readText(Charsets.UTF_8).contains("\u3053\u3093\u306b\u3061\u306f"))
        val turnsJson = File(dir, "turns.json").readText(Charsets.UTF_8)
        assertTrue(turnsJson.contains("\"turns\": ["))
        assertTrue(turnsJson.contains("\"targetLanguageCode\": \"ja\""))
    }
}
