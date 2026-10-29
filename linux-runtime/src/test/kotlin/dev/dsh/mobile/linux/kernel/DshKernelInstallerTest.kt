package dev.dsh.mobile.linux.kernel

import dev.dsh.mobile.linux.LinuxResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** T6：真资产（assets/dsh-kernel）安装、校验、回滚、幂等。 */
class DshKernelInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun assetsDir(): File {
        val candidates = listOf(
            File("src/main/assets/dsh-kernel"),
            File("linux-runtime/src/main/assets/dsh-kernel"),
        )
        return candidates.firstOrNull { File(it, "kernel-manifest.json").isFile }
            ?: throw IllegalStateException("kernel assets not found: " + candidates)
    }

    private fun manifest(): KernelManifest =
        DshKernelArtifacts.parse(File(assetsDir(), "kernel-manifest.json").readText())?.copy(archives = null, nodeArchives = null)
            ?: throw IllegalStateException("kernel manifest unparsable")

    private fun opener(opens: MutableList<String>): (String) -> java.io.InputStream = { path ->
        opens += path
        // path 形如 dsh-kernel/dsh-kernel-x.tgz 或 dsh-kernel/node/node-x.tgz
        File(assetsDir(), path.removePrefix(DshKernelArtifacts.DIR + "/")).inputStream()
    }

    /** 默认不装 Node 运行时（49MB 归档，解一次 130MB）：只有 Node 专项测试打开。 */
    private fun installer(
        rootfs: File,
        opens: MutableList<String> = mutableListOf(),
        usable: Long = Long.MAX_VALUE,
        withNode: Boolean = false,
    ) = DshKernelInstaller(rootfs, opener(opens), usableBytes = { usable }, withNode = withNode, abi = { dev.dsh.mobile.linux.LinuxAbis.X86_64 })

    @Test
    fun installsRealBundleAndIsIdempotent() {
        val rootfs = tmp.newFolder("rootfs")
        val opens = mutableListOf<String>()
        val inst = installer(rootfs, opens)
        val m = manifest()

        val result = inst.install(m)
        assertTrue(result.toString(), result is LinuxResult.Ok)
        val installed = (result as LinuxResult.Ok).value
        assertEquals(m.version, installed.version)
        assertEquals(m.sha256, installed.sha256)
        assertEquals(m.entry, installed.entry)
        assertEquals(File(rootfs, "root/dsh"), installed.dir)
        assertTrue("entry missing: " + installed.hostEntry, installed.hostEntry.isFile)
        assertTrue("entry empty", installed.hostEntry.length() > 0)
        assertEquals(listOf(DshKernelArtifacts.archivePath(m)), opens)
        assertTrue("marker missing", inst.marker.isFile)
        assertEquals("/root/dsh", installed.guestDir)
        assertEquals("/root/dsh/" + m.entry, installed.guestEntry)

        // 幂等：同版本同 sha 直接复用，不再读 asset。
        val again = inst.install(m)
        assertTrue(again is LinuxResult.Ok)
        assertEquals(1, opens.size)
        assertEquals(m.version, inst.installed()?.version)
        // 临时 tar 不残留
        assertFalse(File(rootfs, "tmp/dsh-kernel-" + m.version + ".tgz").exists())
    }

    @Test
    fun shaMismatchKeepsPreviousInstall() {
        val rootfs = tmp.newFolder("rootfs")
        val good = installer(rootfs).install(manifest())
        assertTrue(good is LinuxResult.Ok)

        val opens = mutableListOf<String>()
        val bad = manifest().copy(version = "9.9.9", sha256 = "0".repeat(64))
        val r = installer(rootfs, opens).install(bad)
        assertTrue(r.toString(), r is LinuxResult.Err)
        assertTrue((r as LinuxResult.Err).reason.contains("sha256"))

        // 旧版本仍在，标记与入口都完好
        val installed = installer(rootfs).installed() ?: throw AssertionError("previous install lost")
        assertNotNull(installed)
        assertEquals(manifest().version, installed.version)
        assertTrue(installed.hostEntry.isFile)
        // staging / rollback 不残留，只剩正式目录
        val left = File(rootfs, "root").list()!!.filter { it.startsWith("dsh") }
        assertEquals(listOf("dsh"), left)
        assertFalse(File(rootfs, "tmp/dsh-kernel-9.9.9.tgz").exists())
    }

    @Test
    fun byteMismatchFailsBeforeExtract() {
        val rootfs = tmp.newFolder("rootfs")
        val m = manifest().copy(version = "9.9.9", bytes = manifest().bytes + 1)
        val r = installer(rootfs).install(m)
        assertTrue(r is LinuxResult.Err)
        assertTrue((r as LinuxResult.Err).reason.contains("bytes"))
        assertFalse(File(rootfs, "root/dsh").exists())
        assertFalse(File(rootfs, "root/.dsh-kernel.json").exists())
    }

    @Test
    fun brokenArchiveRollsBackAndCleansStaging() {
        val rootfs = tmp.newFolder("rootfs")
        assertTrue(installer(rootfs).install(manifest()) is LinuxResult.Ok)
        // 真归档字节数/哈希都对，但内容不是 gzip：解包必须抛错并回滚
        val m = manifest().copy(version = "9.9.9")
        val bogus = tmp.newFolder("bogus")
        File(bogus, m.archive).writeBytes(ByteArray(m.bytes.toInt()) { 0x41 })
        val inst = DshKernelInstaller(
            rootfs,
            { File(bogus, m.archive).inputStream() },
            sha256 = { m.sha256 },
            usableBytes = { Long.MAX_VALUE },
            withNode = false,
            abi = { dev.dsh.mobile.linux.LinuxAbis.X86_64 },
        )
        val r = inst.install(m)
        assertTrue(r.toString(), r is LinuxResult.Err)
        assertEquals(manifest().version, inst.installed()!!.version)
        assertTrue(File(rootfs, "root/dsh").isDirectory)
        assertFalse(File(rootfs, "root/dsh.staging-9.9.9").exists())
    }

    @Test
    fun newVersionReplacesOldDirectory() {
        val rootfs = tmp.newFolder("rootfs")
        assertTrue(installer(rootfs).install(manifest()) is LinuxResult.Ok)
        val m = manifest().copy(version = "9.9.9")
        val r = installer(rootfs).install(m)
        assertTrue(r.toString(), r is LinuxResult.Ok)
        assertEquals("9.9.9", (r as LinuxResult.Ok).value.version)
        assertEquals("9.9.9", installer(rootfs).installed()!!.version)
        val left = File(rootfs, "root").list()!!.filter { it.startsWith("dsh") }
        assertEquals(listOf("dsh"), left)
    }

    @Test
    fun incompleteManifestIsRejected() {
        val rootfs = tmp.newFolder("rootfs")
        val r = installer(rootfs).install(manifest().copy(sha256 = ""))
        assertTrue(r is LinuxResult.Err)
        assertTrue((r as LinuxResult.Err).reason.contains("incomplete"))
        assertFalse(File(rootfs, "root/dsh").exists())
    }

    @Test
    fun insufficientDiskWritesNothing() {
        val rootfs = tmp.newFolder("rootfs")
        val r = installer(rootfs, usable = 1024L).install(manifest())
        assertTrue(r is LinuxResult.Err)
        assertTrue((r as LinuxResult.Err).reason.contains("insufficient disk"))
        assertFalse(File(rootfs, "root/dsh").exists())
    }

    @Test
    fun keepsOtherHomeContentUntouched() {
        val rootfs = tmp.newFolder("rootfs")
        val home = File(rootfs, "root")
        home.mkdirs()
        File(home, ".bashrc").writeText("export PS1=keep\n")
        File(home, "notes").mkdirs()
        File(home, "notes/todo.txt").writeText("keep me\n")

        assertTrue(installer(rootfs).install(manifest()) is LinuxResult.Ok)

        assertEquals("export PS1=keep\n", File(home, ".bashrc").readText())
        assertEquals("keep me\n", File(home, "notes/todo.txt").readText())
        assertTrue(File(home, "dsh").isDirectory)

        // 卸载也只删自己的
        assertTrue(installer(rootfs).remove())
        assertFalse(File(home, "dsh").exists())
        assertFalse(File(home, ".dsh-kernel.json").exists())
        assertEquals("export PS1=keep\n", File(home, ".bashrc").readText())
        assertTrue(File(home, "notes/todo.txt").isFile)
    }

    @Test
    fun markerWriteFailureRollsBackActivatedDir() {
        val rootfs = tmp.newFolder("rootfs")
        val v1 = manifest()
        assertTrue(installer(rootfs).install(v1) is LinuxResult.Ok)
        val oldEntry = File(rootfs, "root/dsh/" + v1.entry)
        assertTrue(oldEntry.isFile)

        // 新目录已切换成功后 marker 写入失败：必须整体回滚到 v1，而不是留下新目录 + 旧 rollback。
        val inst = DshKernelInstaller(
            rootfs,
            opener(mutableListOf()),
            usableBytes = { Long.MAX_VALUE },
            markerWriter = { _, _ -> throw java.io.IOException("injected marker write failure") },
            withNode = false,
            abi = { dev.dsh.mobile.linux.LinuxAbis.X86_64 },
        )
        val r = inst.install(manifest().copy(version = "9.9.9"))
        assertTrue(r.toString(), r is LinuxResult.Err)
        assertTrue((r as LinuxResult.Err).reason.contains("injected marker write failure"))

        assertEquals(v1.version, inst.installed()?.version)
        assertTrue("旧入口必须还在: " + oldEntry, oldEntry.isFile)
        val root = File(rootfs, "root")
        val dshLike = root.list()!!.filter { it.startsWith("dsh") }
        assertEquals(listOf("dsh"), dshLike)
        assertFalse(File(root, ".dsh-kernel.json.tmp").exists())
        assertEquals(1, root.list()!!.count { it == ".dsh-kernel.json" })
        assertEquals(v1.sha256, inst.installed()!!.sha256)
    }

    /** Node 运行时：解到 /root/node22、bin/node 落地、marker 记版本、二次安装幂等。 */
    @Test
    fun installsStaticNodeRuntimeBesideKernel() {
        val rootfs = tmp.newFolder("rootfs")
        val opens = mutableListOf<String>()
        val inst = installer(rootfs, opens, withNode = true)
        val m = manifest()

        val r = inst.install(m)
        assertTrue(r.toString(), r is LinuxResult.Ok)
        val node = (r as LinuxResult.Ok).value.node ?: throw AssertionError("node runtime not installed")
        assertEquals(m.nodeVersion, node.version)
        assertEquals(m.nodeSha256, node.sha256)
        assertEquals(File(rootfs, "root/node22"), node.dir)
        assertEquals("/root/node22", node.guestDir)
        assertEquals("/root/node22/bin/node", node.guestBin)
        assertTrue("node binary missing", node.bin.isFile)
        assertTrue("node binary must be executable", node.bin.canExecute())
        for (name in listOf("npm", "npx", "corepack")) {
            assertTrue("$name must be executable", File(node.dir, "bin/$name").canExecute())
        }
        assertTrue("node binary too small: " + node.bin.length(), node.bin.length() > 1_000_000)
        // 顶层目录已剥掉，staging 里不能残留 node-vX-linux-x64-musl
        assertFalse(File(node.dir, m.nodeStrip).exists())
        assertTrue(File(node.dir, "bin/node").isFile)
        assertEquals(
            listOf(DshKernelArtifacts.archivePath(m), DshKernelArtifacts.nodeArchivePath(m)),
            opens,
        )
        // marker 记下 node 版本与校验和
        val marker = inst.marker.readText()
        assertTrue(marker, marker.contains("\"nodeVersion\":\"" + m.nodeVersion + "\""))
        assertTrue(marker, marker.contains("\"nodeSha256\":\"" + m.nodeSha256 + "\""))

        // 幂等：内核与 Node 都同版本同 sha → 不再读 asset
        val again = inst.install(m)
        assertTrue(again is LinuxResult.Ok)
        assertEquals(2, opens.size)
        assertFalse(File(rootfs, "root/node22.staging-" + m.nodeVersion).exists())
        assertFalse(File(rootfs, "tmp/dsh-node-" + m.nodeVersion + ".tgz").exists())
    }

    /** Node 归档校验和不匹配：一个字节都不提交，旧内核与旧 Node 都完好。 */
    @Test
    fun nodeShaMismatchKeepsPreviousInstall() {
        val rootfs = tmp.newFolder("rootfs")
        assertTrue(installer(rootfs, withNode = true).install(manifest()) is LinuxResult.Ok)
        val before = installer(rootfs).installed()!!.node!!

        val bad = manifest().copy(nodeVersion = "9.9.9", nodeSha256 = "0".repeat(64))
        val r = installer(rootfs, withNode = true).install(bad)
        assertTrue(r.toString(), r is LinuxResult.Err)
        assertTrue((r as LinuxResult.Err).reason, (r as LinuxResult.Err).reason.contains("node sha256 mismatch"))

        val after = installer(rootfs).installed()!!
        assertEquals(before.version, after.node!!.version)
        assertTrue(after.node!!.bin.isFile)
        assertFalse(File(rootfs, "root/node22.staging-9.9.9").exists())
        assertEquals(listOf("node22"), File(rootfs, "root").list()!!.filter { it.startsWith("node22") })
    }

    /** nodeStrip 写错（顶层目录对不上）：解出来没有 bin/node，必须报错且不提交任何目录。 */
    @Test
    fun nodeStripMismatchFailsBeforeCommit() {
        val rootfs = tmp.newFolder("rootfs")
        val r = installer(rootfs, withNode = true).install(manifest().copy(nodeStrip = "node-v0.0.0-nope"))
        assertTrue(r.toString(), r is LinuxResult.Err)
        assertTrue((r as LinuxResult.Err).reason, (r as LinuxResult.Err).reason.contains("node binary missing after extract"))
        assertFalse(File(rootfs, "root/node22").exists())
        assertFalse(File(rootfs, "root/dsh").exists())
        assertFalse(File(rootfs, "root/.dsh-kernel.json").exists())
    }
}
