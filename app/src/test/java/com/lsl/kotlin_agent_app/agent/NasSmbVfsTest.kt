package com.lsl.kotlin_agent_app.agent

import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.NasSmbClient
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.NasSmbDirEntry
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.NasSmbErrorCode
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.NasSmbMetadata
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.NasSmbMountConfig
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.NasSmbMountConfigLoader
import com.lsl.kotlin_agent_app.agent.vfs.nas_smb.NasSmbVfsException
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NasSmbVfsTest {

    @Test
    fun dotenvLoader_parsesMultipleMountsWithDefaults() {
        val env =
            """
                NAS_SMB_MOUNTS=home,work
                NAS_SMB_HOME_HOST=192.168.50.1
                NAS_SMB_HOME_USERNAME=u
                NAS_SMB_HOME_PASSWORD=p
                NAS_SMB_HOME_SHARE=share

                NAS_SMB_WORK_HOST=192.168.50.2
                NAS_SMB_WORK_GUEST=true
                NAS_SMB_WORK_SHARE=public
                NAS_SMB_WORK_REMOTE_DIR=/docs
                NAS_SMB_WORK_READ_ONLY=true
            """.trimIndent()

        val mounts = NasSmbMountConfigLoader().loadFromEnvText(env)
        assertEquals(2, mounts.size)

        val home = mounts.first { it.id == "home" }
        assertEquals("home", home.mountName)
        assertEquals(445, home.port)
        assertEquals("", home.remoteDir)
        assertFalse(home.guest)
        assertFalse(home.readOnly)

        val work = mounts.first { it.id == "work" }
        assertEquals("work", work.mountName)
        assertEquals("docs", work.remoteDir)
        assertTrue(work.guest)
        assertTrue(work.readOnly)
    }

    @Test
    fun vfsRoutesWorkspaceFileOpsToFakeRemote_notLocalPlaceholderDir() {
        val ctx = RuntimeEnvironment.getApplication()
        val fake = FakeNasSmbClient()
        val ws = AgentsWorkspace(ctx, nasSmbClient = fake)

        ws.ensureInitialized()
        ws.writeTextFile(
            ".agents/nas_smb/secrets/.env",
            """
                NAS_SMB_MOUNTS=home
                NAS_SMB_HOME_HOST=192.168.50.1
                NAS_SMB_HOME_USERNAME=u
                NAS_SMB_HOME_PASSWORD=p
                NAS_SMB_HOME_SHARE=share
                NAS_SMB_HOME_REMOTE_DIR=/
            """.trimIndent() + "\n",
        )
        ws.ensureInitialized()

        val mountDir = File(ctx.filesDir, ".agents/nas_smb/home")
        assertTrue(mountDir.exists())
        assertTrue(File(mountDir, ".mount.json").exists())

        ws.writeTextFile(".agents/nas_smb/home/test.txt", "hello\n")
        assertEquals("hello\n", ws.readTextFile(".agents/nas_smb/home/test.txt"))

        // Anti-cheat: must NOT land on local placeholder.
        assertFalse(File(ctx.filesDir, ".agents/nas_smb/home/test.txt").exists())

        ws.mkdir(".agents/nas_smb/home/subdir")
        val entries = ws.listDir(".agents/nas_smb/home").map { it.name }.toSet()
        assertTrue(entries.contains("test.txt"))
        assertTrue(entries.contains("subdir"))

        ws.deletePath(".agents/nas_smb/home/test.txt", recursive = false)
        val afterDelete = ws.listDir(".agents/nas_smb/home").map { it.name }.toSet()
        assertFalse(afterDelete.contains("test.txt"))

        ws.deletePath(".agents/nas_smb/home/subdir", recursive = true)
        val afterDeleteDir = ws.listDir(".agents/nas_smb/home").map { it.name }.toSet()
        assertFalse(afterDeleteDir.contains("subdir"))
    }

    private class FakeNasSmbClient : NasSmbClient {
        private sealed interface Node {
            data class Dir(val children: MutableMap<String, Node> = linkedMapOf()) : Node
            data class File(var bytes: ByteArray, var mtime: Long = System.currentTimeMillis()) : Node
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

        private fun getNode(
            root: Node.Dir,
            parts: List<String>,
            createDirs: Boolean,
        ): Node? {
            var cur: Node = root
            for ((idx, p) in parts.withIndex()) {
                if (p == "." || p == "..") throw NasSmbVfsException(NasSmbErrorCode.InvalidConfig, "Path traversal")
                val isLast = idx == parts.lastIndex
                val dir = cur as? Node.Dir ?: return null
                val next = dir.children[p]
                if (next == null) {
                    if (!createDirs) return null
                    val created = if (isLast) Node.Dir() else Node.Dir()
                    dir.children[p] = created
                    cur = created
                } else {
                    cur = next
                }
            }
            return cur
        }

        private fun getDir(
            mount: NasSmbMountConfig,
            remoteDir: String,
            create: Boolean,
        ): Node.Dir {
            val root = rootFor(mount)
            val parts = split(remoteDir)
            if (parts.isEmpty()) return root
            val node = getNode(root, parts, createDirs = create)
            return node as? Node.Dir ?: throw NasSmbVfsException(NasSmbErrorCode.PermissionDenied, "Not a directory")
        }

        override fun listDir(
            mount: NasSmbMountConfig,
            remoteDir: String,
        ): List<NasSmbDirEntry> {
            val dir = getDir(mount, remoteDir, create = false)
            return dir.children.entries.map { (name, node) ->
                NasSmbDirEntry(name = name, isDirectory = node is Node.Dir)
            }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }

        override fun metadataOrNull(
            mount: NasSmbMountConfig,
            remotePath: String,
        ): NasSmbMetadata? {
            val root = rootFor(mount)
            val parts = split(remotePath)
            val node = if (parts.isEmpty()) root else getNode(root, parts, createDirs = false) ?: return null
            return when (node) {
                is Node.Dir -> NasSmbMetadata(isRegularFile = false, isDirectory = true, size = null, lastModifiedAtMillis = null)
                is Node.File -> NasSmbMetadata(isRegularFile = true, isDirectory = false, size = node.bytes.size.toLong(), lastModifiedAtMillis = node.mtime)
            }
        }

        override fun readBytes(
            mount: NasSmbMountConfig,
            remotePath: String,
            maxBytes: Long,
        ): ByteArray {
            val root = rootFor(mount)
            val parts = split(remotePath)
            val node = getNode(root, parts, createDirs = false) ?: throw IllegalStateException("Not found")
            val file = node as? Node.File ?: throw IllegalStateException("Not a file")
            if (file.bytes.size.toLong() > maxBytes) throw IllegalStateException("Too large")
            return file.bytes.copyOf()
        }

        override fun writeBytes(
            mount: NasSmbMountConfig,
            remotePath: String,
            bytes: ByteArray,
        ) {
            val parts = split(remotePath)
            require(parts.isNotEmpty()) { "Refusing to write root" }
            val parentParts = parts.dropLast(1)
            val name = parts.last()
            val parent = getDir(mount, parentParts.joinToString("/"), create = true)
            parent.children[name] = Node.File(bytes = bytes.copyOf())
        }

        override fun mkdirs(
            mount: NasSmbMountConfig,
            remoteDir: String,
        ) {
            getDir(mount, remoteDir, create = true)
        }

        override fun delete(
            mount: NasSmbMountConfig,
            remotePath: String,
            recursive: Boolean,
        ) {
            val root = rootFor(mount)
            val parts = split(remotePath)
            require(parts.isNotEmpty()) { "Refusing to delete root" }
            val parentParts = parts.dropLast(1)
            val name = parts.last()
            val parent = getNode(root, parentParts, createDirs = false) as? Node.Dir ?: return
            val node = parent.children[name] ?: return
            if (node is Node.Dir && node.children.isNotEmpty() && !recursive) {
                throw IllegalStateException("Refusing to delete non-empty dir without recursive")
            }
            parent.children.remove(name)
        }

        override fun move(
            mount: NasSmbMountConfig,
            fromRemotePath: String,
            toRemotePath: String,
            overwrite: Boolean,
        ) {
            val root = rootFor(mount)
            val from = split(fromRemotePath)
            val to = split(toRemotePath)
            require(from.isNotEmpty() && to.isNotEmpty()) { "Invalid move" }
            val fromParent = getNode(root, from.dropLast(1), createDirs = false) as? Node.Dir ?: throw IllegalStateException("Missing src dir")
            val node = fromParent.children[from.last()] ?: throw IllegalStateException("Missing src")
            val toParent = getDir(mount, to.dropLast(1).joinToString("/"), create = true)
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
            val root = rootFor(mount)
            val from = split(fromRemotePath)
            val to = split(toRemotePath)
            require(from.isNotEmpty() && to.isNotEmpty()) { "Invalid copy" }
            val fromParent = getNode(root, from.dropLast(1), createDirs = false) as? Node.Dir ?: throw IllegalStateException("Missing src dir")
            val node = fromParent.children[from.last()] ?: throw IllegalStateException("Missing src")
            val toParent = getDir(mount, to.dropLast(1).joinToString("/"), create = true)
            if (!overwrite && toParent.children.containsKey(to.last())) throw IllegalStateException("Target exists")

            fun deepCopy(n: Node): Node {
                return when (n) {
                    is Node.Dir -> Node.Dir(children = n.children.mapValues { (_, v) -> deepCopy(v) }.toMutableMap())
                    is Node.File -> Node.File(bytes = n.bytes.copyOf(), mtime = System.currentTimeMillis())
                }
            }

            toParent.children[to.last()] = deepCopy(node)
        }
    }
}
