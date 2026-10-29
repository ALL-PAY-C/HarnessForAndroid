package dev.dsh.mobile.linux.kernel

import dev.dsh.mobile.linux.LinuxAbis
import dev.dsh.mobile.linux.LinuxResult
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DshKernelAbiTest {
    @get:Rule val tmp = TemporaryFolder()
    private val legacy = """{"version":"0.2.1","archive":"x64.tgz","sha256":"old","bytes":1,"nodeVersion":"22.23.4","nodeArchive":"node/x64.tgz","nodeSha256":"old-node","nodeBytes":2,"nodeStrip":"x64"}"""
    private fun manifest(extra: String = ""): KernelManifest = checkNotNull(DshKernelArtifacts.parse(legacy.dropLast(1) + extra + "}"))

    @Test fun selectsArm64KernelAndNodeFromArrays() {
        val m = manifest(""", "archives":[{"abi":"arm64-v8a","archive":"arm.tgz","sha256":"arm-sha","bytes":3}], "nodeArchives":[{"abi":"arm64-v8a","archive":"node/arm.tgz","sha256":"node-sha","bytes":4,"strip":"arm"}]""")
        val kernel = checkNotNull(DshKernelArtifacts.kernelArchive(m, LinuxAbis.ARM64))
        val node = checkNotNull(DshKernelArtifacts.nodeArchive(m, LinuxAbis.ARM64))
        assertEquals("arm.tgz", kernel.archive)
        assertEquals("arm-sha", kernel.sha256)
        assertEquals(3L, kernel.bytes)
        assertEquals("node/arm.tgz", node.archive)
        assertEquals("node-sha", node.sha256)
        assertEquals(4L, node.bytes)
        assertEquals("arm", node.strip)
        assertEquals("x64.tgz", m.archive)
        assertEquals("old", m.sha256)
    }

    private fun archive(path: String, content: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { gzip ->
            val header = ByteArray(512)
            fun put(offset: Int, text: String) { text.toByteArray().copyInto(header, offset) }
            put(0, path); put(100, "0000755"); put(124, content.size.toString(8).padStart(11, '0'))
            header[156] = '0'.code.toByte()
            gzip.write(header); gzip.write(content)
            gzip.write(ByteArray((512 - content.size % 512) % 512)); gzip.write(ByteArray(1024))
        }
        return out.toByteArray()
    }

    private fun installArm64Fixture(machine: Int): Pair<LinuxResult<KernelInstallation>, java.io.File> {
        val root = tmp.newFolder()
        val kernel = archive("entry.js", "kernel".toByteArray())
        val elf = ByteArray(20).apply {
            this[0] = 0x7f; this[1] = 69; this[2] = 76; this[3] = 70; this[4] = 2; this[5] = 1
            this[18] = machine.toByte(); this[19] = (machine shr 8).toByte()
        }
        val node = archive("arm-node/bin/node", elf)
        fun digest(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val source = manifest().copy(entry = "entry.js",
            archives = listOf(KernelArchive(LinuxAbis.ARM64, "arm.tgz", digest(kernel), kernel.size.toLong())),
            nodeArchives = listOf(KernelArchive(LinuxAbis.ARM64, "node/arm.tgz", digest(node), node.size.toLong(), "arm-node", "22.99.1")),
        )
        val opened = mutableListOf<String>()
        val installer = DshKernelInstaller(root, { path ->
            opened += path
            when(path) { "dsh-kernel/arm.tgz" -> kernel.inputStream(); "dsh-kernel/node/arm.tgz" -> node.inputStream(); else -> error(path) }
        }, usableBytes = { Long.MAX_VALUE }, abi = { LinuxAbis.ARM64 })
        val result = installer.install(source)
        assertEquals(listOf("dsh-kernel/arm.tgz", "dsh-kernel/node/arm.tgz"), opened)
        return result to root
    }

    @Test fun installerUsesSelectedArm64BytesHashesStripAndVersion() {
        val (result, root) = installArm64Fixture(0xb7)
        assertTrue(result.toString(), result is LinuxResult.Ok)
        val installation = (result as LinuxResult.Ok).value
        assertEquals("22.99.1", installation.node?.version)
        assertTrue(java.io.File(root, "root/node22/bin/node").isFile)
        assertEquals("aarch64", dev.dsh.mobile.linux.ElfHeader.machineOf(checkNotNull(installation.node).bin))
    }

    @Test fun installerRejectsX64ElfOnArm64BeforeActivation() {
        val (result, root) = installArm64Fixture(0x3e)
        assertTrue(result is LinuxResult.Err)
        assertTrue((result as LinuxResult.Err).reason.contains("Node 归档 ABI 不匹配：设备 arm64-v8a，归档 x86_64"))
        assertFalse(java.io.File(root, "root/dsh").exists())
        assertFalse(java.io.File(root, "root/node22").exists())
        assertFalse(java.io.File(root, DshKernelInstaller.MARKER).exists())
        assertTrue(java.io.File(root, "root").list().orEmpty().isEmpty())
    }

    @Test fun elfMachineMustMatchDeviceAbi() {
        fun header(machine: Int) = ByteArray(20).apply {
            this[0] = 0x7f; this[1] = 69; this[2] = 76; this[3] = 70; this[4] = 2; this[5] = 1
            this[18] = machine.toByte(); this[19] = (machine shr 8).toByte()
        }
        val x64 = dev.dsh.mobile.linux.ElfHeader.machineOf(header(0x3e))
        val arm64 = dev.dsh.mobile.linux.ElfHeader.machineOf(header(0xb7))
        assertNull(DshKernelArtifacts.nodeAbiError(LinuxAbis.ARM64, arm64))
        assertNull(DshKernelArtifacts.nodeAbiError(LinuxAbis.X86_64, x64))
        assertTrue(checkNotNull(DshKernelArtifacts.nodeAbiError(LinuxAbis.ARM64, x64)).contains("设备 arm64-v8a，归档 x86_64"))
        assertNotNull(DshKernelArtifacts.nodeAbiError(LinuxAbis.ARM64, null))
    }

    @Test fun arraysBeforeLegacyFieldsDoNotShadowThem() {
        val prefix = """"archives":[{"abi":"arm64-v8a","archive":"arm.tgz","sha256":"arm-sha","bytes":3}],"""
        val m = checkNotNull(DshKernelArtifacts.parse("{" + prefix + legacy.drop(1)))
        assertEquals("x64.tgz", m.archive)
        assertEquals("old", m.sha256)
        assertEquals(1L, m.bytes)
        assertEquals("arm.tgz", DshKernelArtifacts.kernelArchive(m, LinuxAbis.ARM64)?.archive)
    }

    @Test fun declaredEmptyArraysDoNotFallbackToLegacyFields() {
        val m = manifest(""", "archives":[], "nodeArchives":[]""")
        assertNull(DshKernelArtifacts.kernelArchive(m, LinuxAbis.X86_64))
        assertNull(DshKernelArtifacts.nodeArchive(m, LinuxAbis.X86_64))
        assertNull(DshKernelArtifacts.kernelArchive(manifest(), "armeabi-v7a"))
    }

    @Test fun failedBootstrapStillAllowsOfflineArm64Installation() {
        val bootstrap = dev.dsh.mobile.linux.LinuxBootstrap.parse("#T5-STATUS ok=0 skipped=0 apk_rc=1\n#T5-ERROR missing= python3 git")
        assertFalse(bootstrap.ok)
        val (result, _) = installArm64Fixture(0xb7)
        assertTrue(result.toString(), result is LinuxResult.Ok)
        assertNotNull(bootstrap.error)
    }

    @Test fun legacyManifestOnlyServesX64() {
        val m = manifest()
        assertEquals("x64.tgz", DshKernelArtifacts.kernelArchive(m, LinuxAbis.X86_64)?.archive)
        assertEquals("node/x64.tgz", DshKernelArtifacts.nodeArchive(m, LinuxAbis.X86_64)?.archive)
        assertNull(DshKernelArtifacts.kernelArchive(m, LinuxAbis.ARM64))
        assertNull(DshKernelArtifacts.nodeArchive(m, LinuxAbis.ARM64))
    }

    @Test fun missingArm64NodeReturnsExplicitErrorBeforeOpeningAssets() {
        val m = manifest(""", "archives":[{"abi":"arm64-v8a","archive":"arm.tgz","sha256":"arm","bytes":3}], "nodeArchives":[]""")
        val installer = DshKernelInstaller(tmp.newFolder(), { error("must not open assets") }, abi = { LinuxAbis.ARM64 })
        val result = installer.install(m)
        assertTrue(result is LinuxResult.Err)
        assertTrue((result as LinuxResult.Err).reason.contains("当前 ABI(arm64-v8a) 缺少 Node 运行时归档"))
    }

    @Test fun missingArm64KernelReturnsExplicitErrorBeforeOpeningAssets() {
        val installer = DshKernelInstaller(tmp.newFolder(), { error("must not open assets") }, abi = { LinuxAbis.ARM64 })
        val result = installer.install(manifest())
        assertTrue(result is LinuxResult.Err)
        assertTrue((result as LinuxResult.Err).reason.contains("当前 ABI(arm64-v8a) 缺少内核归档"))
    }
}
