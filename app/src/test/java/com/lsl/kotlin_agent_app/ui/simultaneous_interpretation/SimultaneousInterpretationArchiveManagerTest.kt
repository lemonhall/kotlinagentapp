package com.lsl.kotlin_agent_app.ui.simultaneous_interpretation

import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimultaneousInterpretationArchiveManagerTest {
    @Test
    fun startNewSession_createsChineseNamedArchiveDir() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, ".agents/workspace/simultaneous_interpretation").deleteRecursively()
        val manager =
            SimultaneousInterpretationArchiveManager(
                appContext = context,
                nowProvider = {
                    ZonedDateTime.of(2026, 3, 9, 22, 1, 0, 0, ZoneId.of("Asia/Shanghai"))
                },
            )

        val rel = manager.startNewSession(targetLanguageCode = "en", targetLanguageLabel = "英语")

        assertEquals(".agents/workspace/simultaneous_interpretation/2026年03月09日 晚22点01分", rel)
        assertTrue(File(context.filesDir, rel).isDirectory)
    }
}