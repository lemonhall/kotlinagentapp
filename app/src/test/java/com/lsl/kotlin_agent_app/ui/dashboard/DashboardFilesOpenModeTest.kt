package com.lsl.kotlin_agent_app.ui.dashboard

import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.lsl.kotlin_agent_app.MainActivity
import com.lsl.kotlin_agent_app.R
import com.lsl.kotlin_agent_app.agent.AgentsDirEntry
import com.lsl.kotlin_agent_app.agent.AgentsDirEntryType
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
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
class DashboardFilesOpenModeTest {

    @Test
    fun openJson_defaultsToViewMode() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val bottomNav = activity.findViewById<BottomNavigationView>(R.id.nav_view)
        assertNotNull(bottomNav)

        bottomNav.selectedItemId = R.id.navigation_dashboard
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val navHost =
            activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_activity_main) as NavHostFragment
        val dashboard =
            (
                navHost.childFragmentManager.primaryNavigationFragment
                    ?: navHost.childFragmentManager.fragments.firstOrNull { it is DashboardFragment }
            ) as DashboardFragment
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertNotNull(dashboard.view)

        val ws = AgentsWorkspace(activity)
        ws.ensureInitialized()
        ws.writeTextFile(".agents/test.json", """{"ok":true}""")

        val vm = ViewModelProvider(dashboard, FilesViewModel.Factory(activity)).get(FilesViewModel::class.java)
        vm.openFile(AgentsDirEntry(name = "test.json", type = AgentsDirEntryType.File))

        val deadlineMs = System.currentTimeMillis() + 2_000
        while (vm.state.value?.openFilePath.isNullOrBlank() && System.currentTimeMillis() < deadlineMs) {
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            Thread.sleep(10)
        }
        assertTrue(vm.state.value?.openFilePath?.endsWith("test.json") == true)

        val dialogField = DashboardFragment::class.java.getDeclaredField("editorDialog").apply { isAccessible = true }
        val deadlineDialogMs = System.currentTimeMillis() + 2_000
        var dialog: AlertDialog? = null
        while (dialog == null && System.currentTimeMillis() < deadlineDialogMs) {
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            dialog = dialogField.get(dashboard) as? AlertDialog
            if (dialog == null) Thread.sleep(10)
        }
        assertNotNull(dialog)

        val positiveText = dialog!!.getButton(DialogInterface.BUTTON_POSITIVE).text?.toString().orEmpty()
        assertEquals("编辑", positiveText)
    }
}
