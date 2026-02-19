package com.lsl.kotlin_agent_app.listening_history

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListeningHistoryStoreTest {

    @Test
    fun appendEvent_doesNothing_whenDisabled() {
        val ctx = RuntimeEnvironment.getApplication()
        val store = ListeningHistoryStore(ctx)
        store.setEnabled(false)

        store.appendEvent(
            source = "radio",
            action = "play",
            item = buildJsonObject { put("radioFilePath", JsonPrimitive("workspace/radios/test.radio")) },
            userInitiated = true,
        )

        assertFalse(store.eventsFile().exists())
    }

    @Test
    fun clear_requiresConfirmFlag() {
        val ctx = RuntimeEnvironment.getApplication()
        val store = ListeningHistoryStore(ctx)
        store.setEnabled(true)

        store.appendEvent(
            source = "music",
            action = "play",
            item = buildJsonObject { put("path", JsonPrimitive("workspace/musics/demo.mp3")) },
            userInitiated = true,
        )

        val f = store.eventsFile()
        assertTrue(f.exists())

        assertFalse(store.clear(confirm = false))
        assertTrue("file should remain when confirm=false", f.exists() && f.readText(Charsets.UTF_8).isNotBlank())

        assertTrue(store.clear(confirm = true))
        assertTrue("file should be deleted or empty after clear(confirm=true)", !f.exists() || f.readText(Charsets.UTF_8).isBlank())
    }
}
