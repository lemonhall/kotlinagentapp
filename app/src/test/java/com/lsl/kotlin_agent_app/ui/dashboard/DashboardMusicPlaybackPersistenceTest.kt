package com.lsl.kotlin_agent_app.ui.dashboard

import android.view.View
import android.widget.TextView
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.lsl.kotlin_agent_app.MainActivity
import com.lsl.kotlin_agent_app.R
import com.lsl.kotlin_agent_app.media.Mp3MetadataExtractor
import com.lsl.kotlin_agent_app.media.Mp3MetadataReader
import com.lsl.kotlin_agent_app.media.MusicPlaybackRequest
import com.lsl.kotlin_agent_app.media.MusicPlayerController
import com.lsl.kotlin_agent_app.media.MusicPlayerControllerProvider
import com.lsl.kotlin_agent_app.media.MusicTransport
import com.lsl.kotlin_agent_app.media.RawMp3Metadata
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardMusicPlaybackPersistenceTest {

    private class FakeTransport : MusicTransport {
        var playing: Boolean = false
        var vol: Float = 1.0f
        var transportListener: com.lsl.kotlin_agent_app.media.MusicTransportListener? = null
        override suspend fun connect() = Unit
        override suspend fun play(request: MusicPlaybackRequest) {
            playing = true
        }
        override suspend fun pause() {
            playing = false
        }
        override suspend fun resume() {
            playing = true
        }
        override suspend fun stop() {
            playing = false
        }
        override suspend fun seekTo(positionMs: Long) = Unit
        override fun currentPositionMs(): Long = 1234L
        override fun durationMs(): Long? = 5000L
        override fun isPlaying(): Boolean = playing
        override suspend fun setVolume(volume: Float) {
            vol = volume
        }
        override fun volume(): Float? = vol
        override fun setListener(listener: com.lsl.kotlin_agent_app.media.MusicTransportListener?) {
            this.transportListener = listener
        }
    }

    @Test
    fun playbackState_persistsAcrossTabSwitch_andMiniBarRebinds() {
        MusicPlayerControllerProvider.resetForTests()
        MusicPlayerControllerProvider.factoryOverride = { ctx ->
            val fake = FakeTransport()
            MusicPlayerController(
                ctx,
                transport = fake,
                metadataReader =
                    Mp3MetadataReader(
                        extractor =
                            Mp3MetadataExtractor {
                                RawMp3Metadata(title = "Song", artist = "Artist", durationMs = 5000L)
                            }
                    ),
            )
        }

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val bottomNav = activity.findViewById<BottomNavigationView>(R.id.nav_view)
        assertNotNull(bottomNav)

        bottomNav.selectedItemId = R.id.navigation_dashboard
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val controller = MusicPlayerControllerProvider.get()
        controller.playAgentsMp3(".agents/workspace/musics/a.mp3")

        val deadlineMs = System.currentTimeMillis() + 2_000
        while (controller.state.value.agentsPath.isNullOrBlank() && System.currentTimeMillis() < deadlineMs) {
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            Thread.sleep(10)
        }
        assertEquals(".agents/workspace/musics/a.mp3", controller.state.value.agentsPath)
        assertTrue(controller.state.value.isPlaying)

        fun currentDashboard(): DashboardFragment {
            val navHost =
                activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_activity_main) as NavHostFragment
            return (
                navHost.childFragmentManager.primaryNavigationFragment
                    ?: navHost.childFragmentManager.fragments.firstOrNull { it is DashboardFragment }
            ) as DashboardFragment
        }

        val dashboard1 = currentDashboard()
        val miniBar1 = dashboard1.view!!.findViewById<View>(R.id.music_mini_bar)
        val title1 = dashboard1.view!!.findViewById<TextView>(R.id.text_music_title)
        assertEquals(View.VISIBLE, miniBar1.visibility)
        assertEquals("Song", title1.text.toString())

        bottomNav.selectedItemId = R.id.navigation_home
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        bottomNav.selectedItemId = R.id.navigation_dashboard
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val dashboard2 = currentDashboard()
        val miniBar2 = dashboard2.view!!.findViewById<View>(R.id.music_mini_bar)
        val title2 = dashboard2.view!!.findViewById<TextView>(R.id.text_music_title)
        assertEquals(View.VISIBLE, miniBar2.visibility)
        assertEquals("Song", title2.text.toString())

        assertEquals(".agents/workspace/musics/a.mp3", controller.state.value.agentsPath)
        assertTrue(controller.state.value.isPlaying)

        MusicPlayerControllerProvider.resetForTests()
    }

    @Test
    fun radioPlayback_persistsAcrossTabSwitch_andMiniBarRebinds() {
        MusicPlayerControllerProvider.resetForTests()
        MusicPlayerControllerProvider.factoryOverride = { ctx ->
            val fake = FakeTransport()
            MusicPlayerController(
                ctx,
                transport = fake,
                metadataReader = Mp3MetadataReader(extractor = Mp3MetadataExtractor { null }),
            )
        }

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val bottomNav = activity.findViewById<BottomNavigationView>(R.id.nav_view)
        assertNotNull(bottomNav)

        val radioPath = ".agents/workspace/radios/CN__China/test.radio"
        val f = File(activity.filesDir, radioPath)
        f.parentFile?.mkdirs()
        f.writeText(
            """{"schema":"kotlin-agent-app/radio-station@v1","id":"radio-browser:uuid-1","name":"Station X","streamUrl":"https://example.com/stream"}""",
            Charsets.UTF_8
        )

        bottomNav.selectedItemId = R.id.navigation_dashboard
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val controller = MusicPlayerControllerProvider.get()
        controller.playAgentsRadio(radioPath)

        val deadlineMs = System.currentTimeMillis() + 2_000
        while (controller.state.value.agentsPath.isNullOrBlank() && System.currentTimeMillis() < deadlineMs) {
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            Thread.sleep(10)
        }
        assertEquals(radioPath, controller.state.value.agentsPath)
        assertTrue(controller.state.value.isPlaying)
        assertTrue(controller.state.value.isLive)

        fun currentDashboard(): DashboardFragment {
            val navHost =
                activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_activity_main) as NavHostFragment
            return (
                navHost.childFragmentManager.primaryNavigationFragment
                    ?: navHost.childFragmentManager.fragments.firstOrNull { it is DashboardFragment }
            ) as DashboardFragment
        }

        val dashboard1 = currentDashboard()
        val miniBar1 = dashboard1.view!!.findViewById<View>(R.id.music_mini_bar)
        val title1 = dashboard1.view!!.findViewById<TextView>(R.id.text_music_title)
        assertEquals(View.VISIBLE, miniBar1.visibility)
        assertEquals("Station X", title1.text.toString())

        bottomNav.selectedItemId = R.id.navigation_home
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        bottomNav.selectedItemId = R.id.navigation_dashboard
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val dashboard2 = currentDashboard()
        val miniBar2 = dashboard2.view!!.findViewById<View>(R.id.music_mini_bar)
        val title2 = dashboard2.view!!.findViewById<TextView>(R.id.text_music_title)
        assertEquals(View.VISIBLE, miniBar2.visibility)
        assertEquals("Station X", title2.text.toString())

        assertEquals(radioPath, controller.state.value.agentsPath)
        assertTrue(controller.state.value.isPlaying)

        MusicPlayerControllerProvider.resetForTests()
    }
}
