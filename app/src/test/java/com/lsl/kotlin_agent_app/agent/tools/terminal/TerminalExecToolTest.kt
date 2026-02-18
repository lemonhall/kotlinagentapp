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
import java.util.UUID

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
        assertTrue("audit should include stdout", auditText.contains("\"stdout\""))
        assertTrue("audit should not include stdin key", !auditText.contains("\"stdin\""))
    }

    @Test
    fun newlineIsRejected() = runTest { tool ->
        val out = tool.exec("hello\nworld")
        assertTrue(out.exitCode != 0)
        assertEquals("InvalidCommand", out.errorCode)
    }

    @Test
    fun git_init_status_add_commit_log_happyPath() = runTest { tool ->
        val repoName = "jgit-demo-" + UUID.randomUUID().toString().replace("-", "").take(8)
        val repoRel = "workspace/$repoName"

        // init repo
        val initOut = tool.exec("git init --dir $repoRel")
        assertEquals(0, initOut.exitCode)

        // create a new file (untracked)
        val repoDir = File(initOut.filesDir, ".agents/$repoRel")
        assertTrue(repoDir.exists())
        File(repoDir, "a.txt").writeText("hello", Charsets.UTF_8)

        val status1 = tool.exec("git status --repo $repoRel")
        assertEquals(0, status1.exitCode)
        assertTrue(status1.stdout.contains("untracked", ignoreCase = true))

        // add + commit
        val addOut = tool.exec("git add --repo $repoRel --all")
        assertEquals(0, addOut.exitCode)

        val commitOut = tool.exec("git commit --repo $repoRel --message \"init\"")
        assertEquals(0, commitOut.exitCode)

        val status2 = tool.exec("git status --repo $repoRel")
        assertEquals(0, status2.exitCode)
        assertTrue(status2.stdout.contains("clean", ignoreCase = true))

        val logOut = tool.exec("git log --repo $repoRel --max 1")
        assertEquals(0, logOut.exitCode)
        assertTrue(logOut.stdout.contains("init"))
    }

    @Test
    fun git_repoPathTraversal_isRejected() = runTest { tool ->
        val out = tool.exec("git status --repo ../")
        assertTrue(out.exitCode != 0)
        assertEquals("PathEscapesAgentsRoot", out.errorCode)
    }

    @Test
    fun git_branch_checkout_show_diff_reset_stash_happyPath() = runTest { tool ->
        val repoName = "jgit-v14-" + UUID.randomUUID().toString().replace("-", "").take(8)
        val repoRel = "workspace/$repoName"

        // init + first commit
        assertEquals(0, tool.exec("git init --dir $repoRel").exitCode)
        val repoDir = File(tool.filesDir, ".agents/$repoRel")
        File(repoDir, "a.txt").writeText("v1", Charsets.UTF_8)
        assertEquals(0, tool.exec("git add --repo $repoRel --all").exitCode)
        assertEquals(0, tool.exec("git commit --repo $repoRel --message \"c1\"").exitCode)

        // branch + checkout
        val co = tool.exec("git checkout --repo $repoRel --branch feat --create")
        assertEquals(0, co.exitCode)
        val branches = tool.exec("git branch --repo $repoRel")
        assertEquals(0, branches.exitCode)
        assertTrue(branches.stdout.contains("feat"))

        // second commit on feat
        File(repoDir, "a.txt").writeText("v2", Charsets.UTF_8)
        assertEquals(0, tool.exec("git add --repo $repoRel --all").exitCode)
        assertEquals(0, tool.exec("git commit --repo $repoRel --message \"c2\"").exitCode)

        // show HEAD
        val show = tool.exec("git show --repo $repoRel --commit HEAD --patch --max-chars 2000")
        assertEquals(0, show.exitCode)
        assertTrue(show.stdout.contains("c2"))

        // diff between commits
        val diff = tool.exec("git diff --repo $repoRel --from HEAD~1 --to HEAD --patch --max-chars 2000")
        assertEquals(0, diff.exitCode)
        assertTrue(diff.stdout.isNotBlank())

        // stash: dirty working tree then stash push/list/pop
        File(repoDir, "a.txt").writeText("dirty", Charsets.UTF_8)
        val stashPush = tool.exec("git stash push --repo $repoRel --message \"wip\"")
        assertEquals(0, stashPush.exitCode)
        val statusAfterStash = tool.exec("git status --repo $repoRel")
        assertEquals(0, statusAfterStash.exitCode)
        assertTrue(statusAfterStash.stdout.contains("clean", ignoreCase = true))

        val stashList = tool.exec("git stash list --repo $repoRel --max 5")
        assertEquals(0, stashList.exitCode)
        assertTrue(stashList.stdout.contains("wip"))

        val stashPop = tool.exec("git stash pop --repo $repoRel --index 0")
        assertEquals(0, stashPop.exitCode)
        val statusAfterPop = tool.exec("git status --repo $repoRel")
        assertEquals(0, statusAfterPop.exitCode)
        assertTrue(statusAfterPop.stdout.contains("not clean", ignoreCase = true))

        // reset: hard back one commit (from c2 to c1)
        val reset = tool.exec("git reset --repo $repoRel --mode hard --to HEAD~1")
        assertEquals(0, reset.exitCode)
        val log1 = tool.exec("git log --repo $repoRel --max 1")
        assertEquals(0, log1.exitCode)
        assertTrue(log1.stdout.contains("c1"))
    }

    @Test
    fun git_remoteClone_requiresConfirm() = runTest { tool ->
        val out =
            tool.exec(
                "git clone --remote https://example.com/repo.git --dir workspace/clone-no-confirm",
            )
        assertTrue(out.exitCode != 0)
        assertEquals("ConfirmRequired", out.errorCode)
    }

    @Test
    fun git_remoteClone_rejectsUserinfoUrl() = runTest { tool ->
        val out =
            tool.exec(
                "git clone --remote https://user:pass@example.com/repo.git --dir workspace/clone-bad --confirm",
            )
        assertTrue(out.exitCode != 0)
        assertEquals("InvalidRemoteUrl", out.errorCode)
    }

    @Test
    fun git_localRemote_clone_push_pull_happyPath() = runTest { tool ->
        val id = UUID.randomUUID().toString().replace("-", "").take(8)
        val originRel = "workspace/origin-$id.git"
        val clone1Rel = "workspace/clone1-$id"
        val clone2Rel = "workspace/clone2-$id"

        // Bare origin (needed for push)
        val initBare = tool.exec("git init --dir $originRel --bare")
        assertEquals(0, initBare.exitCode)

        // Clone1, commit, push
        assertEquals(0, tool.exec("git clone --local-remote $originRel --dir $clone1Rel --confirm").exitCode)
        val clone1Dir = File(tool.filesDir, ".agents/$clone1Rel")
        File(clone1Dir, "a.txt").writeText("v1", Charsets.UTF_8)
        assertEquals(0, tool.exec("git add --repo $clone1Rel --all").exitCode)
        assertEquals(0, tool.exec("git commit --repo $clone1Rel --message \"c1\"").exitCode)
        assertEquals(0, tool.exec("git push --repo $clone1Rel --confirm").exitCode)

        // Clone2 then pull after new push
        assertEquals(0, tool.exec("git clone --local-remote $originRel --dir $clone2Rel --confirm").exitCode)

        // New commit in clone1
        File(clone1Dir, "a.txt").writeText("v2", Charsets.UTF_8)
        assertEquals(0, tool.exec("git add --repo $clone1Rel --all").exitCode)
        assertEquals(0, tool.exec("git commit --repo $clone1Rel --message \"c2\"").exitCode)
        assertEquals(0, tool.exec("git push --repo $clone1Rel --confirm").exitCode)

        // Pull into clone2 and verify newest commit message appears in log
        assertEquals(0, tool.exec("git pull --repo $clone2Rel --confirm").exitCode)
        val log = tool.exec("git log --repo $clone2Rel --max 1")
        assertEquals(0, log.exitCode)
        assertTrue(log.stdout.contains("c2"))
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
