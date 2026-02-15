package com.lsl.kotlin_agent_app

import androidx.compose.ui.platform.ComposeView
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.lsl.kotlin_agent_app.ui.chat.ChatFragment
import com.lsl.kotlin_agent_app.ui.web.WebFragment
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
class MainActivityChatNavigationTest {
    @Test
    fun launchMainActivity_navigateToChat_createsChatFragmentView() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        val bottomNav = activity.findViewById<BottomNavigationView>(R.id.nav_view)
        assertNotNull(bottomNav)

        bottomNav.selectedItemId = R.id.navigation_home
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val navHost = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_activity_main) as NavHostFragment
        val current = navHost.childFragmentManager.primaryNavigationFragment
            ?: navHost.childFragmentManager.fragments.firstOrNull { it is ChatFragment }

        assertTrue(current is ChatFragment)
        assertNotNull(current?.view)
        assertTrue(current?.view is ComposeView)
    }

    @Test
    fun bottomNav_switchWebThenBackToChat_keepsChatAccessible() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val bottomNav = activity.findViewById<BottomNavigationView>(R.id.nav_view)
        assertNotNull(bottomNav)

        bottomNav.selectedItemId = R.id.navigation_web
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val navHost = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_activity_main) as NavHostFragment
        val currentWeb =
            navHost.childFragmentManager.primaryNavigationFragment
                ?: navHost.childFragmentManager.fragments.firstOrNull { it is WebFragment }
        assertTrue(currentWeb is WebFragment)

        bottomNav.selectedItemId = R.id.navigation_home
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val currentChat =
            navHost.childFragmentManager.primaryNavigationFragment
                ?: navHost.childFragmentManager.fragments.firstOrNull { it is ChatFragment }
        assertTrue(currentChat is ChatFragment)
        assertNotNull(currentChat?.view)
        assertTrue(currentChat?.view is ComposeView)
    }
}
