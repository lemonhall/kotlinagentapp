package com.lsl.kotlin_agent_app.agent

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentsWorkspaceSimultaneousInterpretationTest {
    @Test
    fun ensureInitialized_createsSimultaneousInterpretationWorkspaceDir() {
        val context = RuntimeEnvironment.getApplication()
        val ws = AgentsWorkspace(context)
        ws.ensureInitialized()

        val dir = File(context.filesDir, ".agents/workspace/simultaneous_interpretation")
        assertTrue(dir.exists() && dir.isDirectory)
    }
}