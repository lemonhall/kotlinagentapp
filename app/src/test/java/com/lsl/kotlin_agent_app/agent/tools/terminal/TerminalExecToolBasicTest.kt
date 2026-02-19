package com.lsl.kotlin_agent_app.agent.tools.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalExecToolBasicTest {

    @Test
    fun unknownCommand_isRejected() = runTerminalExecToolTest { tool ->
        val out = tool.exec("no_such_command")
        assertTrue(out.exitCode != 0)
        assertEquals("UnknownCommand", out.errorCode)
    }

    @Test
    fun hello_printsAsciiAndSignature_andWritesAuditRun() = runTerminalExecToolTest { tool ->
        val out = tool.exec("hello")
        assertEquals(0, out.exitCode)
        assertTrue(out.stdout.contains("HELLO"))
        assertTrue(out.stdout.contains("lemonhall"))

        val runId = out.runId
        assertTrue(runId.isNotBlank())

        val auditPath = File(out.filesDir, ".agents/artifacts/terminal_exec/runs/$runId.json")
        assertTrue("audit file should exist: $auditPath", auditPath.exists())
        val auditText = auditPath.readText(Charsets.UTF_8)
        assertTrue(auditText.contains("\"command\""))
        assertTrue(auditText.contains("hello"))
        assertTrue("audit should include stdout", auditText.contains("\"stdout\""))
        assertTrue("audit should not include stdin key", !auditText.contains("\"stdin\""))
    }

    @Test
    fun newlineIsRejected() = runTerminalExecToolTest { tool ->
        val out = tool.exec("hello\nworld")
        assertTrue(out.exitCode != 0)
        assertEquals("InvalidCommand", out.errorCode)
    }
}

