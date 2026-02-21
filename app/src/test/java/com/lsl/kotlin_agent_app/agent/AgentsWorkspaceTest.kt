package com.lsl.kotlin_agent_app.agent

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
class AgentsWorkspaceTest {

    @Test
    fun ensureInitialized_createsLedgerWorkspaceDir() {
        val context = RuntimeEnvironment.getApplication()
        val ws = AgentsWorkspace(context)
        ws.ensureInitialized()

        val ledgerDir = File(context.filesDir, ".agents/workspace/ledger")
        assertTrue(ledgerDir.exists() && ledgerDir.isDirectory)
    }

    @Test
    fun movePath_movesFileWithinAgents() {
        val context = RuntimeEnvironment.getApplication()
        val ws = AgentsWorkspace(context)
        ws.ensureInitialized()

        val src = ".agents/workspace/move/a.txt"
        val dest = ".agents/workspace/move/b.txt"

        File(context.filesDir, src).apply {
            parentFile?.mkdirs()
            writeText("hello", Charsets.UTF_8)
        }

        ws.movePath(from = src, to = dest, overwrite = false)

        val destFile = File(context.filesDir, dest)
        assertTrue(destFile.exists())
        assertEquals("hello", destFile.readText(Charsets.UTF_8))
        assertTrue(!File(context.filesDir, src).exists())
    }

    @Test
    fun movePath_movesDirectoryWithinAgents() {
        val context = RuntimeEnvironment.getApplication()
        val ws = AgentsWorkspace(context)
        ws.ensureInitialized()

        val srcDir = ".agents/workspace/move_dir/src"
        val destDir = ".agents/workspace/move_dir/dest"
        val srcFilePath = "$srcDir/a.txt"

        File(context.filesDir, srcFilePath).apply {
            parentFile?.mkdirs()
            writeText("dir", Charsets.UTF_8)
        }

        ws.movePath(from = srcDir, to = destDir, overwrite = false)

        val moved = File(context.filesDir, "$destDir/a.txt")
        assertTrue(moved.exists())
        assertEquals("dir", moved.readText(Charsets.UTF_8))
        assertTrue(!File(context.filesDir, srcDir).exists())
    }
}
