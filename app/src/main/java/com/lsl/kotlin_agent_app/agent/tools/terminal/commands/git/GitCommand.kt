package com.lsl.kotlin_agent_app.agent.tools.terminal.commands.git

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalArtifact
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommandOutput
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.ResetCommand.ResetType
import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevTree
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.net.URI

internal class GitCommand(
    appContext: Context,
) : TerminalCommand {
    private val agentsRoot = File(appContext.applicationContext.filesDir, ".agents").canonicalFile

    override val name: String = "git"
    override val description: String = "A JGit-backed pseudo git CLI (whitelisted, no shell)."

    override suspend fun run(
        argv: List<String>,
        stdin: String?,
    ): TerminalCommandOutput {
        if (argv.size < 2) {
            return invalidArgs("missing subcommand")
        }
        return try {
            when (argv[1].lowercase()) {
                "init" -> handleInit(argv)
                "status" -> handleStatus(argv)
                "add" -> handleAdd(argv)
                "commit" -> handleCommit(argv)
                "log" -> handleLog(argv)
                "branch" -> handleBranch(argv)
                "checkout" -> handleCheckout(argv)
                "show" -> handleShow(argv)
                "diff" -> handleDiff(argv)
                "reset" -> handleReset(argv)
                "stash" -> handleStash(argv)
                "ls-remote" -> handleLsRemote(argv, stdin)
                "clone" -> handleClone(argv, stdin)
                "fetch" -> handleFetch(argv, stdin)
                "pull" -> handlePull(argv, stdin)
                "push" -> handlePush(argv, stdin)
                else -> invalidArgs("unknown subcommand: ${argv[1]}")
            }
        } catch (t: PathEscapesAgentsRoot) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "path escapes .agents root"),
                errorCode = "PathEscapesAgentsRoot",
                errorMessage = t.message,
            )
        } catch (t: ConfirmRequired) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "missing --confirm"),
                errorCode = "ConfirmRequired",
                errorMessage = t.message,
            )
        } catch (t: InvalidRemoteUrl) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "invalid remote url"),
                errorCode = "InvalidRemoteUrl",
                errorMessage = t.message,
            )
        } catch (t: IllegalArgumentException) {
            invalidArgs(t.message ?: "invalid args")
        } catch (t: Throwable) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "git error"),
                errorCode = "GitError",
                errorMessage = t.message,
            )
        }
    }

    private fun handleInit(argv: List<String>): TerminalCommandOutput {
        val dirRel = requireFlagValue(argv, "--dir")
        val dir = resolveWithinAgents(dirRel)
        val bare = argv.any { it == "--bare" }
        if (!dir.exists()) dir.mkdirs()
        if (!dir.isDirectory) return invalidArgs("--dir must be a directory: $dirRel")

        Git.init().setDirectory(dir).setBare(bare).call().use { git ->
            val repo = git.repository
            val gitDir = repo.directory?.canonicalFile
            val stdout = if (bare) "Initialized bare git repository at ${relPath(dir)}" else "Initialized git repository at ${relPath(dir)}"
            val result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("git init"))
                    put("repo_path", JsonPrimitive(relPath(dir)))
                    put("git_dir", JsonPrimitive(gitDir?.let(::relPath) ?: ".git"))
                    put("bare", JsonPrimitive(bare))
                }
            return TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
        }
    }

    private fun handleStatus(argv: List<String>): TerminalCommandOutput {
        val repoRel = requireFlagValue(argv, "--repo")
        val repoDir = resolveWithinAgents(repoRel)

        val git =
            try {
                Git.open(repoDir)
            } catch (_: RepositoryNotFoundException) {
                return TerminalCommandOutput(
                    exitCode = 128,
                    stdout = "",
                    stderr = "Not a git repository: $repoRel",
                    errorCode = "NotAGitRepo",
                    errorMessage = "Not a git repository",
                )
            }

        git.use { g ->
            val repo = g.repository
            val status =
                try {
                    g.status().call()
                } catch (t: GitAPIException) {
                    return TerminalCommandOutput(
                        exitCode = 2,
                        stdout = "",
                        stderr = (t.message ?: "status failed"),
                        errorCode = "GitStatusFailed",
                        errorMessage = t.message,
                    )
                }

            val counts =
                buildJsonObject {
                    put("untracked", JsonPrimitive(status.untracked.size))
                    put("modified", JsonPrimitive(status.modified.size))
                    put("added", JsonPrimitive(status.added.size))
                    put("changed", JsonPrimitive(status.changed.size))
                    put("removed", JsonPrimitive(status.removed.size))
                    put("missing", JsonPrimitive(status.missing.size))
                    put("conflicting", JsonPrimitive(status.conflicting.size))
                }

            val branch = safeBranch(repo)
            val isClean = status.isClean
            val stdout =
                buildString {
                    if (!branch.isNullOrBlank()) appendLine("On branch $branch")
                    if (isClean) {
                        appendLine("working tree clean")
                    } else {
                        appendLine("working tree not clean")
                        if (status.untracked.isNotEmpty()) appendLine("untracked: ${status.untracked.size}")
                        if (status.modified.isNotEmpty()) appendLine("modified: ${status.modified.size}")
                        if (status.added.isNotEmpty()) appendLine("added: ${status.added.size}")
                        if (status.changed.isNotEmpty()) appendLine("changed: ${status.changed.size}")
                        if (status.removed.isNotEmpty()) appendLine("removed: ${status.removed.size}")
                        if (status.missing.isNotEmpty()) appendLine("missing: ${status.missing.size}")
                        if (status.conflicting.isNotEmpty()) appendLine("conflicting: ${status.conflicting.size}")
                    }
                }.trimEnd()

            val result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("git status"))
                    put("repo_path", JsonPrimitive(relPath(repoDir)))
                    put("branch", branch?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("is_clean", JsonPrimitive(isClean))
                    put("counts", counts)
                }

            return TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
        }
    }

    private fun handleAdd(argv: List<String>): TerminalCommandOutput {
        val repoRel = requireFlagValue(argv, "--repo")
        val repoDir = resolveWithinAgents(repoRel)
        val all = argv.any { it == "--all" }
        if (!all) return invalidArgs("only --all is supported in v13")

        return try {
            Git.open(repoDir).use { g ->
                g.add().addFilepattern(".").call()
                val stdout = "added: ."
                val result =
                    buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("command", JsonPrimitive("git add"))
                        put("repo_path", JsonPrimitive(relPath(repoDir)))
                        put(
                            "added_patterns",
                            buildJsonArray {
                                add(JsonPrimitive("."))
                            },
                        )
                    }
                TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
            }
        } catch (_: RepositoryNotFoundException) {
            TerminalCommandOutput(
                exitCode = 128,
                stdout = "",
                stderr = "Not a git repository: $repoRel",
                errorCode = "NotAGitRepo",
                errorMessage = "Not a git repository",
            )
        } catch (t: Throwable) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "add failed"),
                errorCode = "GitAddFailed",
                errorMessage = t.message,
            )
        }
    }

    private fun handleCommit(argv: List<String>): TerminalCommandOutput {
        val repoRel = requireFlagValue(argv, "--repo")
        val repoDir = resolveWithinAgents(repoRel)
        val msg = requireFlagValue(argv, "--message").trim()
        if (msg.isEmpty()) return invalidArgs("--message must be non-empty")

        return try {
            Git.open(repoDir).use { g ->
                val ident = PersonIdent("agent", "agent@local")
                val commit =
                    g.commit()
                        .setMessage(msg)
                        .setAuthor(ident)
                        .setCommitter(ident)
                        .call()
                val id = commit.id?.name.orEmpty()
                val stdout = "committed: ${id.take(12)} $msg"
                val result =
                    buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("command", JsonPrimitive("git commit"))
                        put("repo_path", JsonPrimitive(relPath(repoDir)))
                        put("commit_id", JsonPrimitive(id))
                        put("message", JsonPrimitive(msg))
                    }
                TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
            }
        } catch (_: RepositoryNotFoundException) {
            TerminalCommandOutput(
                exitCode = 128,
                stdout = "",
                stderr = "Not a git repository: $repoRel",
                errorCode = "NotAGitRepo",
                errorMessage = "Not a git repository",
            )
        } catch (t: Throwable) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "commit failed"),
                errorCode = "GitCommitFailed",
                errorMessage = t.message,
            )
        }
    }

    private fun handleLog(argv: List<String>): TerminalCommandOutput {
        val repoRel = requireFlagValue(argv, "--repo")
        val repoDir = resolveWithinAgents(repoRel)
        val maxRaw = optionalFlagValue(argv, "--max") ?: "10"
        val max = maxRaw.toIntOrNull()?.coerceIn(1, 50) ?: return invalidArgs("--max must be a number")

        return try {
            Git.open(repoDir).use { g ->
                val commits = g.log().setMaxCount(max).call().toList()
                val stdout =
                    buildString {
                        for (c in commits) {
                            appendLine("${c.id.name.take(12)} ${c.shortMessage}")
                        }
                    }.trimEnd()

                val result =
                    buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("command", JsonPrimitive("git log"))
                        put("repo_path", JsonPrimitive(relPath(repoDir)))
                        put(
                            "commits",
                            buildJsonArray {
                                for (c in commits) add(commitJson(c))
                            },
                        )
                    }

                TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
            }
        } catch (_: RepositoryNotFoundException) {
            TerminalCommandOutput(
                exitCode = 128,
                stdout = "",
                stderr = "Not a git repository: $repoRel",
                errorCode = "NotAGitRepo",
                errorMessage = "Not a git repository",
            )
        } catch (t: Throwable) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = (t.message ?: "log failed"),
                errorCode = "GitLogFailed",
                errorMessage = t.message,
            )
        }
    }

    private fun handleBranch(argv: List<String>): TerminalCommandOutput {
        val repoRel = requireFlagValue(argv, "--repo")
        val repoDir = resolveWithinAgents(repoRel)

        return openRepo(repoRel, repoDir) { g ->
            val repo = g.repository
            val current = safeBranch(repo)
            val branches =
                g.branchList()
                    .call()
                    .mapNotNull { ref ->
                        ref.name.removePrefix(Constants.R_HEADS).takeIf { it.isNotBlank() }
                    }
                    .sorted()

            val stdout =
                buildString {
                    for (b in branches) {
                        val mark = if (!current.isNullOrBlank() && b == current) "*" else " "
                        appendLine("$mark $b")
                    }
                }.trimEnd()

            val result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("git branch"))
                    put("repo_path", JsonPrimitive(relPath(repoDir)))
                    put("current", current?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("branches", buildJsonArray { branches.forEach { add(JsonPrimitive(it)) } })
                }

            TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
        }
    }

    private fun handleCheckout(argv: List<String>): TerminalCommandOutput {
        val repoRel = requireFlagValue(argv, "--repo")
        val repoDir = resolveWithinAgents(repoRel)
        val branch = requireFlagValue(argv, "--branch").trim()
        if (branch.isEmpty()) return invalidArgs("--branch must be non-empty")
        val create = argv.any { it == "--create" }

        return openRepo(repoRel, repoDir) { g ->
            try {
                g.checkout().setName(branch).setCreateBranch(create).call()
            } catch (t: Throwable) {
                return@openRepo TerminalCommandOutput(
                    exitCode = 2,
                    stdout = "",
                    stderr = (t.message ?: "checkout failed"),
                    errorCode = "GitCheckoutFailed",
                    errorMessage = t.message,
                )
            }

            val current = safeBranch(g.repository)
            val stdout =
                buildString {
                    append("checked out ")
                    append(current ?: branch)
                    if (create) append(" (created)")
                }

            val result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("git checkout"))
                    put("repo_path", JsonPrimitive(relPath(repoDir)))
                    put("branch", JsonPrimitive(current ?: branch))
                    put("created", JsonPrimitive(create))
                }

            TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
        }
    }

    private fun handleReset(argv: List<String>): TerminalCommandOutput {
        val repoRel = requireFlagValue(argv, "--repo")
        val repoDir = resolveWithinAgents(repoRel)
        val modeRaw = requireFlagValue(argv, "--mode").lowercase()
        val to = requireFlagValue(argv, "--to").trim()
        if (to.isEmpty()) return invalidArgs("--to must be non-empty")

        val mode =
            when (modeRaw) {
                "soft" -> ResetType.SOFT
                "mixed" -> ResetType.MIXED
                "hard" -> ResetType.HARD
                else -> return invalidArgs("unsupported reset mode: $modeRaw")
            }

        return openRepo(repoRel, repoDir) { g ->
            try {
                g.reset().setMode(mode).setRef(to).call()
            } catch (t: Throwable) {
                return@openRepo TerminalCommandOutput(
                    exitCode = 2,
                    stdout = "",
                    stderr = (t.message ?: "reset failed"),
                    errorCode = "GitResetFailed",
                    errorMessage = t.message,
                )
            }
            val stdout = "reset $modeRaw to $to"
            val result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("git reset"))
                    put("repo_path", JsonPrimitive(relPath(repoDir)))
                    put("mode", JsonPrimitive(modeRaw))
                    put("to", JsonPrimitive(to))
                }
            TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
        }
    }

    private fun handleShow(argv: List<String>): TerminalCommandOutput {
        val repoRel = requireFlagValue(argv, "--repo")
        val repoDir = resolveWithinAgents(repoRel)
        val rev = requireFlagValue(argv, "--commit").trim()
        if (rev.isEmpty()) return invalidArgs("--commit must be non-empty")
        val patch = argv.any { it == "--patch" }
        val maxChars = (optionalFlagValue(argv, "--max-chars") ?: "8000").toIntOrNull()?.coerceIn(256, 200_000)
            ?: return invalidArgs("--max-chars must be a number")

        return openRepo(repoRel, repoDir) { g ->
            val repo = g.repository
            val (commit, parentTree) =
                try {
                    resolveCommitWithParentTree(repo, rev)
                } catch (t: Throwable) {
                    return@openRepo TerminalCommandOutput(
                        exitCode = 2,
                        stdout = "",
                        stderr = (t.message ?: "unknown revision"),
                        errorCode = "UnknownRevision",
                        errorMessage = t.message,
                    )
                }

            val head =
                buildString {
                    appendLine("${commit.id.name.take(12)} ${commit.shortMessage}")
                    appendLine("${commit.authorIdent?.name.orEmpty()} <${commit.authorIdent?.emailAddress.orEmpty()}>")
                }.trimEnd()

            val (summaryLines, patchText, artifacts) =
                renderCommitDiff(repo = repo, parentTree = parentTree, commitTree = commit.tree, includePatch = patch, maxChars = maxChars)

            val stdout =
                buildString {
                    appendLine(head)
                    if (summaryLines.isNotBlank()) {
                        appendLine()
                        appendLine(summaryLines)
                    }
                    if (!patchText.isNullOrBlank()) {
                        appendLine()
                        append(patchText)
                    }
                }.trimEnd()

            val result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("git show"))
                    put("repo_path", JsonPrimitive(relPath(repoDir)))
                    put(
                        "commit",
                        buildJsonObject {
                            put("id", JsonPrimitive(commit.id.name))
                            put("message", JsonPrimitive(commit.fullMessage.trim()))
                            put("short_message", JsonPrimitive(commit.shortMessage))
                            put("author_name", JsonPrimitive(commit.authorIdent?.name ?: ""))
                            put("author_email", JsonPrimitive(commit.authorIdent?.emailAddress ?: ""))
                            put("timestamp_ms", JsonPrimitive(commit.commitTime.toLong() * 1000L))
                        },
                    )
                    put("patch", JsonPrimitive(patch))
                }

            TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result, artifacts = artifacts)
        }
    }

    private fun handleDiff(argv: List<String>): TerminalCommandOutput {
        val repoRel = requireFlagValue(argv, "--repo")
        val repoDir = resolveWithinAgents(repoRel)
        val from = requireFlagValue(argv, "--from").trim()
        val to = requireFlagValue(argv, "--to").trim()
        if (from.isEmpty() || to.isEmpty()) return invalidArgs("--from/--to must be non-empty")
        val patch = argv.any { it == "--patch" }
        val maxChars = (optionalFlagValue(argv, "--max-chars") ?: "8000").toIntOrNull()?.coerceIn(256, 200_000)
            ?: return invalidArgs("--max-chars must be a number")

        return openRepo(repoRel, repoDir) { g ->
            val repo = g.repository
            val (fromTree, toTree) =
                try {
                    resolveTrees(repo, from, to)
                } catch (t: Throwable) {
                    return@openRepo TerminalCommandOutput(
                        exitCode = 2,
                        stdout = "",
                        stderr = (t.message ?: "unknown revision"),
                        errorCode = "UnknownRevision",
                        errorMessage = t.message,
                    )
                }

            val (summaryLines, patchText, artifacts) =
                renderTreeDiff(repo = repo, oldTree = fromTree, newTree = toTree, includePatch = patch, maxChars = maxChars)

            val stdout =
                buildString {
                    appendLine("diff $from..$to")
                    if (summaryLines.isNotBlank()) {
                        appendLine()
                        appendLine(summaryLines)
                    }
                    if (!patchText.isNullOrBlank()) {
                        appendLine()
                        append(patchText)
                    }
                }.trimEnd()

            val result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("git diff"))
                    put("repo_path", JsonPrimitive(relPath(repoDir)))
                    put("from", JsonPrimitive(from))
                    put("to", JsonPrimitive(to))
                    put("patch", JsonPrimitive(patch))
                }

            TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result, artifacts = artifacts)
        }
    }

    private fun handleStash(argv: List<String>): TerminalCommandOutput {
        if (argv.size < 3) return invalidArgs("missing stash subcommand (push/list/pop)")
        val sub = argv[2].lowercase()
        return when (sub) {
            "push" -> handleStashPush(argv)
            "list" -> handleStashList(argv)
            "pop" -> handleStashPop(argv)
            else -> invalidArgs("unknown stash subcommand: $sub")
        }
    }

    private fun handleStashPush(argv: List<String>): TerminalCommandOutput {
        val repoRel = requireFlagValue(argv, "--repo")
        val repoDir = resolveWithinAgents(repoRel)
        val msg = requireFlagValue(argv, "--message").trim()
        if (msg.isEmpty()) return invalidArgs("--message must be non-empty")

        return openRepo(repoRel, repoDir) { g ->
            val id =
                try {
                    g.stashCreate().setWorkingDirectoryMessage(msg).call()
                } catch (t: Throwable) {
                    return@openRepo TerminalCommandOutput(
                        exitCode = 2,
                        stdout = "",
                        stderr = (t.message ?: "stash push failed"),
                        errorCode = "GitStashFailed",
                        errorMessage = t.message,
                    )
                }

            val stdout = "stashed: ${id?.name?.take(12).orEmpty()} $msg"
            val result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("git stash push"))
                    put("repo_path", JsonPrimitive(relPath(repoDir)))
                    put("message", JsonPrimitive(msg))
                    put("stash_id", JsonPrimitive(id?.name.orEmpty()))
                }
            TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
        }
    }

    private fun handleStashList(argv: List<String>): TerminalCommandOutput {
        val repoRel = requireFlagValue(argv, "--repo")
        val repoDir = resolveWithinAgents(repoRel)
        val maxRaw = optionalFlagValue(argv, "--max") ?: "10"
        val max = maxRaw.toIntOrNull()?.coerceIn(1, 50) ?: return invalidArgs("--max must be a number")

        return openRepo(repoRel, repoDir) { g ->
            val stashes =
                try {
                    g.stashList().call().take(max)
                } catch (t: Throwable) {
                    return@openRepo TerminalCommandOutput(
                        exitCode = 2,
                        stdout = "",
                        stderr = (t.message ?: "stash list failed"),
                        errorCode = "GitStashFailed",
                        errorMessage = t.message,
                    )
                }

            val stdout =
                buildString {
                    for ((i, c) in stashes.withIndex()) {
                        appendLine("stash@{$i}: ${c.shortMessage}")
                    }
                }.trimEnd()

            val result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("git stash list"))
                    put("repo_path", JsonPrimitive(relPath(repoDir)))
                    put(
                        "stashes",
                        buildJsonArray {
                            for ((i, c) in stashes.withIndex()) {
                                add(
                                    buildJsonObject {
                                        put("index", JsonPrimitive(i))
                                        put("id", JsonPrimitive(c.id.name))
                                        put("message", JsonPrimitive(c.shortMessage))
                                        put("timestamp_ms", JsonPrimitive(c.commitTime.toLong() * 1000L))
                                    },
                                )
                            }
                        },
                    )
                }

            TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
        }
    }

    private fun handleStashPop(argv: List<String>): TerminalCommandOutput {
        val repoRel = requireFlagValue(argv, "--repo")
        val repoDir = resolveWithinAgents(repoRel)
        val idxRaw = optionalFlagValue(argv, "--index") ?: "0"
        val idx = idxRaw.toIntOrNull()?.coerceAtLeast(0) ?: return invalidArgs("--index must be a number")

        return openRepo(repoRel, repoDir) { g ->
            val ref = "stash@{$idx}"
            try {
                g.stashApply().setStashRef(ref).call()
                g.stashDrop().setStashRef(idx).call()
            } catch (t: Throwable) {
                return@openRepo TerminalCommandOutput(
                    exitCode = 2,
                    stdout = "",
                    stderr = (t.message ?: "stash pop failed"),
                    errorCode = "GitStashFailed",
                    errorMessage = t.message,
                )
            }
            val stdout = "popped: $ref"
            val result =
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("command", JsonPrimitive("git stash pop"))
                    put("repo_path", JsonPrimitive(relPath(repoDir)))
                    put("index", JsonPrimitive(idx))
                }
            TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
        }
    }

    private fun handleLsRemote(
        argv: List<String>,
        stdin: String?,
    ): TerminalCommandOutput {
        requireConfirm(argv)
        val remote = parseRemoteSpec(argv)
        val url = remote.uri

        val refs =
            try {
                val cmd =
                    Git.lsRemoteRepository()
                        .setRemote(url)
                        .setHeads(true)
                        .setTags(true)
                val cp = credentialsProviderFor(argv, stdin)
                if (cp != null && remote.kind == RemoteKind.Https) cmd.setCredentialsProvider(cp)
                cmd.call().toList()
            } catch (t: Throwable) {
                return TerminalCommandOutput(
                    exitCode = 2,
                    stdout = "",
                    stderr = "ls-remote failed",
                    errorCode = "GitRemoteFailed",
                    errorMessage = t.message,
                )
            }

        val stdout =
            buildString {
                for (r in refs) {
                    appendLine("${r.objectId.name}\t${r.name}")
                }
            }.trimEnd()

        val result =
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("command", JsonPrimitive("git ls-remote"))
                put("remote", JsonPrimitive(remote.display))
                put(
                    "refs",
                    buildJsonArray {
                        for (r in refs) {
                            add(
                                buildJsonObject {
                                    put("name", JsonPrimitive(r.name))
                                    put("id", JsonPrimitive(r.objectId.name))
                                },
                            )
                        }
                    },
                )
            }

        return TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
    }

    private fun handleClone(
        argv: List<String>,
        stdin: String?,
    ): TerminalCommandOutput {
        requireConfirm(argv)
        val remote = parseRemoteSpec(argv)
        val dirRel = requireFlagValue(argv, "--dir")
        val dir = resolveWithinAgents(dirRel)
        if (dir.exists()) {
            val children = dir.listFiles().orEmpty()
            if (children.isNotEmpty()) return invalidArgs("--dir must be empty or not exist")
        } else {
            dir.mkdirs()
        }

        val cp = credentialsProviderFor(argv, stdin)
        return try {
            Git.cloneRepository()
                .setURI(remote.uri)
                .setDirectory(dir)
                .apply {
                    if (remote.kind == RemoteKind.Https && cp != null) setCredentialsProvider(cp)
                }
                .call()
                .use { _ ->
                    val stdout = "cloned to ${relPath(dir)}"
                    val result =
                        buildJsonObject {
                            put("ok", JsonPrimitive(true))
                            put("command", JsonPrimitive("git clone"))
                            put("remote", JsonPrimitive(remote.display))
                            put("repo_path", JsonPrimitive(relPath(dir)))
                        }
                    TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
                }
        } catch (t: Throwable) {
            TerminalCommandOutput(
                exitCode = 2,
                stdout = "",
                stderr = "clone failed",
                errorCode = "GitRemoteFailed",
                errorMessage = t.message,
            )
        }
    }

    private fun handleFetch(
        argv: List<String>,
        stdin: String?,
    ): TerminalCommandOutput {
        requireConfirm(argv)
        val repoRel = requireFlagValue(argv, "--repo")
        val repoDir = resolveWithinAgents(repoRel)
        val cp = credentialsProviderFor(argv, stdin)

        return openRepo(repoRel, repoDir) { g ->
            try {
                val res =
                    g.fetch()
                        .setRemote("origin")
                        .apply { if (cp != null) setCredentialsProvider(cp) }
                        .call()
                val msgs = res.messages.orEmpty().trim()
                val stdout = if (msgs.isBlank()) "fetched" else "fetched: $msgs"
                val result =
                    buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("command", JsonPrimitive("git fetch"))
                        put("repo_path", JsonPrimitive(relPath(repoDir)))
                    }
                TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
            } catch (t: Throwable) {
                TerminalCommandOutput(
                    exitCode = 2,
                    stdout = "",
                    stderr = "fetch failed",
                    errorCode = "GitRemoteFailed",
                    errorMessage = t.message,
                )
            }
        }
    }

    private fun handlePull(
        argv: List<String>,
        stdin: String?,
    ): TerminalCommandOutput {
        requireConfirm(argv)
        val repoRel = requireFlagValue(argv, "--repo")
        val repoDir = resolveWithinAgents(repoRel)
        val cp = credentialsProviderFor(argv, stdin)

        return openRepo(repoRel, repoDir) { g ->
            try {
                val res =
                    g.pull()
                        .apply { if (cp != null) setCredentialsProvider(cp) }
                        .call()
                val ok = res.isSuccessful
                val stdout = if (ok) "pulled" else "pull failed"
                val result =
                    buildJsonObject {
                        put("ok", JsonPrimitive(ok))
                        put("command", JsonPrimitive("git pull"))
                        put("repo_path", JsonPrimitive(relPath(repoDir)))
                    }
                TerminalCommandOutput(exitCode = if (ok) 0 else 2, stdout = stdout, result = result, errorCode = if (ok) null else "GitRemoteFailed")
            } catch (t: Throwable) {
                TerminalCommandOutput(
                    exitCode = 2,
                    stdout = "",
                    stderr = "pull failed",
                    errorCode = "GitRemoteFailed",
                    errorMessage = t.message,
                )
            }
        }
    }

    private fun handlePush(
        argv: List<String>,
        stdin: String?,
    ): TerminalCommandOutput {
        requireConfirm(argv)
        val repoRel = requireFlagValue(argv, "--repo")
        val repoDir = resolveWithinAgents(repoRel)
        val cp = credentialsProviderFor(argv, stdin)

        return openRepo(repoRel, repoDir) { g ->
            try {
                val results =
                    g.push()
                        .setRemote("origin")
                        .apply { if (cp != null) setCredentialsProvider(cp) }
                        .call()
                        .toList()
                val stdout =
                    buildString {
                        appendLine("pushed")
                        for (r in results) {
                            val msgs = r.messages.orEmpty().trim()
                            if (msgs.isNotBlank()) appendLine(msgs)
                        }
                    }.trimEnd()
                val result =
                    buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("command", JsonPrimitive("git push"))
                        put("repo_path", JsonPrimitive(relPath(repoDir)))
                    }
                TerminalCommandOutput(exitCode = 0, stdout = stdout, result = result)
            } catch (t: Throwable) {
                TerminalCommandOutput(
                    exitCode = 2,
                    stdout = "",
                    stderr = "push failed",
                    errorCode = "GitRemoteFailed",
                    errorMessage = t.message,
                )
            }
        }
    }

    private fun openRepo(
        repoRel: String,
        repoDir: File,
        block: (Git) -> TerminalCommandOutput,
    ): TerminalCommandOutput {
        val git =
            try {
                Git.open(repoDir)
            } catch (_: RepositoryNotFoundException) {
                return TerminalCommandOutput(
                    exitCode = 128,
                    stdout = "",
                    stderr = "Not a git repository: $repoRel",
                    errorCode = "NotAGitRepo",
                    errorMessage = "Not a git repository",
                )
            }
        git.use { g ->
            return block(g)
        }
    }

    private fun commitJson(c: RevCommit): JsonElement {
        val author = c.authorIdent
        return buildJsonObject {
            put("id", JsonPrimitive(c.id.name))
            put("message", JsonPrimitive(c.fullMessage.trim()))
            put("short_message", JsonPrimitive(c.shortMessage))
            put("author_name", JsonPrimitive(author?.name ?: ""))
            put("author_email", JsonPrimitive(author?.emailAddress ?: ""))
            put("timestamp_ms", JsonPrimitive(c.commitTime.toLong() * 1000L))
        }
    }

    private fun invalidArgs(message: String): TerminalCommandOutput {
        return TerminalCommandOutput(
            exitCode = 2,
            stdout = "",
            stderr = message,
            errorCode = "InvalidArgs",
            errorMessage = message,
        )
    }

    private fun requireFlagValue(argv: List<String>, flag: String): String {
        val idx = argv.indexOf(flag)
        if (idx < 0 || idx + 1 >= argv.size) throw IllegalArgumentException("missing required flag: $flag")
        return argv[idx + 1]
    }

    private fun optionalFlagValue(argv: List<String>, flag: String): String? {
        val idx = argv.indexOf(flag)
        if (idx < 0 || idx + 1 >= argv.size) return null
        return argv[idx + 1]
    }

    private fun safeBranch(repo: org.eclipse.jgit.lib.Repository): String? =
        try {
            repo.branch
        } catch (_: Throwable) {
            null
        }

    private fun resolveWithinAgents(rel: String): File {
        val raw = rel.replace('\\', '/').trim()
        if (raw.isEmpty()) throw IllegalArgumentException("path is empty")
        if (raw.startsWith("/") || raw.contains(":")) throw IllegalArgumentException("absolute paths are not allowed")
        if (raw.split('/').any { it == ".." }) {
            throw PathEscapesAgentsRoot("path escapes .agents root: $rel")
        }
        val target = File(agentsRoot, raw.trimStart('/')).canonicalFile
        val rootPath = agentsRoot.path
        val rootPrefix = rootPath.trimEnd(File.separatorChar) + File.separator
        if (target.path != rootPath && !target.path.startsWith(rootPrefix)) {
            throw PathEscapesAgentsRoot("path escapes .agents root: $rel")
        }
        return target
    }

    private fun relPath(file: File): String {
        val f = file.canonicalFile
        val root = agentsRoot
        val rootPath = root.path.trimEnd(File.separatorChar) + File.separator
        val p = f.path
        val rel =
            when {
                p == root.path -> ""
                p.startsWith(rootPath) -> p.substring(rootPath.length)
                else -> p
            }
        return rel.replace('\\', '/').trimStart('/')
    }

    private class PathEscapesAgentsRoot(message: String) : IllegalArgumentException(message)

    private fun requireConfirm(argv: List<String>) {
        if (argv.none { it == "--confirm" }) {
            throw ConfirmRequired("missing --confirm")
        }
    }

    private class ConfirmRequired(message: String) : IllegalArgumentException(message)

    private enum class RemoteKind { Https, Local }

    private data class RemoteSpec(
        val kind: RemoteKind,
        val uri: String,
        val display: String,
    )

    private fun parseRemoteSpec(argv: List<String>): RemoteSpec {
        val remoteUrl = optionalFlagValue(argv, "--remote")
        val localRemote = optionalFlagValue(argv, "--local-remote")
        val count = listOf(remoteUrl, localRemote).count { !it.isNullOrBlank() }
        if (count != 1) throw IllegalArgumentException("exactly one of --remote/--local-remote is required")

        if (!remoteUrl.isNullOrBlank()) {
            val url = remoteUrl.trim()
            validateHttpsRemoteUrl(url)
            return RemoteSpec(kind = RemoteKind.Https, uri = url, display = url)
        }

        val rel = localRemote!!.trim()
        val f = resolveWithinAgents(rel)
        if (!f.exists() || !f.isDirectory) throw IllegalArgumentException("local remote not found: $rel")
        // Use a file URI to avoid platform-specific path parsing issues.
        val uri = f.canonicalFile.toURI().toString()
        return RemoteSpec(kind = RemoteKind.Local, uri = uri, display = "local:$rel")
    }

    private fun validateHttpsRemoteUrl(url: String) {
        val u =
            try {
                URI(url)
            } catch (_: Throwable) {
                throw InvalidRemoteUrl("invalid url")
            }
        val scheme = u.scheme?.lowercase() ?: throw InvalidRemoteUrl("missing scheme")
        if (scheme != "https") throw InvalidRemoteUrl("only https is supported in v15")
        if (!u.userInfo.isNullOrBlank()) throw InvalidRemoteUrl("userinfo is not allowed")
        if (u.host.isNullOrBlank()) throw InvalidRemoteUrl("missing host")
    }

    private class InvalidRemoteUrl(message: String) : IllegalArgumentException(message)

    private fun credentialsProviderFor(
        argv: List<String>,
        stdin: String?,
    ): CredentialsProvider? {
        val auth = optionalFlagValue(argv, "--auth")?.trim().orEmpty()
        if (auth.isEmpty()) return null
        val s = stdin?.trim().orEmpty()
        if (s.isEmpty()) throw IllegalArgumentException("stdin is required for --auth")
        return when (auth) {
            "stdin-token" -> UsernamePasswordCredentialsProvider("oauth2", s)
            "stdin-basic" -> {
                val idx = s.indexOf(':')
                if (idx <= 0) throw IllegalArgumentException("stdin must be username:password")
                val user = s.substring(0, idx)
                val pass = s.substring(idx + 1)
                UsernamePasswordCredentialsProvider(user, pass)
            }
            else -> throw IllegalArgumentException("unsupported auth: $auth")
        }
    }

    private fun resolveTrees(
        repo: Repository,
        from: String,
        to: String,
    ): Pair<RevTree, RevTree> {
        val walk = RevWalk(repo)
        walk.use { w ->
            val fromId = repo.resolve(from) ?: throw IllegalArgumentException("unknown revision: $from")
            val toId = repo.resolve(to) ?: throw IllegalArgumentException("unknown revision: $to")
            val fromCommit = w.parseCommit(fromId)
            val toCommit = w.parseCommit(toId)
            return fromCommit.tree to toCommit.tree
        }
    }

    private fun resolveCommitWithParentTree(
        repo: Repository,
        rev: String,
    ): Pair<RevCommit, RevTree?> {
        val walk = RevWalk(repo)
        walk.use { w ->
            val id = repo.resolve(rev) ?: throw IllegalArgumentException("unknown revision: $rev")
            val commit = w.parseCommit(id)
            val parentTree =
                if (commit.parentCount > 0) {
                    val p = w.parseCommit(commit.getParent(0).id)
                    p.tree
                } else {
                    null
                }
            return commit to parentTree
        }
    }

    private fun renderCommitDiff(
        repo: Repository,
        parentTree: RevTree?,
        commitTree: RevTree,
        includePatch: Boolean,
        maxChars: Int,
    ): Triple<String, String?, List<TerminalArtifact>> {
        val reader = repo.newObjectReader()
        reader.use { r ->
            val oldIter = CanonicalTreeParser()
            if (parentTree != null) oldIter.reset(r, parentTree.id) else oldIter.reset()
            val newIter = CanonicalTreeParser()
            newIter.reset(r, commitTree.id)
            return renderTreeDiff(repo = repo, oldTreeIter = oldIter, newTreeIter = newIter, includePatch = includePatch, maxChars = maxChars)
        }
    }

    private fun renderTreeDiff(
        repo: Repository,
        oldTree: RevTree,
        newTree: RevTree,
        includePatch: Boolean,
        maxChars: Int,
    ): Triple<String, String?, List<TerminalArtifact>> {
        val reader = repo.newObjectReader()
        reader.use { r ->
            val oldIter = CanonicalTreeParser()
            oldIter.reset(r, oldTree.id)
            val newIter = CanonicalTreeParser()
            newIter.reset(r, newTree.id)
            return renderTreeDiff(repo = repo, oldTreeIter = oldIter, newTreeIter = newIter, includePatch = includePatch, maxChars = maxChars)
        }
    }

    private fun renderTreeDiff(
        repo: Repository,
        oldTreeIter: CanonicalTreeParser,
        newTreeIter: CanonicalTreeParser,
        includePatch: Boolean,
        maxChars: Int,
    ): Triple<String, String?, List<TerminalArtifact>> {
        val entries =
            Git(repo).use { g ->
                g.diff().setOldTree(oldTreeIter).setNewTree(newTreeIter).call()
            }

        val summary =
            buildString {
                for (e in entries) {
                    appendLine("${e.changeType.name.lowercase()}\t${e.newPath}")
                }
            }.trimEnd()

        if (!includePatch) return Triple(summary, null, emptyList())

        val out = ByteArrayOutputStream()
        DiffFormatter(out).use { fmt ->
            fmt.setRepository(repo)
            fmt.setDiffComparator(RawTextComparator.DEFAULT)
            fmt.isDetectRenames = true
            for (e in entries) {
                fmt.format(e)
            }
        }
        val patchText = out.toString(StandardCharsets.UTF_8)
        if (patchText.length <= maxChars) return Triple(summary, patchText.trimEnd(), emptyList())

        val artifactPath = writeArtifact(kind = "patch", content = patchText)
        val snippet =
            patchText.take(maxChars / 2) + "\n…(truncated, see artifact)…\n" + patchText.takeLast(maxChars - maxChars / 2)

        return Triple(
            summary,
            snippet.trimEnd(),
            listOf(
                TerminalArtifact(
                    path = ".agents/$artifactPath",
                    mime = "text/x-diff",
                    description = "Full patch output (truncated in stdout).",
                ),
            ),
        )
    }

    private fun writeArtifact(
        kind: String,
        content: String,
    ): String {
        val dir = File(agentsRoot, "artifacts/git/$kind")
        dir.mkdirs()
        val name = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val f = File(dir, "$name.txt")
        f.writeText(content, Charsets.UTF_8)
        // return path relative to .agents
        val rel = "artifacts/git/$kind/${f.name}"
        return rel.replace('\\', '/')
    }
}
