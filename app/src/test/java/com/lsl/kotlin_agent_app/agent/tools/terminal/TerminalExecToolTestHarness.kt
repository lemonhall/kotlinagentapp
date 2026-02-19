package com.lsl.kotlin_agent_app.agent.tools.terminal

import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.lemonhall.openagentic.sdk.tools.ToolContext
import me.lemonhall.openagentic.sdk.tools.ToolOutput
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertNotNull
import org.robolectric.RuntimeEnvironment

internal data class TerminalExecOut(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val runId: String,
    val errorCode: String?,
    val result: JsonObject?,
    val artifacts: List<String>,
    val filesDir: File,
)

internal fun runTerminalExecToolTest(
    setup: (android.content.Context) -> (() -> Unit) = { { } },
    block: suspend (TerminalExecTestHarness) -> Unit,
) {
    val context = RuntimeEnvironment.getApplication()
    val teardown = setup(context)
    AgentsWorkspace(context).ensureInitialized()

    try {
        val tool = TerminalExecTool(appContext = context)
        val ctx =
            ToolContext(
                fileSystem = FileSystem.SYSTEM,
                cwd = File(context.filesDir, ".agents").absolutePath.replace('\\', '/').toPath(),
            )
        val harness = TerminalExecTestHarness(appContext = context, tool = tool, ctx = ctx, filesDir = context.filesDir)

        runBlocking {
            block(harness)
        }
    } finally {
        teardown()
    }
}

internal class TerminalExecTestHarness(
    val appContext: android.content.Context,
    private val tool: TerminalExecTool,
    private val ctx: ToolContext,
    val filesDir: File,
) {
    suspend fun exec(
        command: String,
        stdin: String? = null,
    ): TerminalExecOut {
        return execWithTool(tool = tool, command = command, stdin = stdin)
    }

    suspend fun execWithFreshTool(
        command: String,
        stdin: String? = null,
    ): TerminalExecOut {
        val fresh = TerminalExecTool(appContext = appContext)
        return execWithTool(tool = fresh, command = command, stdin = stdin)
    }

    private suspend fun execWithTool(
        tool: TerminalExecTool,
        command: String,
        stdin: String?,
    ): TerminalExecOut {
        val input =
            buildJsonObject {
                put("command", JsonPrimitive(command))
                if (stdin != null) put("stdin", JsonPrimitive(stdin))
            }
        val out0 = tool.run(input, ctx)
        val json = (out0 as ToolOutput.Json).value
        assertNotNull(json)
        val obj = json!!.jsonObject
        val resultObj =
            when (val r = obj["result"]) {
                null, is JsonNull -> null
                else -> r.jsonObject
            }
        val artifacts =
            obj["artifacts"]?.jsonArray?.mapNotNull { el ->
                (el as? JsonObject)?.get("path")?.let { p ->
                    (p as? JsonPrimitive)?.content
                }
            }.orEmpty()
        return TerminalExecOut(
            exitCode = (obj["exit_code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1,
            stdout = (obj["stdout"] as? JsonPrimitive)?.content ?: "",
            stderr = (obj["stderr"] as? JsonPrimitive)?.content ?: "",
            runId = (obj["run_id"] as? JsonPrimitive)?.content ?: "",
            errorCode = (obj["error_code"] as? JsonPrimitive)?.content,
            result = resultObj,
            artifacts = artifacts,
            filesDir = filesDir,
        )
    }
}

internal fun seedSshEnv(
    filesDir: File,
    content: String,
) {
    val env = File(filesDir, ".agents/skills/ssh-cli/secrets/.env")
    env.parentFile?.mkdirs()
    env.writeText(content.trimEnd() + "\n", Charsets.UTF_8)
}

internal fun installFakeSshClientIfAvailable(
    stdout: String,
    stderr: String,
    remoteExitStatus: Int,
    hostKeyFingerprint: String,
) {
    try {
        val hooksClass = Class.forName("com.lsl.kotlin_agent_app.agent.tools.ssh.SshClientTestHooks")
        val clientInterface = Class.forName("com.lsl.kotlin_agent_app.agent.tools.ssh.SshClient")
        val responseClass = Class.forName("com.lsl.kotlin_agent_app.agent.tools.ssh.SshExecResponse")

        val response =
            responseClass.declaredConstructors.first { it.parameterTypes.size == 5 }.newInstance(
                stdout,
                stderr,
                remoteExitStatus,
                hostKeyFingerprint,
                1L,
            )

        val proxy =
            Proxy.newProxyInstance(
                clientInterface.classLoader,
                arrayOf(clientInterface),
            ) { _, method, _ ->
                when (method.name) {
                    "exec" -> response
                    else -> null
                }
            }

        val hooks = hooksClass.getField("INSTANCE").get(null)
        hooksClass.getMethod("install", clientInterface).invoke(hooks, proxy)
    } catch (_: ClassNotFoundException) {
        // ssh tool not implemented yet (red stage)
    }
}
