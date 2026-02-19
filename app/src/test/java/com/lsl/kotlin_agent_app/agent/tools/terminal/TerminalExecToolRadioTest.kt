package com.lsl.kotlin_agent_app.agent.tools.terminal

import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.media.MusicPlayerController
import com.lsl.kotlin_agent_app.media.MusicPlayerControllerProvider
import com.lsl.kotlin_agent_app.radios.RadioBrowserClientTestHooks
import com.lsl.kotlin_agent_app.radios.RadioBrowserHttpResponse
import com.lsl.kotlin_agent_app.radios.RadioBrowserTransport
import com.lsl.kotlin_agent_app.radios.RadioPathNaming
import com.lsl.kotlin_agent_app.radios.StreamUrlResolverHttpResponse
import com.lsl.kotlin_agent_app.radios.StreamUrlResolverTestHooks
import com.lsl.kotlin_agent_app.radios.StreamUrlResolverTransport
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
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
                    "radio sync --help",
                    "radio help sync",
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
            val transport1 = outStatus1.result!!["transport"]!!.jsonObject
            assertTrue("transport.playback_state should exist", transport1["playback_state"] != null)
            assertTrue("transport.play_when_ready should exist", transport1["play_when_ready"] != null)
            assertTrue("transport.is_playing should exist", transport1["is_playing"] != null)
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
    fun radio_play_supportsAwaitMs_andReturnsVerifyObject() =
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
            val outPlay = tool.exec("radio play --in workspace/radios/test1.radio --await_ms 10")
            assertEquals(0, outPlay.exitCode)

            val verify = outPlay.result!!["play"]!!.jsonObject["verify"]!!.jsonObject
            assertEquals("playing", verify["outcome"]!!.jsonPrimitive.content)
            assertTrue("polls should be >= 1", verify["polls"]!!.jsonPrimitive.content.toInt() >= 1)
        }

    @Test
    fun radio_play_resolvesPls_andPlaysFirstCandidateUrl() =
        run {
            val transportHolder = arrayOfNulls<FakeMusicTransport>(1)
        runTerminalExecToolTest(
            setup = { context ->
                val ws = AgentsWorkspace(context)
                ws.ensureInitialized()
                val radioJson =
                    """
                    {
                      "schema": "kotlin-agent-app/radio-station@v1",
                      "id": "radio-browser:test-pls",
                      "name": "Playlist Station",
                      "streamUrl": "https://example.com/list.pls",
                      "country": "Testland",
                      "faviconUrl": "https://example.com/favicon.png"
                    }
                    """.trimIndent()
                val f = File(context.filesDir, ".agents/workspace/radios/test_pls.radio")
                f.parentFile?.mkdirs()
                f.writeText(radioJson, Charsets.UTF_8)

                val transport = FakeMusicTransport()
                transportHolder[0] = transport

                StreamUrlResolverTestHooks.install(
                    object : StreamUrlResolverTransport {
                        override suspend fun get(url: HttpUrl): StreamUrlResolverHttpResponse {
                            val body =
                                """
                                [playlist]
                                NumberOfEntries=2
                                File1=https://cdn.example.com/live1.mp3
                                File2=https://cdn.example.com/live2.aac
                                """.trimIndent()
                            return StreamUrlResolverHttpResponse(
                                statusCode = 200,
                                finalUrl = url,
                                contentType = "audio/x-scpls",
                                bodyText = body,
                                redirectCount = 0,
                            )
                        }
                    }
                )

                MusicPlayerControllerProvider.resetForTests()
                MusicPlayerControllerProvider.installAppContext(context)
                MusicPlayerControllerProvider.factoryOverride = { ctx ->
                    MusicPlayerController(ctx, transport = transport)
                }
                {
                    StreamUrlResolverTestHooks.clear()
                    MusicPlayerControllerProvider.resetForTests()
                }
            },
        ) { tool ->
            val outPlay = tool.exec("radio play --in workspace/radios/test_pls.radio")
            assertEquals(0, outPlay.exitCode)

            assertEquals("playing", outPlay.result!!["state"]!!.jsonPrimitive.content)
            val transport = transportHolder[0] ?: error("missing test transport")
            assertEquals("https://cdn.example.com/live1.mp3", transport.lastPlayedUri)
        }
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

    @Test
    fun radio_sync_countries_and_stations_roundtrip() =
        runTerminalExecToolTest(
            setup = { context ->
                val radiosRoot = File(context.filesDir, ".agents/workspace/radios")
                radiosRoot.deleteRecursively()
                AgentsWorkspace(context).ensureInitialized()

                val fake =
                    object : RadioBrowserTransport {
                        override suspend fun get(url: HttpUrl): RadioBrowserHttpResponse {
                            val path = url.encodedPath
                            val body =
                                when {
                                    path == "/json/countries" ->
                                        """
                                        [
                                          {"name":"Egypt","stationcount":2,"iso_3166_1":"EG"}
                                        ]
                                        """.trimIndent()

                                    path.startsWith("/json/stations/bycountry/") ->
                                        """
                                        [
                                          {"stationuuid":"uuid-1","name":"Egypt Station 1","url_resolved":"https://example.com/eg1","country":"Egypt","votes":10},
                                          {"stationuuid":"uuid-2","name":"Egypt Station 2","url_resolved":"https://example.com/eg2","country":"Egypt","votes":20}
                                        ]
                                        """.trimIndent()

                                    else -> "[]"
                                }
                            return RadioBrowserHttpResponse(statusCode = 200, bodyText = body, headers = emptyMap())
                        }
                    }

                RadioBrowserClientTestHooks.install(fake);
                {
                    RadioBrowserClientTestHooks.clear()
                }
            },
        ) { tool ->
            val outCountries = tool.exec("radio sync countries --force")
            assertEquals(0, outCountries.exitCode)
            assertEquals("1", outCountries.result!!["countries_count"]!!.jsonPrimitive.content)

            val outStations = tool.exec("radio sync stations --cc EG --force")
            assertEquals(0, outStations.exitCode)
            assertEquals("EG__Egypt", outStations.result!!["dir"]!!.jsonPrimitive.content)
            assertEquals("2", outStations.result!!["radios_count"]!!.jsonPrimitive.content)

            val idx = File(outStations.filesDir, ".agents/workspace/radios/EG__Egypt/.stations.index.json")
            assertTrue("stations index should exist", idx.exists())
        }
}
