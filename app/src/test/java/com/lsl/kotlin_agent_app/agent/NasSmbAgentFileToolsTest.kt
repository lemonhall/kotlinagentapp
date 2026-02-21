package com.lsl.kotlin_agent_app.agent

import com.lsl.kotlin_agent_app.agent.vfs.AgentsVfsFileSystem
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.NasSmbClient
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.NasSmbDirEntry
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.NasSmbErrorCode
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.NasSmbMetadata
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.NasSmbMountConfig
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.NasSmbVfs
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.NasSmbVfsException
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.OkioNasSmbMountsProvider
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.tools.ListTool
import me.lemonhall.openagentic.sdk.tools.ReadTool
import me.lemonhall.openagentic.sdk.tools.ToolContext
import me.lemonhall.openagentic.sdk.tools.ToolOutput
import me.lemonhall.openagentic.sdk.tools.WriteTool
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertTrue
import org.junit.Test

class NasSmbAgentFileToolsTest {

    @Test
    fun openagenticFileTools_workUnderNasSmbMountViaAgentsVfsFileSystem() {
        runBlocking {
        val tmpRoot = Files.createTempDirectory("agents-root").toFile()
        val agentsRoot = tmpRoot.absolutePath.replace('\\', '/').toPath()
        val baseFs = FileSystem.SYSTEM

        val envDir = java.io.File(tmpRoot, "nas_smb/secrets").apply { mkdirs() }
        java.io.File(envDir, ".env").writeText(
            """
                NAS_SMB_MOUNTS=home
                NAS_SMB_HOME_HOST=192.168.50.1
                NAS_SMB_HOME_USERNAME=u
                NAS_SMB_HOME_PASSWORD=p
                NAS_SMB_HOME_SHARE=share
                NAS_SMB_HOME_REMOTE_DIR=/
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )

        val fake = FakeNasSmbClient()
        val nasSmbVfs = NasSmbVfs(mountsProvider = OkioNasSmbMountsProvider(fileSystem = baseFs, agentsRoot = agentsRoot), client = fake)
        val fs = AgentsVfsFileSystem(delegate = baseFs, agentsRoot = agentsRoot, nasSmbVfs = nasSmbVfs)

        val ctx = ToolContext(fileSystem = fs, cwd = agentsRoot, projectDir = agentsRoot)

        val writeOut =
            WriteTool().run(
                input =
                    buildJsonObject {
                        put("file_path", JsonPrimitive("nas_smb/home/test.txt"))
                        put("content", JsonPrimitive("hello"))
                        put("overwrite", JsonPrimitive(true))
                    },
                ctx = ctx,
            )
        assertTrue(writeOut is ToolOutput.Json)

        val readOut =
            ReadTool().run(
                input =
                    buildJsonObject {
                        put("file_path", JsonPrimitive("nas_smb/home/test.txt"))
                    },
                ctx = ctx,
            ) as ToolOutput.Json

        val json = readOut.value as JsonObject
        val content = (json["content"] as JsonPrimitive).content
        assertTrue(content.contains("hello"))

        val listOut =
            ListTool(limit = 20).run(
                input =
                    buildJsonObject {
                        put("path", JsonPrimitive("nas_smb/home"))
                    },
                ctx = ctx,
            ) as ToolOutput.Json
        val listJson = listOut.value as JsonObject
        val tree = (listJson["output"] as JsonPrimitive).content
        assertTrue(tree.contains("test.txt"))
        }
    }

    private class FakeNasSmbClient : NasSmbClient {
        private sealed interface Node {
            data class Dir(val children: MutableMap<String, Node> = linkedMapOf()) : Node
            data class File(var bytes: ByteArray) : Node
        }

        private val roots = linkedMapOf<String, Node.Dir>()

        private fun rootFor(mount: NasSmbMountConfig): Node.Dir {
            return roots.getOrPut(mount.mountName.lowercase()) { Node.Dir() }
        }

        private fun split(remote: String): List<String> {
            val raw = remote.trim().replace('\\', '/').trim('/')
            if (raw.isBlank()) return emptyList()
            return raw.split('/').filter { it.isNotBlank() }
        }

        private fun ensureDir(
            root: Node.Dir,
            parts: List<String>,
        ): Node.Dir {
            var cur = root
            for (p in parts) {
                val next = cur.children[p]
                if (next == null) {
                    val d = Node.Dir()
                    cur.children[p] = d
                    cur = d
                } else {
                    cur = next as? Node.Dir ?: throw NasSmbVfsException(NasSmbErrorCode.PermissionDenied, "Not a directory")
                }
            }
            return cur
        }

        override fun listDir(mount: NasSmbMountConfig, remoteDir: String): List<NasSmbDirEntry> {
            val root = rootFor(mount)
            val dir = ensureDir(root, split(remoteDir))
            return dir.children.map { (name, node) -> NasSmbDirEntry(name = name, isDirectory = node is Node.Dir) }
        }

        override fun metadataOrNull(mount: NasSmbMountConfig, remotePath: String): NasSmbMetadata? {
            val root = rootFor(mount)
            val parts = split(remotePath)
            var cur: Node = root
            for (p in parts) {
                val dir = cur as? Node.Dir ?: return null
                cur = dir.children[p] ?: return null
            }
            return when (cur) {
                is Node.Dir -> NasSmbMetadata(isRegularFile = false, isDirectory = true)
                is Node.File -> NasSmbMetadata(isRegularFile = true, isDirectory = false, size = cur.bytes.size.toLong())
            }
        }

        override fun readBytes(mount: NasSmbMountConfig, remotePath: String, maxBytes: Long): ByteArray {
            val meta = metadataOrNull(mount, remotePath) ?: throw IllegalStateException("Not found")
            if (!meta.isRegularFile) throw IllegalStateException("Not a file")
            val root = rootFor(mount)
            val parts = split(remotePath)
            var cur: Node = root
            for (p in parts) {
                cur = (cur as Node.Dir).children[p]!!
            }
            val b = (cur as Node.File).bytes
            if (b.size.toLong() > maxBytes) throw IllegalStateException("Too large")
            return b.copyOf()
        }

        override fun writeBytes(mount: NasSmbMountConfig, remotePath: String, bytes: ByteArray) {
            val parts = split(remotePath)
            require(parts.isNotEmpty())
            val root = rootFor(mount)
            val parent = ensureDir(root, parts.dropLast(1))
            parent.children[parts.last()] = Node.File(bytes.copyOf())
        }

        override fun mkdirs(mount: NasSmbMountConfig, remoteDir: String) {
            ensureDir(rootFor(mount), split(remoteDir))
        }

        override fun delete(mount: NasSmbMountConfig, remotePath: String, recursive: Boolean) {
            val parts = split(remotePath)
            if (parts.isEmpty()) return
            val root = rootFor(mount)
            val parent = ensureDir(root, parts.dropLast(1))
            parent.children.remove(parts.last())
        }

        override fun move(mount: NasSmbMountConfig, fromRemotePath: String, toRemotePath: String, overwrite: Boolean) {
            val from = split(fromRemotePath)
            val to = split(toRemotePath)
            require(from.isNotEmpty() && to.isNotEmpty())
            val root = rootFor(mount)
            val fromParent = ensureDir(root, from.dropLast(1))
            val node = fromParent.children[from.last()] ?: throw IllegalStateException("Missing src")
            val toParent = ensureDir(root, to.dropLast(1))
            if (!overwrite && toParent.children.containsKey(to.last())) throw IllegalStateException("Target exists")
            toParent.children[to.last()] = node
            fromParent.children.remove(from.last())
        }

        override fun copy(
            mount: NasSmbMountConfig,
            fromRemotePath: String,
            toRemotePath: String,
            overwrite: Boolean,
        ) {
            val from = split(fromRemotePath)
            val to = split(toRemotePath)
            require(from.isNotEmpty() && to.isNotEmpty())
            val root = rootFor(mount)
            val fromParent = ensureDir(root, from.dropLast(1))
            val node = fromParent.children[from.last()] ?: throw IllegalStateException("Missing src")
            val toParent = ensureDir(root, to.dropLast(1))
            if (!overwrite && toParent.children.containsKey(to.last())) throw IllegalStateException("Target exists")

            fun deepCopy(n: Node): Node {
                return when (n) {
                    is Node.Dir -> Node.Dir(children = n.children.mapValues { (_, v) -> deepCopy(v) }.toMutableMap())
                    is Node.File -> Node.File(bytes = n.bytes.copyOf())
                }
            }

            toParent.children[to.last()] = deepCopy(node)
        }
    }
}
