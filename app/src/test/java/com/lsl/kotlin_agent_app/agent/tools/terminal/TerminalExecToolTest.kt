package com.lsl.kotlin_agent_app.agent.tools.terminal

import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import me.lemonhall.openagentic.sdk.tools.ToolContext
import me.lemonhall.openagentic.sdk.tools.ToolOutput
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalExecToolTest {

    @Test
    fun unknownCommand_isRejected() = runTest { tool ->
        val out = tool.exec("no_such_command")
        assertTrue(out.exitCode != 0)
        assertEquals("UnknownCommand", out.errorCode)
    }

    @Test
    fun hello_printsAsciiAndSignature_andWritesAuditRun() = runTest { tool ->
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
    }

    @Test
    fun newlineIsRejected() = runTest { tool ->
        val out = tool.exec("hello\nworld")
        assertTrue(out.exitCode != 0)
        assertEquals("InvalidCommand", out.errorCode)
    }

    private data class ExecOut(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val runId: String,
        val errorCode: String?,
        val filesDir: File,
    )

    private fun runTest(block: suspend (TestHarness) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        AgentsWorkspace(context).ensureInitialized()

        val tool = TerminalExecTool(appContext = context)
        val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = File(context.filesDir, ".agents").absolutePath.replace('\\', '/').toPath())
        val harness = TestHarness(tool = tool, ctx = ctx, filesDir = context.filesDir)

        kotlinx.coroutines.runBlocking {
            block(harness)
        }
    }

    private class TestHarness(
        private val tool: TerminalExecTool,
        private val ctx: ToolContext,
        val filesDir: File,
    ) {
        suspend fun exec(command: String): ExecOut {
            val input =
                buildJsonObject {
                    put("command", JsonPrimitive(command))
                }
            val out0 = tool.run(input, ctx)
            val json = (out0 as ToolOutput.Json).value
            assertNotNull(json)
            val obj = json!!.jsonObject
            return ExecOut(
                exitCode = (obj["exit_code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1,
                stdout = (obj["stdout"] as? JsonPrimitive)?.content ?: "",
                stderr = (obj["stderr"] as? JsonPrimitive)?.content ?: "",
                runId = (obj["run_id"] as? JsonPrimitive)?.content ?: "",
                errorCode = (obj["error_code"] as? JsonPrimitive)?.content,
                filesDir = filesDir,
            )
        }
    }
}

