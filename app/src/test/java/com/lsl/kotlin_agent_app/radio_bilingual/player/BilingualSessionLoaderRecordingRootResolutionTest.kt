package com.lsl.kotlin_agent_app.radio_bilingual.player

import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BilingualSessionLoaderRecordingRootResolutionTest {

    @Test
    fun load_acceptsSessionUnderWorkspaceRecordingsRoot() {
        val context = RuntimeEnvironment.getApplication()
        val ws = AgentsWorkspace(context)
        ws.ensureInitialized()

        val sessionId = "rec_20260220_mic_bilingual_loader"
        ws.mkdir(".agents/workspace/recordings/$sessionId")
        ws.writeTextFile(".agents/workspace/recordings/$sessionId/_meta.json", "{}\n")
        ws.writeTextFile(".agents/workspace/recordings/$sessionId/chunk_001.ogg", "fake ogg\n")
        ws.mkdir(".agents/workspace/recordings/$sessionId/transcripts")
        ws.mkdir(".agents/workspace/recordings/$sessionId/translations")

        val loader =
            BilingualSessionLoader(
                workspace = ws,
                durationReader =
                    object : BilingualSessionLoader.ChunkDurationReader {
                        override fun readDurationMs(file: java.io.File): Long? = null
                    },
            )

        val loaded = loader.load(sessionId)
        assertEquals(sessionId, loaded.sessionId)
        assertEquals(1, loaded.chunks.size)
    }
}
