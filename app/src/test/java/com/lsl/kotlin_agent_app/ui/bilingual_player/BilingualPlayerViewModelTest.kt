package com.lsl.kotlin_agent_app.ui.bilingual_player

import com.lsl.kotlin_agent_app.MainDispatcherRule
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.radio_bilingual.player.BilingualSessionLoader
import com.lsl.kotlin_agent_app.radio_bilingual.player.SessionPlayerController
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BilingualPlayerViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private class FakePlayer : SessionPlayerController {
        private val _state = MutableStateFlow(SessionPlayerController.PlayerState(isPlaying = false, currentChunkIndex = 0, currentPositionMs = 0L, playbackSpeed = 1.0f))
        override val state: StateFlow<SessionPlayerController.PlayerState> = _state

        var lastLoaded: List<File> = emptyList()
        var lastSeek: Pair<Int, Long>? = null
        var lastSpeed: Float? = null

        override fun loadSession(chunks: List<File>) {
            lastLoaded = chunks
            _state.value = _state.value.copy(currentChunkIndex = 0, currentPositionMs = 0L, lastErrorMessage = null)
        }

        override fun play() {
            _state.value = _state.value.copy(isPlaying = true)
        }

        override fun pause() {
            _state.value = _state.value.copy(isPlaying = false)
        }

        override fun togglePlayPause() {
            if (_state.value.isPlaying) pause() else play()
        }

        override fun seekToChunk(chunkIndex: Int, positionMs: Long) {
            lastSeek = chunkIndex to positionMs
            _state.value = _state.value.copy(currentChunkIndex = chunkIndex, currentPositionMs = positionMs.coerceAtLeast(0L))
        }

        override fun setSpeed(speed: Float) {
            lastSpeed = speed
            _state.value = _state.value.copy(playbackSpeed = speed)
        }

        override fun close() {}
    }

    @Test
    fun loadSession_prefersTranslation_fallsBackToTranscript() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val ws = AgentsWorkspace(context)
            ws.ensureInitialized()

            val sid = "rec_20260220_bilingual_vm"
            ws.mkdir(".agents/workspace/radio_recordings/$sid")
            ws.writeTextFile(".agents/workspace/radio_recordings/$sid/chunk_001.ogg", "fake ogg\n")
            ws.writeTextFile(".agents/workspace/radio_recordings/$sid/chunk_002.ogg", "fake ogg\n")
            ws.mkdir(".agents/workspace/radio_recordings/$sid/translations")
            ws.mkdir(".agents/workspace/radio_recordings/$sid/transcripts")

            val pretty =
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true }

            val tx1: JsonObject =
                buildJsonObject {
                    put("schema", JsonPrimitive("kotlin-agent-app/radio-translation-chunk@v1"))
                    put("sessionId", JsonPrimitive(sid))
                    put("chunkIndex", JsonPrimitive(1))
                    put("sourceLanguage", JsonPrimitive("ja"))
                    put("targetLanguage", JsonPrimitive("zh"))
                    put(
                        "segments",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("id", JsonPrimitive(0))
                                    put("startMs", JsonPrimitive(0))
                                    put("endMs", JsonPrimitive(1000))
                                    put("sourceText", JsonPrimitive("S1"))
                                    put("translatedText", JsonPrimitive("T1"))
                                },
                            )
                        },
                    )
                }
            ws.writeTextFile(".agents/workspace/radio_recordings/$sid/translations/chunk_001.translation.json", pretty.encodeToString(JsonObject.serializer(), tx1) + "\n")

            val tr2: JsonObject =
                buildJsonObject {
                    put("schema", JsonPrimitive("kotlin-agent-app/radio-transcript-chunk@v1"))
                    put("sessionId", JsonPrimitive(sid))
                    put("chunkIndex", JsonPrimitive(2))
                    put(
                        "segments",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("id", JsonPrimitive(0))
                                    put("startMs", JsonPrimitive(0))
                                    put("endMs", JsonPrimitive(1200))
                                    put("text", JsonPrimitive("S2"))
                                },
                            )
                        },
                    )
                }
            ws.writeTextFile(".agents/workspace/radio_recordings/$sid/transcripts/chunk_002.transcript.json", pretty.encodeToString(JsonObject.serializer(), tr2) + "\n")

            val loader =
                BilingualSessionLoader(
                    workspace = ws,
                    durationReader =
                        object : BilingualSessionLoader.ChunkDurationReader {
                            override fun readDurationMs(file: File): Long? {
                                return if (file.name.contains("001")) 1500L else 2000L
                            }
                        },
                )
            val player = FakePlayer()
            val vm =
                BilingualPlayerViewModel(
                    workspace = ws,
                    loader = loader,
                    player = player,
                    ioDispatcher = Dispatchers.Main,
                )

            vm.loadSession(sid)

            val st = vm.state.value
            assertEquals(sid, st.sessionId)
            assertEquals(2, st.chunkCount)
            assertTrue(player.lastLoaded.size == 2)

            // 2 segments total: first from translation, second from transcript.
            assertEquals(2, st.segments.size)
            assertEquals("S1", st.segments[0].sourceText)
            assertEquals("T1", st.segments[0].translatedText)
            assertEquals(0L, st.segments[0].totalStartMs)
            assertEquals("S2", st.segments[1].sourceText)
            assertEquals(null, st.segments[1].translatedText)
            assertEquals(1500L, st.segments[1].totalStartMs)

            // Seek by segment should call player.seekToChunk with correct mapping.
            vm.seekToSegment(0)
            assertNotNull(player.lastSeek)
        }
}
