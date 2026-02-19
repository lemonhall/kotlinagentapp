package com.lsl.kotlin_agent_app.media

import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicPlayerControllerTest {

    @get:Rule
    val mainDispatcherRule = com.lsl.kotlin_agent_app.MainDispatcherRule(StandardTestDispatcher())

    private class FakeTransport : MusicTransport {
        var lastPlayed: MusicPlaybackRequest? = null
        var playing: Boolean = false
        var pos: Long = 0L
        var dur: Long? = null
        var vol: Float = 1.0f
        var transportListener: MusicTransportListener? = null

        override suspend fun connect() = Unit

        override suspend fun play(request: MusicPlaybackRequest) {
            lastPlayed = request
            playing = true
            pos = 0L
            dur = request.metadata.durationMs
        }

        override suspend fun pause() {
            playing = false
        }

        override suspend fun resume() {
            playing = true
        }

        override suspend fun stop() {
            playing = false
            lastPlayed = null
            pos = 0L
            dur = null
        }

        override suspend fun seekTo(positionMs: Long) {
            pos = positionMs
        }

        override fun currentPositionMs(): Long = pos

        override fun durationMs(): Long? = dur

        override fun isPlaying(): Boolean = playing

        override suspend fun setVolume(volume: Float) {
            vol = volume
        }

        override fun volume(): Float? = vol

        override fun setListener(listener: MusicTransportListener?) {
            this.transportListener = listener
        }
    }

    @Test
    fun playAgentsMp3_outsideMusics_isRejected() = runTest {
        val ctx = RuntimeEnvironment.getApplication()
        val transport = FakeTransport()
        val controller =
            MusicPlayerController(
                ctx,
                transport = transport,
                metadataReader = Mp3MetadataReader(Mp3MetadataExtractor { null }),
                ioDispatcher = kotlinx.coroutines.Dispatchers.Main,
            )
        try {
            controller.playAgentsMp3(".agents/workspace/inbox/a.mp3")
            assertEquals("仅允许播放 musics/ 目录下的 mp3", controller.state.value.errorMessage)
        } finally {
            controller.close()
        }
    }

    @Test
    fun playAgentsMp3_insideMusics_updatesState() = runTest {
        val ctx = RuntimeEnvironment.getApplication()
        val transport = FakeTransport()
        val controller =
            MusicPlayerController(
                ctx,
                transport = transport,
                metadataReader =
                    Mp3MetadataReader(
                        Mp3MetadataExtractor {
                            RawMp3Metadata(title = "Song", artist = "Me", durationMs = 5000L)
                        }
                    ),
                ioDispatcher = kotlinx.coroutines.Dispatchers.Main,
            )
        try {
            controller.playAgentsMp3(".agents/workspace/musics/a.mp3")
            testScheduler.runCurrent()
            assertEquals(".agents/workspace/musics/a.mp3", controller.state.value.agentsPath)
            assertEquals(false, controller.state.value.isLive)
            assertEquals("Song", controller.state.value.title)
            assertEquals("Me", controller.state.value.artist)
            assertEquals(true, controller.state.value.isPlaying)
        } finally {
            controller.close()
        }
    }

    @Test
    fun playAgentsRadio_outsideRadios_isRejected() = runTest {
        val ctx = RuntimeEnvironment.getApplication()
        val transport = FakeTransport()
        val controller =
            MusicPlayerController(
                ctx,
                transport = transport,
                metadataReader = Mp3MetadataReader(Mp3MetadataExtractor { null }),
                ioDispatcher = kotlinx.coroutines.Dispatchers.Main,
            )
        try {
            controller.playAgentsRadio(".agents/workspace/inbox/a.radio")
            assertEquals("仅允许播放 radios/ 目录下的 .radio", controller.state.value.errorMessage)
        } finally {
            controller.close()
        }
    }

    @Test
    fun playAgentsRadio_insideRadios_updatesState_andPlaysStreamUrl() = runTest {
        val ctx = RuntimeEnvironment.getApplication()
        val transport = FakeTransport()
        val controller =
            MusicPlayerController(
                ctx,
                transport = transport,
                metadataReader = Mp3MetadataReader(Mp3MetadataExtractor { null }),
                ioDispatcher = kotlinx.coroutines.Dispatchers.Main,
            )
        val path = ".agents/workspace/radios/CN__China/test.radio"
        val file = File(ctx.filesDir, path)
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"schema":"kotlin-agent-app/radio-station@v1","id":"radio-browser:uuid-123","name":"Station","streamUrl":"https://example.com/stream"}
            """.trimIndent(),
            Charsets.UTF_8
        )

        try {
            controller.playAgentsRadio(path)
            testScheduler.runCurrent()
            assertEquals(path, controller.state.value.agentsPath)
            assertEquals(true, controller.state.value.isLive)
            assertEquals("Station", controller.state.value.title)
            assertEquals(true, controller.state.value.isPlaying)
            assertEquals("https://example.com/stream", transport.lastPlayed?.uri)
            assertEquals(true, transport.lastPlayed?.isLive)
        } finally {
            controller.close()
        }
    }

    @Test
    fun playAgentsMp3Now_calledFromBackground_runsTransportOnMainImmediate() = runTest {
        val ctx = RuntimeEnvironment.getApplication()

        class RecordingTransport : MusicTransport {
            var lastPlayInterceptor: ContinuationInterceptor? = null
            override suspend fun connect() = Unit

            override suspend fun play(request: MusicPlaybackRequest) {
                lastPlayInterceptor = currentCoroutineContext()[ContinuationInterceptor]
            }

            override suspend fun pause() = Unit
            override suspend fun resume() = Unit
            override suspend fun stop() = Unit
            override suspend fun seekTo(positionMs: Long) = Unit
            override fun currentPositionMs(): Long = 0L
            override fun durationMs(): Long? = null
            override fun isPlaying(): Boolean = false
            override suspend fun setVolume(volume: Float) = Unit
            override fun volume(): Float? = null
            override fun setListener(listener: MusicTransportListener?) = Unit
        }

        val transport = RecordingTransport()
        val controller =
            MusicPlayerController(
                ctx,
                transport = transport,
                metadataReader = Mp3MetadataReader(Mp3MetadataExtractor { RawMp3Metadata(title = "Song", artist = "Me", durationMs = 5000L) }),
                ioDispatcher = Dispatchers.Main,
            )

        try {
            withContext(Dispatchers.Default) {
                controller.playAgentsMp3Now(".agents/workspace/musics/a.mp3")
            }

            assertSame(Dispatchers.Main.immediate, transport.lastPlayInterceptor)
        } finally {
            controller.close()
        }
    }
}
