package dev.dsh.mobile.core.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BackupManifestShaTest {

    private fun writePackage(tag: String): Pair<BackupRoots, File> {
        val source = FakeBackupDb()
        source.sessions["s1"] = FakeBackupDb.sessionRow("s1", "会话")
        source.messages["m1"] = FakeBackupDb.messageRow("m1", "s1", "正文")
        val roots = tempRoots(tag)
        val pkg = File(roots.filesDir, tag + ".hfabak")
        BackupWriter.Companion.newWriter(roots, source).write(pkg, FULL_SELECTION)
        return roots to pkg
    }

    /** 重打一个包：把 [entry] 的内容换成 [mutate] 的结果，其余原样。 */
    private fun rewrite(src: File, dest: File, entry: String, mutate: (ByteArray) -> ByteArray) {
        ZipFile(src).use { zip ->
            ZipOutputStream(dest.outputStream().buffered()).use { out ->
                for (e in zip.entries()) {
                    val bytes = zip.getInputStream(e).readBytes()
                    out.putNextEntry(ZipEntry(e.name))
                    out.write(if (e.name == entry) mutate(bytes) else bytes)
                    out.closeEntry()
                }
            }
        }
    }

    @Test
    fun `篡改数据库字节后拒绝恢复并报校验失败`() {
        val (roots, pkg) = writePackage("sha-db")
        val tampered = File(roots.filesDir, "tampered-db.hfabak")
        rewrite(pkg, tampered, "db/dsh-mobile.db") { it + byteArrayOf(0x20) }

        val target = FakeBackupDb()
        val result = RestoreEngine(tempRoots("sha-db-dst"), target, PrefsSource.Empty, PrefsSink.Noop)
            .restore(tampered, RestoreEngine.Mode.Merge)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("校验失败"))
        assertEquals(0, target.sessions.size)
        assertEquals(0, target.messages.size)
    }

    @Test
    fun `篡改 manifest 记录的校验和同样被拒`() {
        val (roots, pkg) = writePackage("sha-manifest")
        val tampered = File(roots.filesDir, "tampered-manifest.hfabak")
        rewrite(pkg, tampered, BackupWriter.MANIFEST_ENTRY) { bytes ->
            val text = String(bytes)
            val broken = text.replaceFirst("\"sha256\":\"", "\"sha256\":\"0")
            broken.toByteArray()
        }

        val result = RestoreEngine(tempRoots("sha-manifest-dst"), FakeBackupDb(), PrefsSource.Empty, PrefsSink.Noop)
            .restore(tampered, RestoreEngine.Mode.Merge)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("校验失败"))
    }

    @Test
    fun `原包不被误判为损坏`() {
        val (roots, pkg) = writePackage("sha-ok")
        val target = FakeBackupDb()
        val report = RestoreEngine(tempRoots("sha-ok-dst"), target, PrefsSource.Empty, PrefsSink.Noop)
            .restore(pkg, RestoreEngine.Mode.Merge)
            .getOrThrow()

        assertEquals(1, report.sessions)
        assertEquals(1, report.messages)
        assertEquals("正文", target.messages["m1"]?.str("text"))
        assertTrue(pkg.length() > 0)
    }
}
