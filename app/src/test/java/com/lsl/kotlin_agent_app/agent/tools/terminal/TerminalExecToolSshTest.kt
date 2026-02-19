package com.lsl.kotlin_agent_app.agent.tools.terminal

import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalExecToolSshTest {

    @Test
    fun ssh_exec_requires_stdin() =
        runTerminalExecToolTest { tool ->
            seedSshEnv(tool.filesDir, "SSH_PASSWORD=dummy")
            installFakeSshClientIfAvailable(stdout = "ok", stderr = "", remoteExitStatus = 0, hostKeyFingerprint = "fp-test")

            val out = tool.exec("ssh exec --host example.com --port 22 --user root")
            assertTrue(out.exitCode != 0)
            assertEquals("InvalidArgs", out.errorCode)
        }

    @Test
    fun ssh_exec_unknownHost_withoutTrust_isRejected() =
        runTerminalExecToolTest { tool ->
            seedSshEnv(tool.filesDir, "SSH_PASSWORD=dummy")
            installFakeSshClientIfAvailable(stdout = "ok", stderr = "", remoteExitStatus = 0, hostKeyFingerprint = "fp-test")

            val out = tool.exec("ssh exec --host example.com --port 22 --user root", stdin = "id")
            assertTrue(out.exitCode != 0)
            assertEquals("HostKeyUntrusted", out.errorCode)
        }

    @Test
    fun ssh_exec_rejectsSensitiveArgv() =
        runTerminalExecToolTest { tool ->
            seedSshEnv(tool.filesDir, "SSH_PASSWORD=dummy")
            installFakeSshClientIfAvailable(stdout = "ok", stderr = "", remoteExitStatus = 0, hostKeyFingerprint = "fp-test")

            val out =
                tool.exec(
                    "ssh exec --host example.com --port 22 --user root --password secret",
                    stdin = "id",
                )
            assertTrue(out.exitCode != 0)
            assertEquals("SensitiveArgv", out.errorCode)
        }

    @Test
    fun ssh_exec_rejectsOutPathTraversal() =
        runTerminalExecToolTest { tool ->
            seedSshEnv(tool.filesDir, "SSH_PASSWORD=dummy")
            installFakeSshClientIfAvailable(stdout = "ok", stderr = "", remoteExitStatus = 0, hostKeyFingerprint = "fp-test")

            val out =
                tool.exec(
                    "ssh exec --host example.com --port 22 --user root --trust-host-key --out ../oops.json",
                    stdin = "id",
                )
            assertTrue(out.exitCode != 0)
            assertEquals("PathEscapesAgentsRoot", out.errorCode)
        }

    @Test
    fun ssh_exec_trustHostKey_writesKnownHosts_andOutArtifact() =
        runTerminalExecToolTest { tool ->
            seedSshEnv(tool.filesDir, "SSH_PASSWORD=dummy")
            installFakeSshClientIfAvailable(stdout = "hello", stderr = "", remoteExitStatus = 0, hostKeyFingerprint = "fp-1")

            val outRel = "artifacts/ssh/exec/test.json"
            val out =
                tool.exec(
                    "ssh exec --host example.com --port 22 --user root --trust-host-key --out $outRel",
                    stdin = "echo hi ; whoami",
                )
            assertEquals(0, out.exitCode)
            assertEquals("ssh exec", (out.result?.get("command") as? JsonPrimitive)?.content)
            assertTrue(out.artifacts.contains(".agents/$outRel"))
            assertTrue(File(tool.filesDir, ".agents/$outRel").exists())
            assertTrue(File(tool.filesDir, ".agents/workspace/ssh/known_hosts").exists())
        }

    @Test
    fun ssh_exec_remoteNonZeroExit_isStableErrorCode() =
        runTerminalExecToolTest { tool ->
            seedSshEnv(tool.filesDir, "SSH_PASSWORD=dummy")
            installFakeSshClientIfAvailable(stdout = "", stderr = "boom", remoteExitStatus = 7, hostKeyFingerprint = "fp-1")

            val outRel = "artifacts/ssh/exec/nonzero.json"
            val out =
                tool.exec(
                    "ssh exec --host example.com --port 22 --user root --trust-host-key --out $outRel",
                    stdin = "false",
                )
            assertTrue(out.exitCode != 0)
            assertEquals("RemoteNonZeroExit", out.errorCode)
            assertEquals(7, (out.result?.get("remote_exit_status") as? JsonPrimitive)?.content?.toInt())
        }
}

