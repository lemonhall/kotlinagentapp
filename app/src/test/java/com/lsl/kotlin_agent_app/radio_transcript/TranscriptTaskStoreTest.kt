package com.lsl.kotlin_agent_app.radio_transcript

import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptTaskStoreTest {

    @Test
    fun createTask_writesTaskFiles_andUpdatesIndex() {
        val context = RuntimeEnvironment.getApplication()
        val ws = AgentsWorkspace(context)
        ws.ensureInitialized()

        val store = TranscriptTaskStore(ws)

        val sessionId = "rec_20260220_010203_abc123"
        ws.mkdir(".agents/workspace/radio_recordings/$sessionId")

        store.ensureSessionRoot(sessionId)
        assertTrue(ws.exists(RadioTranscriptPaths.tasksIndexJson(sessionId)))

        val taskId = "tx_20260220_0103_x1y2z3"
        val created = store.createTask(sessionId = sessionId, taskId = taskId, sourceLanguage = "ja", totalChunks = 12)
        assertEquals(taskId, created.taskId)
        assertEquals(sessionId, created.sessionId)
        assertEquals("pending", created.state)
        assertEquals(12, created.totalChunks)

        val taskJsonPath = RadioTranscriptPaths.taskJson(sessionId, taskId)
        val taskStatusPath = RadioTranscriptPaths.taskStatusMd(sessionId, taskId)
        assertTrue("task json should exist: $taskJsonPath", ws.exists(taskJsonPath))
        assertTrue("task status should exist: $taskStatusPath", ws.exists(taskStatusPath))

        val raw = ws.readTextFile(taskJsonPath, maxBytes = 256 * 1024)
        val parsed = TranscriptTaskV1.parse(raw)
        assertEquals(taskId, parsed.taskId)
        assertEquals("ja", parsed.sourceLanguage)

        val idxRaw = ws.readTextFile(RadioTranscriptPaths.tasksIndexJson(sessionId), maxBytes = 256 * 1024)
        val idx = TranscriptTasksIndexV1.parse(idxRaw)
        assertTrue(idx.tasks.any { it.taskId == taskId })

        val taskFile = File(context.filesDir, taskJsonPath)
        assertTrue(taskFile.exists())
        assertTrue(taskFile.length() > 10)
    }

    @Test
    fun cancelTask_setsStateCancelled_andKeepsOutputs() {
        val context = RuntimeEnvironment.getApplication()
        val ws = AgentsWorkspace(context)
        ws.ensureInitialized()

        val store = TranscriptTaskStore(ws)
        val sessionId = "rec_20260220_010203_abc123"
        ws.mkdir(".agents/workspace/radio_recordings/$sessionId")
        store.ensureSessionRoot(sessionId)

        val taskId = "tx_20260220_0103_x1y2z3"
        store.createTask(sessionId = sessionId, taskId = taskId, sourceLanguage = "ja", totalChunks = 1)

        // Seed a completed chunk output file (must be kept).
        val outPath = RadioTranscriptPaths.chunkTranscriptJson(sessionId, taskId, chunkIndex = 1)
        ws.writeTextFile(outPath, "{ \"ok\": true }\n")

        val cancelled = store.cancelTask(sessionId, taskId)
        assertEquals("cancelled", cancelled.state)

        val raw = ws.readTextFile(RadioTranscriptPaths.taskJson(sessionId, taskId), maxBytes = 256 * 1024)
        val parsed = TranscriptTaskV1.parse(raw)
        assertEquals("cancelled", parsed.state)

        assertTrue("outputs should be kept on cancel", ws.exists(outPath))
        assertNotNull(cancelled.updatedAt)
    }
}

