package com.lsl.kotlin_agent_app.agent.tools.terminal

import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalExecToolGitTest {

    @Test
    fun git_init_status_add_commit_log_happyPath() =
        runTerminalExecToolTest { tool ->
            val repoName = "jgit-demo-" + UUID.randomUUID().toString().replace("-", "").take(8)
            val repoRel = "workspace/$repoName"

            val initOut = tool.exec("git init --dir $repoRel")
            assertEquals(0, initOut.exitCode)

            val repoDir = File(initOut.filesDir, ".agents/$repoRel")
            assertTrue(repoDir.exists())
            File(repoDir, "a.txt").writeText("hello", Charsets.UTF_8)

            val status1 = tool.exec("git status --repo $repoRel")
            assertEquals(0, status1.exitCode)
            assertTrue(status1.stdout.contains("untracked", ignoreCase = true))

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
    fun git_repoPathTraversal_isRejected() =
        runTerminalExecToolTest { tool ->
            val out = tool.exec("git status --repo ../")
            assertTrue(out.exitCode != 0)
            assertEquals("PathEscapesAgentsRoot", out.errorCode)
        }

    @Test
    fun git_branch_checkout_show_diff_reset_stash_happyPath() =
        runTerminalExecToolTest { tool ->
            val repoName = "jgit-v14-" + UUID.randomUUID().toString().replace("-", "").take(8)
            val repoRel = "workspace/$repoName"

            assertEquals(0, tool.exec("git init --dir $repoRel").exitCode)
            val repoDir = File(tool.filesDir, ".agents/$repoRel")
            File(repoDir, "a.txt").writeText("v1", Charsets.UTF_8)
            assertEquals(0, tool.exec("git add --repo $repoRel --all").exitCode)
            assertEquals(0, tool.exec("git commit --repo $repoRel --message \"c1\"").exitCode)

            val co = tool.exec("git checkout --repo $repoRel --branch feat --create")
            assertEquals(0, co.exitCode)
            val branches = tool.exec("git branch --repo $repoRel")
            assertEquals(0, branches.exitCode)
            assertTrue(branches.stdout.contains("feat"))

            File(repoDir, "a.txt").writeText("v2", Charsets.UTF_8)
            assertEquals(0, tool.exec("git add --repo $repoRel --all").exitCode)
            assertEquals(0, tool.exec("git commit --repo $repoRel --message \"c2\"").exitCode)

            val show = tool.exec("git show --repo $repoRel --commit HEAD --patch --max-chars 2000")
            assertEquals(0, show.exitCode)
            assertTrue(show.stdout.contains("c2"))

            val diff = tool.exec("git diff --repo $repoRel --from HEAD~1 --to HEAD --patch --max-chars 2000")
            assertEquals(0, diff.exitCode)
            assertTrue(diff.stdout.isNotBlank())

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

            val reset = tool.exec("git reset --repo $repoRel --mode hard --to HEAD~1")
            assertEquals(0, reset.exitCode)
            val log1 = tool.exec("git log --repo $repoRel --max 1")
            assertEquals(0, log1.exitCode)
            assertTrue(log1.stdout.contains("c1"))
        }

    @Test
    fun git_remoteClone_requiresConfirm() =
        runTerminalExecToolTest { tool ->
            val out =
                tool.exec(
                    "git clone --remote https://example.com/repo.git --dir workspace/clone-no-confirm",
                )
            assertTrue(out.exitCode != 0)
            assertEquals("ConfirmRequired", out.errorCode)
        }

    @Test
    fun git_remoteClone_rejectsUserinfoUrl() =
        runTerminalExecToolTest { tool ->
            val out =
                tool.exec(
                    "git clone --remote https://user:pass@example.com/repo.git --dir workspace/clone-bad --confirm",
                )
            assertTrue(out.exitCode != 0)
            assertEquals("InvalidRemoteUrl", out.errorCode)
        }

    @Test
    fun git_localRemote_clone_push_pull_happyPath() =
        runTerminalExecToolTest { tool ->
            val id = UUID.randomUUID().toString().replace("-", "").take(8)
            val originRel = "workspace/origin-$id.git"
            val clone1Rel = "workspace/clone1-$id"
            val clone2Rel = "workspace/clone2-$id"

            val initBare = tool.exec("git init --dir $originRel --bare")
            assertEquals(0, initBare.exitCode)

            assertEquals(0, tool.exec("git clone --local-remote $originRel --dir $clone1Rel --confirm").exitCode)
            val clone1Dir = File(tool.filesDir, ".agents/$clone1Rel")
            File(clone1Dir, "a.txt").writeText("v1", Charsets.UTF_8)
            assertEquals(0, tool.exec("git add --repo $clone1Rel --all").exitCode)
            assertEquals(0, tool.exec("git commit --repo $clone1Rel --message \"c1\"").exitCode)
            assertEquals(0, tool.exec("git push --repo $clone1Rel --confirm").exitCode)

            assertEquals(0, tool.exec("git clone --local-remote $originRel --dir $clone2Rel --confirm").exitCode)

            File(clone1Dir, "a.txt").writeText("v2", Charsets.UTF_8)
            assertEquals(0, tool.exec("git add --repo $clone1Rel --all").exitCode)
            assertEquals(0, tool.exec("git commit --repo $clone1Rel --message \"c2\"").exitCode)
            assertEquals(0, tool.exec("git push --repo $clone1Rel --confirm").exitCode)

            assertEquals(0, tool.exec("git pull --repo $clone2Rel --confirm").exitCode)
            val log = tool.exec("git log --repo $clone2Rel --max 1")
            assertEquals(0, log.exitCode)
            assertTrue(log.stdout.contains("c2"))
        }
}

