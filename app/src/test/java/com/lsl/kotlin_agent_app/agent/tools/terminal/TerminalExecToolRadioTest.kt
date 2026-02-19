package com.lsl.kotlin_agent_app.agent.tools.terminal

import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.media.MusicPlayerController
import com.lsl.kotlin_agent_app.media.MusicPlayerControllerProvider
import com.lsl.kotlin_agent_app.radios.RadioPathNaming
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalExecToolRadioTest {

    @Test
    fun radio_help_forms_exit0() =
        runTerminalExecToolTest { tool ->
            val forms =
                listOf(
                    "radio --help",
                    "radio help",
                    "radio status --help",
                    "radio help status",
                    "radio play --help",
                    "radio help play",
                    "radio pause --help",
                    "radio help pause",
                    "radio resume --help",
                    "radio help resume",
                    "radio stop --help",
                    "radio help stop",
                )
            for (cmd in forms) {
                val out = tool.exec(cmd)
                assertEquals("help should exit 0 for: $cmd", 0, out.exitCode)
                val r = out.result ?: error("missing result for: $cmd")
                assertEquals(true, r["ok"]!!.jsonPrimitive.content.toBooleanStrict())
                assertTrue("stdout should be non-empty for: $cmd", out.stdout.isNotBlank())
            }
        }

    @Test
    fun radio_play_rejectsBadPaths_andMissingFile() =
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
            val outOutside = tool.exec("radio play --in workspace/musics/not_allowed.radio")
            assertTrue(outOutside.exitCode != 0)
            assertEquals("NotInRadiosDir", outOutside.errorCode)

            val outExt = tool.exec("radio play --in workspace/radios/not_radio.txt")
            assertTrue(outExt.exitCode != 0)
            assertEquals("NotRadioFile", outExt.errorCode)

            val outMissing = tool.exec("radio play --in workspace/radios/missing.radio")
            assertTrue(outMissing.exitCode != 0)
            assertEquals("NotFound", outMissing.errorCode)
        }

    @Test
    fun radio_play_pause_resume_stop_roundtrip() =
        runTerminalExecToolTest(
            setup = { context ->
                val ws = AgentsWorkspace(context)
                ws.ensureInitialized()
                val radioJson =
                    """
                    {
                      "schema": "kotlin-agent-app/radio-station@v1",
                      "id": "radio-browser:test-1",
                      "name": "Test Station 1",
                      "streamUrl": "https://example.com/live?token=SECRET",
                      "country": "Testland",
                      "faviconUrl": "https://example.com/favicon.png"
                    }
                    """.trimIndent()
                val f = File(context.filesDir, ".agents/workspace/radios/test1.radio")
                f.parentFile?.mkdirs()
                f.writeText(radioJson, Charsets.UTF_8)

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
            val outPlay = tool.exec("radio play --in workspace/radios/test1.radio")
            assertEquals(0, outPlay.exitCode)

            val outStatus1 = tool.exec("radio status")
            assertEquals(0, outStatus1.exitCode)
            assertEquals("playing", outStatus1.result!!["state"]!!.jsonPrimitive.content)
            val station1 = outStatus1.result!!["station"]!!.jsonObject
            assertEquals("workspace/radios/test1.radio", station1["path"]!!.jsonPrimitive.content)
            assertEquals("radio-browser:test-1", station1["id"]!!.jsonPrimitive.content)
            assertEquals("Test Station 1", station1["name"]!!.jsonPrimitive.content)

            val outPause = tool.exec("radio pause")
            assertEquals(0, outPause.exitCode)
            assertEquals("paused", outPause.result!!["state"]!!.jsonPrimitive.content)

            val outResume = tool.exec("radio resume")
            assertEquals(0, outResume.exitCode)
            assertEquals("playing", outResume.result!!["state"]!!.jsonPrimitive.content)

            val outStop = tool.exec("radio stop")
            assertEquals(0, outStop.exitCode)
            val outStatus2 = tool.exec("radio status")
            assertEquals(0, outStatus2.exitCode)
            assertEquals("stopped", outStatus2.result!!["state"]!!.jsonPrimitive.content)
        }

    @Test
    fun radio_play_supportsPathsWithSpaces_viaIn_andInB64() =
        runTerminalExecToolTest(
            setup = { context ->
                val ws = AgentsWorkspace(context)
                ws.ensureInitialized()

                val radioJson =
                    """
                    {
                      "schema": "kotlin-agent-app/radio-station@v1",
                      "id": "radio-browser:egypt-1",
                      "name": "Egyptian Radio",
                      "streamUrl": "https://example.com/live?token=SECRET",
                      "country": "Egypt",
                      "faviconUrl": "https://example.com/favicon.png"
                    }
                    """.trimIndent()
                val agentsPath = ".agents/workspace/radios/EG__Egypt/Egyptian Radio__test-1.radio"
                val f = File(context.filesDir, agentsPath)
                f.parentFile?.mkdirs()
                f.writeText(radioJson, Charsets.UTF_8)

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
            val rawWorkspacePath = "workspace/radios/EG__Egypt/Egyptian Radio__test-1.radio"

            val outPlayIn = tool.exec("radio play --in $rawWorkspacePath")
            assertEquals(0, outPlayIn.exitCode)
            val st1 = tool.exec("radio status")
            assertEquals(0, st1.exitCode)
            assertEquals(rawWorkspacePath, st1.result!!["station"]!!.jsonObject["path"]!!.jsonPrimitive.content)

            val outStop = tool.exec("radio stop")
            assertEquals(0, outStop.exitCode)

            val b64 = Base64.getEncoder().encodeToString(rawWorkspacePath.toByteArray(Charsets.UTF_8))
            val outPlayB64 = tool.exec("radio play --in_b64 $b64")
            assertEquals(0, outPlayB64.exitCode)
            val st2 = tool.exec("radio status")
            assertEquals(0, st2.exitCode)
            assertEquals(rawWorkspacePath, st2.result!!["station"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        }

    @Test
    fun radio_fav_add_list_rm_roundtrip() =
        runTerminalExecToolTest(
            setup = { context ->
                val ws = AgentsWorkspace(context)
                ws.ensureInitialized()
                val radioJson =
                    """
                    {
                      "schema": "kotlin-agent-app/radio-station@v1",
                      "id": "radio-browser:test-1",
                      "name": "Test Station 1",
                      "streamUrl": "https://example.com/live?token=SECRET",
                      "country": "Testland",
                      "faviconUrl": "https://example.com/favicon.png"
                    }
                    """.trimIndent()
                val f = File(context.filesDir, ".agents/workspace/radios/test1.radio")
                f.parentFile?.mkdirs()
                f.writeText(radioJson, Charsets.UTF_8)

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
            val outPlay = tool.exec("radio play --in workspace/radios/test1.radio")
            assertEquals(0, outPlay.exitCode)

            val outFavAdd = tool.exec("radio fav add")
            assertEquals(0, outFavAdd.exitCode)

            val expectedFileName = RadioPathNaming.stationFileName(stationName = "Test Station 1", stationUuid = "test-1")
            val expectedAgentsPath = ".agents/workspace/radios/favorites/$expectedFileName"
            val favFile = File(outFavAdd.filesDir, expectedAgentsPath)
            assertTrue("favorite file should exist: $expectedAgentsPath", favFile.exists())

            val outFavList1 = tool.exec("radio fav list")
            assertEquals(0, outFavList1.exitCode)
            val favs1 = outFavList1.result!!["favorites"]!!.jsonArray
            assertEquals(1, favs1.size)
            val st1 = favs1[0].jsonObject
            assertEquals("Test Station 1", st1["name"]!!.jsonPrimitive.content)
            assertEquals("workspace/radios/favorites/$expectedFileName", st1["path"]!!.jsonPrimitive.content)

            val outFavRm = tool.exec("radio fav rm")
            assertEquals(0, outFavRm.exitCode)
            assertTrue("favorite file should be removed: $expectedAgentsPath", !favFile.exists())

            val outFavList2 = tool.exec("radio fav list")
            assertEquals(0, outFavList2.exitCode)
            val favs2 = outFavList2.result!!["favorites"]!!.jsonArray
            assertEquals(0, favs2.size)
        }

    @Test
    fun radio_fav_add_requiresPlayingWhenNoInFlag() =
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
            val out = tool.exec("radio fav add")
            assertTrue(out.exitCode != 0)
            assertEquals("NotPlayingRadio", out.errorCode)
        }
}
