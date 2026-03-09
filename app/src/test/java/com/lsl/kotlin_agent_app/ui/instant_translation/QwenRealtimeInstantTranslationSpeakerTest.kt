package com.lsl.kotlin_agent_app.ui.instant_translation

import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QwenRealtimeInstantTranslationSpeakerTest {
    @Test
    fun speak_streamsRemoteAudio_playsItAndArchivesWaveFile() = runTest {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, ".agents/workspace/instant_translation").deleteRecursively()
        val archiveManager =
            InstantTranslationArchiveManager(
                appContext = context,
                nowProvider = {
                    ZonedDateTime.of(2026, 3, 9, 22, 1, 0, 0, ZoneId.of("Asia/Shanghai"))
                },
            )
        val sessionPath =
            archiveManager.startNewSession(
                targetLanguageCode = "fr",
                targetLanguageLabel = "法语",
                sampleRateHz = 16_000,
            )
        val client =
            FakeQwenRealtimeTtsClient(
                chunks = listOf(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8)),
            )
        val player = FakeInstantTranslationAudioPlayer()
        val hookCalls = mutableListOf<String>()
        val speaker =
            QwenRealtimeInstantTranslationSpeaker(
                apiKeyProvider = { "test-key" },
                archiveManager = archiveManager,
                ttsClient = client,
                playerFactory = { player },
                playbackHooks =
                    object : InstantTranslationPlaybackHooks {
                        override fun beforePlayback() {
                            hookCalls += "before"
                        }

                        override fun afterPlayback() {
                            hookCalls += "after"
                        }
                    },
            )

        speaker.speak(
            InstantTranslationTurn(
                id = 1L,
                sourceText = "你好",
                targetLanguageCode = "fr",
                targetLanguageLabel = "法语",
                translatedText = "bonjour",
                isPending = false,
                archiveSessionRelativePath = sessionPath,
            ),
        )

        assertEquals(1, client.requests.size)
        assertEquals("bonjour", client.requests.single().text)
        assertEquals("fr", client.requests.single().languageCode)
        assertEquals(listOf("before", "after"), hookCalls)
        assertTrue(player.awaitedCompletion)
        assertTrue(player.closed)
        assertTrue(player.playedBytes.contentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))

        val ttsTextFile = File(context.filesDir, "$sessionPath/tts/turn-0001.txt")
        val ttsWaveFile = File(context.filesDir, "$sessionPath/tts/turn-0001.wav")
        assertEquals("bonjour\n", ttsTextFile.readText(Charsets.UTF_8))
        assertTrue(ttsWaveFile.isFile)
        assertEquals("RIFF", ttsWaveFile.readBytes().copyOfRange(0, 4).toString(Charsets.US_ASCII))
    }

    private class FakeQwenRealtimeTtsClient(
        private val chunks: List<ByteArray>,
    ) : QwenRealtimeTtsClient {
        val requests = mutableListOf<QwenRealtimeTtsRequest>()

        override suspend fun synthesize(
            request: QwenRealtimeTtsRequest,
            onAudioChunk: (ByteArray) -> Unit,
        ) {
            requests += request
            chunks.forEach(onAudioChunk)
        }
    }

    private class FakeInstantTranslationAudioPlayer : InstantTranslationAudioPlayer {
        private val buffer = ArrayList<Byte>()
        var awaitedCompletion: Boolean = false
        var closed: Boolean = false

        val playedBytes: ByteArray
            get() = buffer.toByteArray()

        override fun writePcm(bytes: ByteArray) {
            bytes.forEach { buffer += it }
        }

        override fun awaitPlaybackComplete() {
            awaitedCompletion = true
        }

        override fun close() {
            closed = true
        }
    }
}
