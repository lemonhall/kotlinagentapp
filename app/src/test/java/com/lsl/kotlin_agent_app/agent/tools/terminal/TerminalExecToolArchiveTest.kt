package com.lsl.kotlin_agent_app.agent.tools.terminal

import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalExecToolArchiveTest {

    @Test
    fun zip_create_requiresConfirm() =
        runTerminalExecToolTest { tool ->
            val srcRel = "workspace/zip-src-" + UUID.randomUUID().toString().replace("-", "").take(8)
            val srcDir = File(tool.filesDir, ".agents/$srcRel")
            srcDir.mkdirs()
            File(srcDir, "a.txt").writeText("hello", Charsets.UTF_8)

            val out = tool.exec("zip create --src $srcRel --out workspace/out.zip")
            assertTrue(out.exitCode != 0)
            assertEquals("ConfirmRequired", out.errorCode)
        }

    @Test
    fun zip_extract_blocksPathTraversalEntry() =
        runTerminalExecToolTest { tool ->
            val zipRel = "workspace/bad-" + UUID.randomUUID().toString().replace("-", "").take(8) + ".zip"
            val zipFile = File(tool.filesDir, ".agents/$zipRel")
            zipFile.parentFile?.mkdirs()
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                zos.putNextEntry(ZipEntry("../evil.txt"))
                zos.write("nope".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("ok.txt"))
                zos.write("ok".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            val destRel = "workspace/unpack-" + UUID.randomUUID().toString().replace("-", "").take(8)
            val out = tool.exec("zip extract --in $zipRel --dest $destRel --confirm")
            assertEquals(0, out.exitCode)

            val escaped = File(tool.filesDir, ".agents/workspace/evil.txt")
            assertTrue("zip slip must not create escaped file: $escaped", !escaped.exists())

            val skipped = out.result?.get("skipped")?.jsonObject
            val unsafe = skipped?.get("unsafe_path") as? JsonPrimitive
            assertTrue("expected skipped.unsafe_path >= 1", (unsafe?.content?.toLongOrNull() ?: 0L) >= 1L)
        }

    @Test
    fun zip_extract_supportsEncodingFlag_forCp932Names() =
        runTerminalExecToolTest { tool ->
            val zipRel = "workspace/cp932-" + UUID.randomUUID().toString().replace("-", "").take(8) + ".zip"
            val zipFile = File(tool.filesDir, ".agents/$zipRel")
            zipFile.parentFile?.mkdirs()

            val entryName = "残酷な天使のテーゼ.txt"
            ZipArchiveOutputStream(FileOutputStream(zipFile)).use { zos ->
                zos.setEncoding("windows-31j")
                zos.setUseLanguageEncodingFlag(false)
                val e = ZipArchiveEntry(entryName)
                zos.putArchiveEntry(e)
                zos.write("ok".toByteArray(Charsets.UTF_8))
                zos.closeArchiveEntry()
                zos.finish()
            }

            val destRel = "workspace/unpack-" + UUID.randomUUID().toString().replace("-", "").take(8)
            val out = tool.exec("zip extract --in $zipRel --dest $destRel --confirm --encoding cp932")
            assertEquals(0, out.exitCode)

            val extracted = File(tool.filesDir, ".agents/$destRel/$entryName")
            assertTrue("expected decoded filename to be preserved: $extracted", extracted.exists())
            assertEquals("ok", extracted.readText(Charsets.UTF_8))
        }

    @Test
    fun zip_extract_autoEncoding_canHandleGbkEncodedJapaneseNames() =
        runTerminalExecToolTest { tool ->
            val zipRel = "workspace/gbk-" + UUID.randomUUID().toString().replace("-", "").take(8) + ".zip"
            val zipFile = File(tool.filesDir, ".agents/$zipRel")
            zipFile.parentFile?.mkdirs()

            val entryName = "残酷な天使のテーゼ.txt"
            ZipArchiveOutputStream(FileOutputStream(zipFile)).use { zos ->
                zos.setEncoding("GBK")
                zos.setUseLanguageEncodingFlag(false)
                val e = ZipArchiveEntry(entryName)
                zos.putArchiveEntry(e)
                zos.write("ok".toByteArray(Charsets.UTF_8))
                zos.closeArchiveEntry()
                zos.finish()
            }

            val destRel = "workspace/unpack-" + UUID.randomUUID().toString().replace("-", "").take(8)
            val out = tool.exec("zip extract --in $zipRel --dest $destRel --confirm --encoding auto")
            assertEquals(0, out.exitCode)

            val extracted = File(tool.filesDir, ".agents/$destRel/$entryName")
            assertTrue("expected decoded filename to be preserved: $extracted", extracted.exists())
            assertEquals("ok", extracted.readText(Charsets.UTF_8))
        }

    @Test
    fun zip_list_supportsOutArtifact() =
        runTerminalExecToolTest { tool ->
            val zipRel = "workspace/many-" + UUID.randomUUID().toString().replace("-", "").take(8) + ".zip"
            val zipFile = File(tool.filesDir, ".agents/$zipRel")
            zipFile.parentFile?.mkdirs()
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                for (i in 0 until 250) {
                    zos.putNextEntry(ZipEntry("f/$i.txt"))
                    zos.write("x".toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }

            val outRel = "artifacts/archive/zip-list-" + UUID.randomUUID().toString().replace("-", "").take(8) + ".json"
            val out = tool.exec("zip list --in $zipRel --max 5 --out $outRel")
            assertEquals(0, out.exitCode)
            assertTrue(out.artifacts.any { it.endsWith("/$outRel") })

            val outFile = File(tool.filesDir, ".agents/$outRel")
            assertTrue("list --out file should exist: $outFile", outFile.exists())
            val text = outFile.readText(Charsets.UTF_8)
            assertTrue(text.contains("\"count_total\""))
            assertTrue(text.contains("\"entries\""))
        }

    @Test
    fun tar_create_requiresConfirm() =
        runTerminalExecToolTest { tool ->
            val srcRel = "workspace/tar-src-" + UUID.randomUUID().toString().replace("-", "").take(8)
            val srcDir = File(tool.filesDir, ".agents/$srcRel")
            srcDir.mkdirs()
            File(srcDir, "a.txt").writeText("hello", Charsets.UTF_8)

            val out = tool.exec("tar create --src $srcRel --out workspace/out.tar")
            assertTrue(out.exitCode != 0)
            assertEquals("ConfirmRequired", out.errorCode)
        }

    @Test
    fun tar_extract_blocksPathTraversalEntry() =
        runTerminalExecToolTest { tool ->
            val tarRel = "workspace/bad-" + UUID.randomUUID().toString().replace("-", "").take(8) + ".tar"
            val tarFile = File(tool.filesDir, ".agents/$tarRel")
            tarFile.parentFile?.mkdirs()

            TarArchiveOutputStream(FileOutputStream(tarFile)).use { tout ->
                tout.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tout.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)

                val evil = TarArchiveEntry("../evil.txt")
                val evilBytes = "nope".toByteArray(Charsets.UTF_8)
                evil.size = evilBytes.size.toLong()
                tout.putArchiveEntry(evil)
                tout.write(evilBytes)
                tout.closeArchiveEntry()

                val ok = TarArchiveEntry("ok.txt")
                val okBytes = "ok".toByteArray(Charsets.UTF_8)
                ok.size = okBytes.size.toLong()
                tout.putArchiveEntry(ok)
                tout.write(okBytes)
                tout.closeArchiveEntry()

                tout.finish()
            }

            val destRel = "workspace/unpack-" + UUID.randomUUID().toString().replace("-", "").take(8)
            val out = tool.exec("tar extract --in $tarRel --dest $destRel --confirm")
            assertEquals(0, out.exitCode)

            val escaped = File(tool.filesDir, ".agents/workspace/evil.txt")
            assertTrue("tar slip must not create escaped file: $escaped", !escaped.exists())

            val skipped = out.result?.get("skipped")?.jsonObject
            val unsafe = skipped?.get("unsafe_path") as? JsonPrimitive
            assertTrue("expected skipped.unsafe_path >= 1", (unsafe?.content?.toLongOrNull() ?: 0L) >= 1L)
        }

    @Test
    fun tar_create_extract_roundtrip() =
        runTerminalExecToolTest { tool ->
            val id = UUID.randomUUID().toString().replace("-", "").take(8)
            val srcRel = "workspace/tar-roundtrip-src-$id"
            val outRel = "workspace/tar-roundtrip-$id.tar"
            val destRel = "workspace/tar-roundtrip-dest-$id"

            val srcDir = File(tool.filesDir, ".agents/$srcRel")
            srcDir.mkdirs()
            File(srcDir, "a.txt").writeText("hello-tar", Charsets.UTF_8)

            val create = tool.exec("tar create --src $srcRel --out $outRel --confirm")
            assertEquals(0, create.exitCode)
            assertTrue(File(tool.filesDir, ".agents/$outRel").exists())

            val extract = tool.exec("tar extract --in $outRel --dest $destRel --confirm")
            assertEquals(0, extract.exitCode)
            val extracted = File(tool.filesDir, ".agents/$destRel/a.txt")
            assertTrue(extracted.exists())
            assertEquals("hello-tar", extracted.readText(Charsets.UTF_8))
        }
}

