package dev.dsh.mobile.linux

import dev.dsh.mobile.toolbridge.ShellChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** T5：预置脚本的输出解析与「先写 DNS 再装包」的顺序。 */
class LinuxBootstrapTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val skippedOutput = """
        #T5-STATE installed= python3 py3-pip git curl wget ca-certificates
        #T5-STATE missing=
        #T5-STATUS ok=1 skipped=1 apk_rc=-
    """.trimIndent()

    /** 设备真实情况：apk 因为个别文件 rename 失败返回 3，但必需包全在。 */
    private val apkRc3Output = """
        #T5-STATE installed= python3
        #T5-STATE missing= py3-pip git curl wget ca-certificates
        #T5-APK-BEGIN
        (1/22) Installing curl (8.14.1-r0)
        ERROR: ncurses-terminfo-base-6.4_p20240420-r2: failed to rename etc/terminfo/v/.apk.fa3cd to etc/terminfo/v/vt220.
        3 errors; 88 MiB in 53 packages
        #T5-APK-END rc=3
        #T5-STATUS ok=1 skipped=0 apk_rc=3
    """.trimIndent()

    private val failedOutput = """
        #T5-STATE installed=
        #T5-STATE missing= py3-pip git
        #T5-APK-BEGIN
        ERROR: unable to select packages: py3-pip (no such package)
        #T5-APK-END rc=1
        #T5-ROLLBACK begin missing= py3-pip git
        #T5-ROLLBACK done
        #T5-STATUS ok=0 skipped=0 apk_rc=1
        #T5-ERROR missing= py3-pip git
    """.trimIndent()

    @Test
    fun `全部已装时 ok 且标记 skipped`() {
        val r = LinuxBootstrap.parse(skippedOutput)
        assertTrue(r.ok)
        assertTrue(r.skipped)
        assertNull(r.apkRc)
        assertEquals(6, r.installed.size)
        assertTrue(r.missing.isEmpty())
        assertNull(r.error)
        assertEquals("", r.apkOutput)
    }

    @Test
    fun `apk 返回 3 但包都在 仍然算成功`() {
        val r = LinuxBootstrap.parse(apkRc3Output)
        assertTrue(r.error ?: "", r.ok)
        assertFalse(r.skipped)
        assertEquals(3, r.apkRc)
        // 那条 rename 噪声在 parse 出口就被 LinuxApkLog 剔掉，正常进度行保留。
        assertFalse(r.apkOutput, r.apkOutput.contains("failed to rename"))
        assertTrue(r.apkOutput, r.apkOutput.contains("Installing curl"))
    }

    @Test
    fun `仍缺包时 ok 为 false 并给出缺的包与 apk 原始输出`() {
        val r = LinuxBootstrap.parse(failedOutput)
        assertFalse(r.ok)
        assertEquals(listOf("py3-pip", "git"), r.missing)
        assertTrue(r.error ?: "", requireNotNull(r.error).contains("py3-pip"))
        assertTrue(r.apkOutput, r.apkOutput.contains("no such package"))
        assertTrue(r.raw, r.raw.contains("#T5-ROLLBACK done"))
    }

    @Test
    fun `脚本没跑出 STATUS 时用 stderr 说明原因`() {
        val r = LinuxBootstrap.parse("", "proot: cannot exec /bin/sh")
        assertFalse(r.ok)
        assertEquals("proot: cannot exec /bin/sh", r.error)
        assertTrue(r.missing.isEmpty())
    }

    @Test
    fun `ensure 先写 resolv hosts pip conf 再跑预置脚本`() {
        val (channel, calls) = channel(ProotRun(0, skippedOutput, ""))
        runTest {
            val r = LinuxBootstrap(channel = channel, network = LinuxNetwork(listOf("10.0.2.3"))).ensure()
            assertTrue(r.ok)
            val rootfs = channel.installation.rootfs
            assertEquals("nameserver 10.0.2.3\n", File(rootfs, LinuxNetwork.RESOLV_CONF).readText())
            assertTrue(File(rootfs, LinuxNetwork.HOSTS).isFile)
            assertEquals(LinuxPip.content(), File(rootfs, LinuxPip.PATH).readText())
            assertTrue(File(rootfs, LinuxPip.PATH).readText().contains("break-system-packages = true"))
            val command = requireNotNull(calls()).last()
            assertTrue(command, command.contains("apk info -e"))
            assertTrue(command, command.contains("#T5-APK-BEGIN"))
        }
    }

    @Test
    fun `ensure 超时也把脚本原始输出留在结果里`() {
        val (channel, _) = channel(ProotRun(-1, "#T5-STATE installed=\n", "timeout after 300000ms", timedOut = true))
        runTest {
            val r = LinuxBootstrap(channel = channel, network = LinuxNetwork(emptyList())).ensure()
            assertFalse(r.ok)
            assertTrue(r.raw, r.raw.contains("#T5-STATE installed="))
        }
    }

    private class Call(val command: String)

    private fun channel(result: ProotRun): Pair<ProotChannel, () -> List<String>> {
        val nativeDir = tmp.newFolder("native")
        File(nativeDir, "libproot.so").writeBytes(byteArrayOf(0x7F, 'E'.code.toByte()))
        File(nativeDir, "libproot-loader.so").writeBytes(byteArrayOf(0x7F, 'E'.code.toByte()))
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/sh").writeText("")
        val commands = mutableListOf<String>()
        val ch = ProotChannel(
            installation = LinuxInstallation("3.20.3", "x86_64", rootfs, rootfs.parentFile!!),
            nativeDir = nativeDir,
            tmpDir = tmp.newFolder("linux-tmp"),
            workDir = tmp.newFolder("workspace"),
            runner = ProotRunner { argv, _, _, _ ->
                commands += argv.last()
                result
            },
            network = LinuxNetwork(listOf("10.0.2.3")),
        )
        return ch to { commands }
    }
}
