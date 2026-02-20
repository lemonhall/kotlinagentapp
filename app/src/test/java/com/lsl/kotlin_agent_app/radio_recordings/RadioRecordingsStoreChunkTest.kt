package com.lsl.kotlin_agent_app.radio_recordings

import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RadioRecordingsStoreChunkTest {

    @Test
    fun appendChunk_updatesMetaAndIndex_andIsIdempotent() {
        val context = RuntimeEnvironment.getApplication()
        val ws = AgentsWorkspace(context)
        ws.ensureInitialized()

        val store = RadioRecordingsStore(ws)
        store.ensureRoot()

        val sessionId = "rec_20260220_010203_abc123"
        ws.mkdir(RadioRecordingsPaths.sessionDir(sessionId))

        val meta =
            RecordingMetaV1(
                schema = RecordingMetaV1.SCHEMA_V1,
                sessionId = sessionId,
                station =
                    RecordingMetaV1.Station(
                        stationId = "radio-browser:test-1",
                        name = "Test Station",
                        radioFilePath = "workspace/radios/test1.radio",
                        streamUrl = "https://example.com/live",
                    ),
                chunkDurationMin = 10,
                outputFormat = "ogg_opus_64kbps",
                state = "pending",
                createdAt = "2026-02-20T10:00:00+08:00",
                updatedAt = "2026-02-20T10:00:00+08:00",
                chunks = emptyList(),
            )
        store.writeSessionMeta(sessionId, meta)

        val idx =
            RecordingsIndexV1(
                generatedAtSec = store.nowSec(),
                sessions =
                    listOf(
                        RecordingsIndexV1.SessionEntry(
                            sessionId = sessionId,
                            dir = sessionId,
                            stationName = "Test Station",
                            state = "pending",
                            startAt = meta.createdAt,
                            chunksCount = 0,
                        ),
                    ),
            )
        store.writeIndex(idx)

        store.appendChunk(sessionId, 1)
        val raw1 = ws.readTextFile(RadioRecordingsPaths.sessionMetaJson(sessionId), maxBytes = 256 * 1024)
        val parsed1 = RecordingMetaV1.parse(raw1)
        assertEquals("recording", parsed1.state)
        assertEquals(1, parsed1.chunks.size)
        assertEquals("chunk_001.ogg", parsed1.chunks[0].file)
        assertEquals(1, parsed1.chunks[0].index)

        val idxRaw1 = ws.readTextFile(RadioRecordingsPaths.ROOT_INDEX_JSON, maxBytes = 256 * 1024)
        val idxParsed1 = RecordingsIndexV1.parse(idxRaw1)
        assertEquals(1, idxParsed1.sessions.size)
        assertEquals("recording", idxParsed1.sessions[0].state)
        assertEquals(1, idxParsed1.sessions[0].chunksCount)

        // Idempotent: appending same chunk again should not duplicate.
        store.appendChunk(sessionId, 1)
        val raw2 = ws.readTextFile(RadioRecordingsPaths.sessionMetaJson(sessionId), maxBytes = 256 * 1024)
        val parsed2 = RecordingMetaV1.parse(raw2)
        assertEquals(1, parsed2.chunks.size)

        // Atomic-ish: file should be non-empty and parseable.
        val metaFile = File(context.filesDir, RadioRecordingsPaths.sessionMetaJson(sessionId))
        assertTrue(metaFile.exists())
        assertTrue(metaFile.length() > 10)
    }
}

