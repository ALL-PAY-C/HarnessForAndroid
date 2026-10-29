package dev.dsh.mobile.linux

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * T3 单元侧：版本化 rootfs 安装的判定逻辑。
 * 重点是「宁可失败也不留半个可用环境」：校验不过、路径越界、磁盘不够，都必须不激活。
 */
class LinuxEnvironmentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- 测试用最小 tar.gz 构造 ----
    private data class Entry(val name: String, val body: String = "", val type: Char = '0', val link: String = "", val prefix: String = "", val mode: String = "0000644")

    private fun header(e: Entry): ByteArray {
        val h = ByteArray(512)
        fun put(off: Int, s: String) { s.toByteArray().copyInto(h, off) }
        put(0, e.name)
        put(100, e.mode + "\u0000")
        put(108, "0000000\u0000")
        put(116, "0000000\u0000")
        put(124, e.body.toByteArray().size.toString(8).padStart(11, '0') + "\u0000")
        put(136, "00000000000\u0000")
        put(148, "        ")
        h[156] = e.type.code.toByte()
        if (e.link.isNotEmpty()) put(157, e.link)
        put(257, "ustar\u0000")
        put(263, "00")
        put(345, e.prefix)
        var sum = 0
        for (b in h) sum += b.toInt() and 0xFF
        put(148, sum.toString(8).padStart(6, '0') + "\u0000 ")
        return h
    }

    private fun tarGz(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz ->
            for (e in entries) {
                gz.write(header(e))
                if (e.body.isNotEmpty()) {
                    val b = e.body.toByteArray()
                    gz.write(b)
                    val pad = (512 - b.size % 512) % 512
                    gz.write(ByteArray(pad))
                }
            }
            gz.write(ByteArray(1024))
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun writeRootfs(entries: List<Entry> = listOf(Entry("bin/", type = '5'), Entry("bin/sh", "#!/bin/sh\n"))): File {
        val f = File(tmp.newFolder(), "rootfs.tar.gz")
        f.writeBytes(tarGz(entries))
        return f
    }

    private fun manifestFor(f: File, version: String = "3.20.3", abi: String = "x86_64") = LinuxManifest(
        version = version,
        abi = abi,
        url = "https://example.invalid/alpine-minirootfs-$version-$abi.tar.gz",
        sha256 = sha256(f.readBytes()),
        bytes = f.length(),
    )

    private fun env(
        abi: String = "x86_64",
        usable: Long = 1L shl 30,
        downloader: (LinuxManifest, File) -> Unit,
    ): LinuxEnvironment = LinuxEnvironment(
        baseDir = tmp.newFolder("linux"),
        abi = abi,
        downloader = downloader,
        usableBytes = { usable },
    )

    private fun envCopying(src: File, usable: Long = 1L shl 30) =
        env(usable = usable) { _, dest -> src.copyTo(dest, overwrite = true) }

    @Test
    fun `ustar prefix preserves long dependency paths`() {
        val prefix = "node_modules/" + "p".repeat(90)
        val archive = writeRootfs(listOf(Entry("package.json", "fixture", prefix = prefix)))
        val dest = tmp.newFolder()
        LinuxTar.extract(archive, dest)
        assertEquals("fixture", File(dest, "$prefix/package.json").readText())
    }

    @Test(expected = LinuxTarException::class)
    fun `ustar prefix cannot escape extraction root`() {
        LinuxTar.extract(writeRootfs(listOf(Entry("escape", "bad", prefix = "../.."))), tmp.newFolder())
    }

    @Test(expected = LinuxTarException::class)
    fun `prefix does not hide absolute entry name`() {
        LinuxTar.extract(writeRootfs(listOf(Entry("/escape", "bad", prefix = "safe"))), tmp.newFolder())
    }

    @Test(expected = LinuxTarException::class)
    fun `prefix symlink target stays within extraction root`() {
        LinuxTar.extract(writeRootfs(listOf(Entry("link", type = '2', link = "../../escape", prefix = "safe"))), tmp.newFolder())
    }

    @Test fun `tar executable bit is preserved`() {
        val dest = tmp.newFolder()
        LinuxTar.extract(writeRootfs(listOf(Entry("bin/", type = '5'), Entry("bin/tool", "#!/bin/sh\n", mode = "0000755"))), dest)
        assertTrue(File(dest, "bin/tool").canExecute())
    }

    @Test fun `GNU and PAX extension records fail explicitly`() {
        for (type in listOf('L', 'K', 'x', 'g')) {
            try {
                LinuxTar.extract(writeRootfs(listOf(Entry("extension", "long-name", type))), tmp.newFolder())
                org.junit.Assert.fail("accepted unsupported extension $type")
            } catch (e: LinuxTarException) {
                assertTrue(e.message.orEmpty().contains("unsupported tar long-name extension"))
            }
        }
    }

    @Test
    fun repairExecutablesRestoresNestedFilesDespiteExecutableShell() {
        val src = writeRootfs(listOf(
            Entry("bin/sh", "#!/bin/sh\n", mode = "0000755"),
            Entry("usr/lib/nested/loader", "\u007fELFpayload"),
            Entry("opt/scripts/start", "#!/bin/sh\n"),
            Entry("usr/libexec/nested/helper", "helper"),
            Entry("etc/plain", "configuration"),
        ))
        val e = envCopying(src)
        val rootfs = requireNotNull(e.prepare(manifestFor(src)).getOrNull()).rootfs
        val files = listOf("usr/lib/nested/loader", "opt/scripts/start", "usr/libexec/nested/helper")
            .map { File(rootfs, it) }
        (files + File(rootfs, "etc/plain")).forEach { it.setExecutable(false, false) }
        org.junit.Assume.assumeTrue("host must support removing execute bits", files.none { it.canExecute() })
        assertTrue(File(rootfs, "bin/sh").canExecute())
        assertEquals(3, e.repairExecutables())
        files.forEach { assertTrue(it.path, it.canExecute()) }
        assertEquals(false, File(rootfs, "etc/plain").canExecute())
    }

    @Test
    fun repairScanFindsNestedExecutablesAndCountsOnlySuccessfulChanges() {
        val rootfs = tmp.newFolder()
        LinuxTar.extract(writeRootfs(listOf(
            Entry("bin/sh", "shell"),
            Entry("usr/lib/nested/loader", "\u007fELFpayload"),
            Entry("opt/scripts/start", "#!/bin/sh\n"),
            Entry("usr/libexec/nested/helper", "helper"),
            Entry("opt/broken", "\u007fELFpayload"),
            Entry("etc/plain", "configuration"),
        )), rootfs)
        val executable = mutableSetOf(File(rootfs, "bin/sh").path)
        fun tracked(file: File): File = object : File(file.path) {
            override fun canExecute(): Boolean = path in executable
            override fun listFiles(): Array<File>? = super.listFiles()?.map { tracked(it) }?.toTypedArray()
            override fun setExecutable(value: Boolean, ownerOnly: Boolean): Boolean {
                if (name == "broken") return false
                if (value) executable.add(path) else executable.remove(path)
                return true
            }
        }
        val trackedRoot = tracked(rootfs)
        assertEquals(3, repairGuestExecutables(trackedRoot))
        listOf("usr/lib/nested/loader", "opt/scripts/start", "usr/libexec/nested/helper")
            .forEach { assertTrue(it, tracked(File(rootfs, it)).canExecute()) }
        assertEquals(false, tracked(File(rootfs, "etc/plain")).canExecute())
        assertEquals(false, tracked(File(rootfs, "opt/broken")).canExecute())
        assertEquals(0, repairGuestExecutables(trackedRoot))
    }

    @Test
    fun repairExecutablesIsIdempotent() {
        val src = writeRootfs()
        val e = envCopying(src)
        val rootfs = requireNotNull(e.prepare(manifestFor(src)).getOrNull()).rootfs
        val shell = File(rootfs, "bin/sh")
        shell.setExecutable(false, false)
        org.junit.Assume.assumeTrue("host must support removing execute bits", !shell.canExecute())
        assertEquals(1, e.repairExecutables())
        assertTrue(shell.canExecute())
        assertEquals(0, e.repairExecutables())
    }

    @Test
    fun copiedLinkPreservesExecutableMode() {
        val src = writeRootfs(listOf(
            Entry("bin/busybox", "executable", mode = "0000755"),
            Entry("bin/sh", type = '1', link = "busybox"),
        ))
        val rootfs = tmp.newFolder()
        LinuxTar.extract(src, rootfs)
        val shell = File(rootfs, "bin/sh")
        assertTrue(shell.isFile)
        assertEquals("executable", shell.readText())
        assertTrue(shell.canExecute())
    }

    @Test
    fun invalidateActiveRemovesPointerAndVersionDirectory() {
        val src = writeRootfs()
        val e = envCopying(src)
        val installation = requireNotNull(e.prepare(manifestFor(src)).getOrNull())
        assertTrue(e.invalidateActive())
        assertEquals(false, installation.dir.exists())
        assertEquals(false, File(e.baseDir, "current").exists())
        assertNull(e.active())
        assertTrue(e.invalidateActive())
        assertEquals(0, e.repairExecutables())
    }

    @Test
    fun `合法 rootfs 被安装并原子激活`() = runTest {
        val src = writeRootfs()
        val e = envCopying(src)
        val result = e.prepare(manifestFor(src))
        assertTrue(result.toString(), result is LinuxResult.Ok)
        val inst = requireNotNull(result.getOrNull())
        assertTrue(File(inst.rootfs, "bin/sh").isFile)
        assertEquals("3.20.3", e.active()?.version)
        assertEquals("x86_64", e.active()?.abi)
        assertTrue(File(e.baseDir, "current").isFile)
    }

    @Test
    fun `SHA-256 不匹配时不激活且不留残料`() = runTest {
        val src = writeRootfs()
        val e = envCopying(src)
        val bad = manifestFor(src).copy(sha256 = "00".repeat(32))
        val result = e.prepare(bad)
        assertTrue(result.toString(), result is LinuxResult.Err)
        assertTrue((result as LinuxResult.Err).reason, result.reason.contains("sha256"))
        assertNull(e.active())
        assertTrue(e.usage().files == 0)
    }

    @Test
    fun `字节数不符时判失败`() = runTest {
        val src = writeRootfs()
        val e = envCopying(src)
        val result = e.prepare(manifestFor(src).copy(bytes = src.length() + 1))
        assertTrue(result.toString(), result is LinuxResult.Err)
        assertTrue((result as LinuxResult.Err).reason, result.reason.contains("bytes"))
    }

    @Test
    fun `路径越界的条目拒绝安装`() = runTest {
        val src = writeRootfs(listOf(Entry("bin/", type = '5'), Entry("../../evil.txt", "x")))
        val e = envCopying(src)
        val result = e.prepare(manifestFor(src))
        assertTrue(result.toString(), result is LinuxResult.Err)
        assertTrue((result as LinuxResult.Err).reason, result.reason.contains("unsafe"))
        assertNull(e.active())
    }

    @Test
    fun `绝对路径条目拒绝安装`() = runTest {
        val src = writeRootfs(listOf(Entry("/etc/passwd", "root:x:0:0")))
        val e = envCopying(src)
        val result = e.prepare(manifestFor(src))
        assertTrue(result.toString(), result is LinuxResult.Err)
        assertNull(e.active())
    }

    @Test
    fun `指向外部的符号链接拒绝安装`() = runTest {
        val src = writeRootfs(listOf(Entry("bin/", type = '5'), Entry("bin/sh", type = '2', link = "../../../data/data")))
        val e = envCopying(src)
        val result = e.prepare(manifestFor(src))
        assertTrue(result.toString(), result is LinuxResult.Err)
        assertNull(e.active())
    }

    @Test
    fun `磁盘空间不足时在下载前就失败`() = runTest {
        val src = writeRootfs()
        var downloaded = false
        val e = env(usable = 1024L) { _, _ -> downloaded = true }
        val result = e.prepare(manifestFor(src))
        assertTrue(result.toString(), result is LinuxResult.Err)
        assertTrue((result as LinuxResult.Err).reason, result.reason.contains("disk"))
        assertEquals(false, downloaded)
    }

    @Test
    fun `已激活同一版本时不重复下载`() = runTest {
        val src = writeRootfs()
        var calls = 0
        val e = env { _, dest -> calls++; src.copyTo(dest, overwrite = true) }
        val m = manifestFor(src)
        assertTrue(e.prepare(m) is LinuxResult.Ok)
        assertTrue(e.prepare(m) is LinuxResult.Ok)
        assertEquals(1, calls)
    }

    @Test
    fun `ABI 不符时拒绝`() = runTest {
        val src = writeRootfs()
        val e = envCopying(src)
        val result = e.prepare(manifestFor(src, abi = "arm64-v8a"))
        assertTrue(result.toString(), result is LinuxResult.Err)
        assertTrue((result as LinuxResult.Err).reason, result.reason.contains("abi"))
    }

    @Test
    fun `removeInactive 只删旧版本并保留当前`() = runTest {
        val srcOld = writeRootfs()
        val srcNew = writeRootfs(listOf(Entry("bin/", type = '5'), Entry("bin/sh", "new")))
        // 下载器按版本给出对应的制品，避免两个版本共用同一份字节。
        val e = env { m, dest ->
            (if (m.version == "3.19.1") srcOld else srcNew).copyTo(dest, overwrite = true)
        }
        assertTrue(e.prepare(manifestFor(srcOld, version = "3.19.1")) is LinuxResult.Ok)
        assertTrue(e.prepare(manifestFor(srcNew, version = "3.20.3")) is LinuxResult.Ok)
        assertEquals(1, e.removeInactive())
        assertEquals("3.20.3", e.active()?.version)
    }

    @Test
    fun `usage 统计当前目录字节与文件数`() = runTest {
        val src = writeRootfs()
        val e = envCopying(src)
        assertTrue(e.prepare(manifestFor(src)) is LinuxResult.Ok)
        val u = e.usage()
        assertTrue(u.files > 0)
        assertTrue(u.bytes > 0)
        assertNotNull(e.active())
    }

    @Test
    fun `manifest 解析与按 ABI 选取`() {
        val json = """
        {
          "version": "3.20.3",
          "artifacts": [
            {"abi":"x86_64","url":"u1","sha256":"a1","bytes":11},
            {"abi":"arm64-v8a","url":"u2","sha256":"a2","bytes":22}
          ]
        }
        """.trimIndent()
        val list = LinuxArtifacts.parse(json)
        assertEquals(2, list.size)
        assertEquals("u2", LinuxArtifacts.forAbi(list, "arm64-v8a")?.url)
        assertNull(LinuxArtifacts.forAbi(list, "armeabi-v7a"))
    }
}
