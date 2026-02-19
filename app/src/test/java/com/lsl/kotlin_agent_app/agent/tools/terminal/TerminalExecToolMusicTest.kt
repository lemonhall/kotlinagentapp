package com.lsl.kotlin_agent_app.agent.tools.terminal

import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.media.MusicPlayerController
import com.lsl.kotlin_agent_app.media.MusicPlayerControllerProvider
import java.io.File
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalExecToolMusicTest {

    @Test
    fun music_status_idle_byDefault() =
        runTerminalExecToolTest(
            setup = { context ->
                MusicPlayerControllerProvider.resetForTests()
                MusicPlayerControllerProvider.installAppContext(context)
                MusicPlayerControllerProvider.factoryOverride = { ctx ->
                    MusicPlayerController(ctx, transport = FakeMusicTransport())
                }
                {
                    MusicPlayerControllerProvider.resetForTests()
                }
            },
        ) { tool ->
            val out = tool.exec("music status")
            assertEquals(0, out.exitCode)
            val r = out.result ?: error("missing result")
            assertEquals("idle", r["state"]!!.jsonPrimitive.content)
            assertEquals(0, r["queue_size"]!!.jsonPrimitive.content.toInt())
        }

    @Test
    fun music_play_rejectsPathOutsideMusicsTree() =
        runTerminalExecToolTest(
            setup = { context ->
                MusicPlayerControllerProvider.resetForTests()
                MusicPlayerControllerProvider.installAppContext(context)
                MusicPlayerControllerProvider.factoryOverride = { ctx ->
                    MusicPlayerController(ctx, transport = FakeMusicTransport())
                }
                {
                    MusicPlayerControllerProvider.resetForTests()
                }
            },
        ) { tool ->
            val out = tool.exec("music play --in workspace/inbox/not_allowed.mp3")
            assertTrue(out.exitCode != 0)
            assertEquals("PathNotAllowed", out.errorCode)
        }

    @Test
    fun music_play_pause_resume_seek_stop_roundtrip() =
        runTerminalExecToolTest(
            setup = { context ->
                val ws = AgentsWorkspace(context)
                ws.ensureInitialized()
                val p = "workspace/musics/demo.mp3"
                val f = File(context.filesDir, ".agents/$p")
                f.parentFile?.mkdirs()
                f.writeBytes(buildFakeMp3Bytes())

                MusicPlayerControllerProvider.resetForTests()
                MusicPlayerControllerProvider.installAppContext(context)
                MusicPlayerControllerProvider.factoryOverride = { ctx ->
                    MusicPlayerController(ctx, transport = FakeMusicTransport())
                }
                {
                    MusicPlayerControllerProvider.resetForTests()
                }
            },
        ) { tool ->
            val outPlay = tool.exec("music play --in workspace/musics/demo.mp3")
            assertEquals(0, outPlay.exitCode)

            val outStatus1 = tool.exec("music status")
            assertEquals(0, outStatus1.exitCode)
            assertEquals("playing", outStatus1.result!!["state"]!!.jsonPrimitive.content)

            val outPause = tool.exec("music pause")
            assertEquals(0, outPause.exitCode)
            assertEquals("paused", outPause.result!!["state"]!!.jsonPrimitive.content)

            val outResume = tool.exec("music resume")
            assertEquals(0, outResume.exitCode)
            assertEquals("playing", outResume.result!!["state"]!!.jsonPrimitive.content)

            val outSeek = tool.exec("music seek --to-ms 1234")
            assertEquals(0, outSeek.exitCode)
            val outStatus2 = tool.exec("music status")
            assertEquals(0, outStatus2.exitCode)
            assertEquals(1234L, outStatus2.result!!["position_ms"]!!.jsonPrimitive.content.toLong())

            val outStop = tool.exec("music stop")
            assertEquals(0, outStop.exitCode)
            val outStatus3 = tool.exec("music status")
            assertEquals(0, outStatus3.exitCode)
            assertEquals("stopped", outStatus3.result!!["state"]!!.jsonPrimitive.content)
        }

    @Test
    fun music_next_prev_usesDeterministicQueue() =
        runTerminalExecToolTest(
            setup = { context ->
                val ws = AgentsWorkspace(context)
                ws.ensureInitialized()
                val f1 = File(context.filesDir, ".agents/workspace/musics/a.mp3")
                val f2 = File(context.filesDir, ".agents/workspace/musics/b.mp3")
                f1.parentFile?.mkdirs()
                f1.writeBytes(buildFakeMp3Bytes())
                f2.writeBytes(buildFakeMp3Bytes())

                MusicPlayerControllerProvider.resetForTests()
                MusicPlayerControllerProvider.installAppContext(context)
                MusicPlayerControllerProvider.factoryOverride = { ctx ->
                    MusicPlayerController(ctx, transport = FakeMusicTransport())
                }
                {
                    MusicPlayerControllerProvider.resetForTests()
                }
            },
        ) { tool ->
            assertEquals(0, tool.exec("music play --in workspace/musics/a.mp3").exitCode)
            assertEquals(0, tool.exec("music next").exitCode)
            val st1 = tool.exec("music status").result ?: error("missing result")
            val track1 = st1["track"]!!.jsonObject
            assertEquals("workspace/musics/b.mp3", track1["path"]!!.jsonPrimitive.content)

            assertEquals(0, tool.exec("music prev").exitCode)
            val st2 = tool.exec("music status").result ?: error("missing result")
            val track2 = st2["track"]!!.jsonObject
            assertEquals("workspace/musics/a.mp3", track2["path"]!!.jsonPrimitive.content)
        }

    @Test
    fun music_meta_set_requiresConfirm_andDoesNotModifyFile() =
        runTerminalExecToolTest { tool ->
            val context = RuntimeEnvironment.getApplication()
            val ws = AgentsWorkspace(context)
            ws.ensureInitialized()
            val f = File(context.filesDir, ".agents/workspace/musics/meta.mp3")
            f.parentFile?.mkdirs()
            val before = buildFakeMp3Bytes()
            f.writeBytes(before)

            val out = tool.exec("music meta set --in workspace/musics/meta.mp3 --title new-title")
            assertTrue(out.exitCode != 0)
            assertEquals("ConfirmRequired", out.errorCode)
            assertTrue(f.readBytes().contentEquals(before))
        }

    @Test
    fun music_meta_set_writesAtomically_andMetaGetReflectsChanges() =
        runTerminalExecToolTest { tool ->
            val context = RuntimeEnvironment.getApplication()
            val ws = AgentsWorkspace(context)
            ws.ensureInitialized()
            val f = File(context.filesDir, ".agents/workspace/musics/meta2.mp3")
            f.parentFile?.mkdirs()
            f.writeBytes(buildFakeMp3Bytes())

            val outSet =
                tool.exec(
                    "music meta set --in workspace/musics/meta2.mp3 --title \"t1\" --artist \"a1\" --lyrics \"l1\" --confirm",
                )
            assertEquals(0, outSet.exitCode)
            val outGet = tool.exec("music meta get --in workspace/musics/meta2.mp3")
            assertEquals(0, outGet.exitCode)
            val md = outGet.result!!["metadata"]!!.jsonObject
            assertEquals("t1", md["title"]!!.jsonPrimitive.content)
            assertEquals("a1", md["artist"]!!.jsonPrimitive.content)
            assertEquals("l1", md["lyrics"]!!.jsonPrimitive.content)
        }

    @Test
    fun music_meta_set_rollbackOnReplaceFailure() =
        runTerminalExecToolTest(
            setup = { _ ->
                System.setProperty("kotlin-agent-app.music.atomic_replace.fail_for_test", "1");
                {
                    System.clearProperty("kotlin-agent-app.music.atomic_replace.fail_for_test")
                }
            },
        ) { tool ->
            val context = RuntimeEnvironment.getApplication()
            val ws = AgentsWorkspace(context)
            ws.ensureInitialized()
            val f = File(context.filesDir, ".agents/workspace/musics/meta3.mp3")
            f.parentFile?.mkdirs()
            val before = buildFakeMp3Bytes()
            f.writeBytes(before)

            val out =
                tool.exec(
                    "music meta set --in workspace/musics/meta3.mp3 --title \"t2\" --confirm",
                )
            assertTrue(out.exitCode != 0)
            assertEquals("WriteFailed", out.errorCode)
            assertTrue(f.readBytes().contentEquals(before))
        }
}
