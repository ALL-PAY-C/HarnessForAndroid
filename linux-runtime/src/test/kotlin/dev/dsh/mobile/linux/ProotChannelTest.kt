package dev.dsh.mobile.linux

import dev.dsh.mobile.toolbridge.ChannelKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T4 单元侧：ProotChannel 只做「把 ShellChannel.exec 翻成 proot 进程」这一件事。
 * 真实进程由 androidTest 证明；这里钉死 argv/env 形状与退出码透传，
 * 特别是 A5 要求的那条：返回码必须是真码，stderr 必须分开。
 */
class ProotChannelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test fun terminalSpecUsesInteractiveGuestShellAndExecPrefix() {
        val (ch, _) = channel()
        ch.workDir.deleteRecursively()
        ch.tmpDir.deleteRecursively()
        val spec = ch.terminalSpec()
        assertEquals(ch.prootBinary.absolutePath, spec.shell)
        assertEquals(ch.argv("ignored").dropLast(3) + listOf("/bin/sh", "-l"), spec.args)
        assertEquals(ch.prootBinary.absolutePath, spec.args.first())
        assertFalse(spec.args.contains("-c"))
        assertEquals(ch.workDir.absolutePath, spec.cwd)
        val env = spec.env.associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(ch.loaderFile.absolutePath, env["PROOT_LOADER"])
        assertEquals(ch.tmpDir.absolutePath, env["PROOT_TMP_DIR"])
        assertEquals("xterm-256color", env["TERM"])
        assertTrue(ch.workDir.isDirectory)
        assertTrue(ch.tmpDir.isDirectory)
        assertTrue(File(ch.installation.rootfs, "etc/resolv.conf").isFile)
    }

    private class Call(val argv: List<String>, val env: Map<String, String>, val cwd: File?, val timeoutMs: Long)

    private fun channel(
        result: ProotRun = ProotRun(0, "ok\n", ""),
        onCall: (Call) -> Unit = {},
    ): Pair<ProotChannel, () -> Call?> {
        val nativeDir = tmp.newFolder("native")
        File(nativeDir, "libproot.so").writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        File(nativeDir, "libproot-loader.so").writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/sh").writeText("")
        var last: Call? = null
        val ch = ProotChannel(
            installation = LinuxInstallation("3.20.3", "x86_64", rootfs, rootfs.parentFile!!),
            nativeDir = nativeDir,
            tmpDir = tmp.newFolder("linux-tmp"),
            workDir = tmp.newFolder("workspace"),
            runner = ProotRunner { argv, env, cwd, timeoutMs ->
                last = Call(argv, env, cwd, timeoutMs)
                onCall(last!!)
                result
            },
        )
        return ch to { last }
    }

    @Test
    fun `argv 用 proot -r rootfs 且命令作为单个参数交给 sh -c`() = runTest {
        val (ch, last) = channel()
        val command = "cd /tmp && echo \"a b\" | wc -l"
        ch.exec(command, 5_000)
        val argv = requireNotNull(last()).argv
        assertEquals(ChannelKind.Proot, ch.kind)
        assertTrue(argv.toString(), argv.contains("-r"))
        assertTrue(argv.toString(), argv.contains(ch.installation.rootfs.absolutePath))
        // -0 = fake root：设备实测不加时 guest 内写 /usr、/etc 会 EACCES，包管理装不上东西。
        assertEquals("-0", argv[1])
        val shIdx = argv.indexOf("/bin/sh")
        assertTrue(argv.toString(), shIdx >= 0)
        assertEquals("-c", argv[shIdx + 1])
        assertEquals(command, argv[shIdx + 2])
        assertEquals(argv.size, shIdx + 3)
    }

    @Test
    fun `每次 exec 都显式带 PROOT_LOADER 与 PROOT_TMP_DIR`() = runTest {
        val (ch, last) = channel()
        ch.exec("true", 5_000)
        val env = requireNotNull(last()).env
        assertEquals(File(ch.nativeDir, "libproot-loader.so").absolutePath, env["PROOT_LOADER"])
        assertEquals(ch.tmpDir.absolutePath, env["PROOT_TMP_DIR"])
        assertEquals("/root", env["HOME"])
        assertEquals("/tmp", env["TMPDIR"])
    }

    @Test
    fun `返回真实退出码且 stderr 与 stdout 分离`() = runTest {
        val (ch, _) = channel(ProotRun(7, "stdout-line\n", "stderr-line\n"))
        val r = ch.exec("exit 7", 5_000)
        assertEquals(7, r.code)
        assertEquals("stdout-line\n", r.stdout)
        assertEquals("stderr-line\n", r.stderr)
        assertFalse(r.ok)
    }

    @Test
    fun `超时返回 -1 并说明超时`() = runTest {
        val (ch, _) = channel(ProotRun(0, "partial\n", "", timedOut = true))
        val r = ch.exec("sleep 99", 1_000)
        assertEquals(-1, r.code)
        assertTrue(r.stderr, r.stderr.contains("timeout"))
        assertEquals("partial\n", r.stdout)
    }

    @Test
    fun `rootfs 缺失时通道不可用`() {
        val nativeDir = tmp.newFolder("native2")
        File(nativeDir, "libproot.so").writeBytes(byteArrayOf(0x7F))
        val missing = File(tmp.root, "no-such-rootfs")
        val ch = ProotChannel(
            installation = LinuxInstallation("3.20.3", "x86_64", missing, tmp.root),
            nativeDir = nativeDir,
            tmpDir = tmp.newFolder("t2"),
            workDir = tmp.newFolder("w2"),
            runner = ProotRunner { _, _, _, _ -> ProotRun(0, "", "") },
        )
        assertFalse(ch.available())
    }
}
