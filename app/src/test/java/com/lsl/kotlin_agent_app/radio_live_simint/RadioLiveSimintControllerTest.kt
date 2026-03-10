package com.lsl.kotlin_agent_app.radio_live_simint

import com.lsl.kotlin_agent_app.ui.simultaneous_interpretation.AliyunLiveTranslateClient
import com.lsl.kotlin_agent_app.ui.simultaneous_interpretation.AliyunLiveTranslateClientListener
import com.lsl.kotlin_agent_app.ui.simultaneous_interpretation.LiveTranslateAudioInputSource
import com.lsl.kotlin_agent_app.ui.simultaneous_interpretation.LiveTranslateAudioPlayer
import com.lsl.kotlin_agent_app.ui.simultaneous_interpretation.LiveTranslateSessionStartConfig
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RadioLiveSimintControllerTest {

    @get:Rule
    val mainDispatcherRule = com.lsl.kotlin_agent_app.MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun start_withoutApiKey_setsError() = runTest {
        val ctx = RuntimeEnvironment.getApplication()
        val controller =
            RadioLiveSimintController(
                context = ctx,
                apiKeyProvider = { "" },
                ioDispatcher = StandardTestDispatcher(testScheduler),
                clientFactory = { FakeClient() },
                inputSourceFactory = { FakeInputSource() },
                audioPlayerFactory = { FakeAudioPlayer() },
            )

        controller.start(
            agentsRadioPath = ".agents/workspace/radios/demo.radio",
            targetLanguageCode = "zh",
            targetLanguageLabel = "中文",
        )

        assertFalse(controller.state.value.isRunning)
        assertEquals("请先在 Settings 的 Voice Input 中填写 DASHSCOPE_API_KEY", controller.state.value.errorMessage)
    }

    @Test
    fun start_validRadioPath_entersRunningState_andWritesAudio() = runTest {
        val ctx = RuntimeEnvironment.getApplication()
        val radioPath = ".agents/workspace/radios/demo.radio"
        val file = File(ctx.filesDir, radioPath)
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {
              "schema": "kotlin-agent-app/radio-station@v1",
              "id": "demo",
              "name": "Demo Radio",
              "streamUrl": "https://example.com/stream"
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val client = FakeClient()
        val audio = FakeAudioPlayer()
        val controller =
            RadioLiveSimintController(
                context = ctx,
                apiKeyProvider = { "sk-test" },
                ioDispatcher = StandardTestDispatcher(testScheduler),
                clientFactory = { client },
                inputSourceFactory = { FakeInputSource() },
                audioPlayerFactory = { audio },
            )

        controller.start(
            agentsRadioPath = radioPath,
            targetLanguageCode = "zh",
            targetLanguageLabel = "中文",
        )

        advanceUntilIdle()
        assertTrue(controller.state.value.isRunning)

        val l = client.listener
        assertNotNull(l)
        l?.onAudioDelta(byteArrayOf(1, 2, 3))

        assertEquals(1, audio.frames.size)
        assertEquals(3, audio.frames.single().size)

        controller.stop()
        advanceUntilIdle()
        assertFalse(controller.state.value.isRunning)
    }
}

private class FakeClient : AliyunLiveTranslateClient {
    var listener: AliyunLiveTranslateClientListener? = null
    var stopped: Boolean = false

    override suspend fun start(
        config: LiveTranslateSessionStartConfig,
        listener: AliyunLiveTranslateClientListener,
    ) {
        this.listener = listener
    }

    override fun appendAudioFrame(bytes: ByteArray) = Unit

    override fun stop() {
        stopped = true
    }
}

private class FakeInputSource : LiveTranslateAudioInputSource {
    var started: Boolean = false
    var stopped: Boolean = false

    override fun start(
        onStarted: () -> Unit,
        onAudioFrame: (ByteArray) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        started = true
        onStarted()
    }

    override fun stop() {
        stopped = true
    }
}

private class FakeAudioPlayer : LiveTranslateAudioPlayer {
    val frames = mutableListOf<ByteArray>()

    override fun writePcm(bytes: ByteArray) {
        frames.add(bytes)
    }

    override fun close() = Unit
}

