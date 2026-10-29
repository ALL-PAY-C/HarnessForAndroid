package dev.dsh.mobile.linux.kernel

import dev.dsh.mobile.linux.LinuxInstallation
import dev.dsh.mobile.linux.ProotChannel
import dev.dsh.mobile.linux.ProotRun
import dev.dsh.mobile.linux.ProotRunner
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** T6：命令构造（HOME/cwd/入口）与握手判定（退出码 + 版本号双证据）。 */
class DshKernelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val entry = "node_modules/@deepseek-ai/dsh/lib/bin.js"

    /** 复用 ProotChannelTest 的造法：nativeDir 里要有两个 lib*.so，rootfs 里要有 /bin/sh。 */
    private fun channel(commands: MutableList<String>, reply: (String) -> ProotRun): ProotChannel {
        val native = tmp.newFolder()
        val elf = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
        File(native, "libproot.so").writeBytes(elf)
        File(native, "libproot-loader.so").writeBytes(elf)
        val rootfs = tmp.newFolder()
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/sh").writeText("")
        return ProotChannel(
            installation = LinuxInstallation("3.20.3", "x86_64", rootfs, rootfs.parentFile!!),
            nativeDir = native,
            tmpDir = tmp.newFolder(),
            workDir = tmp.newFolder(),
            runner = ProotRunner { argv, _, _, _ ->
                val command = argv.last()
                commands += command
                reply(command)
            },
        )
    }

    private fun installation() = KernelInstallation("0.2.1-rc.2", "sha", tmp.newFolder(), entry)

    private fun kernel(commands: MutableList<String> = mutableListOf(), reply: (String) -> ProotRun) =
        DshKernel(channel(commands, reply), installation())

    @Test
    fun healthRepairsPermissionDeniedAndRetriesOnce() = runTest {
        val commands = mutableListOf<String>()
        var repairs = 0
        val k = DshKernel(channel(commands) { cmd ->
            when {
                repairs == 0 -> ProotRun(1, "", "proot error: Permission denied")
                cmd.contains("test -x") -> ProotRun(0, "v22.23.4", "")
                else -> ProotRun(0, "0.2.1-rc.2", "")
            }
        }, installation(), repairExecutables = { repairs++; 1 })
        assertTrue(k.health().ok)
        assertEquals(1, repairs)
        assertEquals(3, commands.size)
    }

    @Test
    fun healthAppendsGuidanceAfterEaccesRetryFails() = runTest {
        val commands = mutableListOf<String>()
        var repairs = 0
        val k = DshKernel(channel(commands) { ProotRun(1, "", "execve: EACCES") },
            installation(), repairExecutables = { repairs++; 0 })
        val health = k.health()
        assertFalse(health.ok)
        assertEquals(1, repairs)
        assertEquals(2, commands.size)
        assertTrue(health.error.orEmpty().endsWith(";修复后仍不可执行，运行时目录可能落在不支持 exec 的位置（外部存储）或需要重装运行时"))
    }

    @Test
    fun healthRetriesKernelPermissionDeniedCaseInsensitively() = runTest {
        val commands = mutableListOf<String>()
        var repairs = 0
        val k = DshKernel(channel(commands) { cmd ->
            when {
                cmd.contains("test -x") -> ProotRun(0, "v22.23.4", "")
                repairs == 0 -> ProotRun(1, "", "pErMiSsIoN dEnIeD")
                else -> ProotRun(0, "0.2.1-rc.2", "")
            }
        }, installation(), repairExecutables = { repairs++; 1 })
        assertTrue(k.health().ok)
        assertEquals(1, repairs)
        assertEquals(4, commands.size)
    }

    @Test
    fun healthDoesNotRepairUnrelatedFailure() = runTest {
        val commands = mutableListOf<String>()
        var repairs = 0
        val k = DshKernel(channel(commands) { ProotRun(1, "", "not found") },
            installation(), repairExecutables = { repairs++; 0 })
        assertFalse(k.health().ok)
        assertEquals(0, repairs)
        assertEquals(1, commands.size)
    }

    @Test
    fun commandExportsHomePwdPathAndExecsOfficialEntry() {
        val k = kernel { ProotRun(0, "", "") }
        val cmd = k.command("--version")
        assertTrue(cmd, cmd.contains("cd /root/dsh"))
        assertTrue(cmd, cmd.contains("export HOME=/root PWD=/root/dsh"))
        assertTrue(cmd, cmd.contains("PATH=/root/node22/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"))
        assertTrue(cmd, cmd.contains("LANG=C.UTF-8"))
        assertTrue("kernel profiles and state must stay in guest", cmd.contains("DSH_HOME=/root/dsh"))
        assertTrue(cmd, cmd.contains("exec /root/node22/bin/node /root/dsh/" + entry + " --version"))
        assertFalse("不许 exec libnode.so", cmd.contains("libnode"))
        assertFalse("不许 apk 装 nodejs", cmd.contains("apk add"))
        assertEquals("/root/dsh/" + entry, k.guestEntry)
        assertEquals("test -x /root/node22/bin/node && /root/node22/bin/node -v", k.nodeVersionCommand())
        // 无参数时不带多余空格
        assertTrue(k.command().endsWith("exec /root/node22/bin/node /root/dsh/" + entry))
    }

    @Test
    fun healthProbesStaticNodeThenReportsVersion() = runTest {
        val commands = mutableListOf<String>()
        val k = kernel(commands) { cmd ->
            when {
                cmd.contains("test -x /root/node22/bin/node") -> ProotRun(0, "v22.23.4\n", "")
                else -> ProotRun(0, "0.2.1-rc.2\n", "")
            }
        }
        val health = k.health()
        assertTrue(health.error, health.ok)
        assertEquals("0.2.1-rc.2", health.version)
        assertEquals("v22.23.4", health.nodeVersion)
        assertEquals(entry, health.entry)
        assertEquals(0, health.exitCode)
        assertNull(health.error)
        assertEquals(2, commands.size)
        assertTrue("先探 node，再跑内核", commands[0].contains("/root/node22/bin/node -v"))
        assertTrue(commands[1].contains("exec /root/node22/bin/node"))
        assertFalse("不再 apk 装 nodejs", commands.any { it.contains("apk add") })
    }

    @Test
    fun kernelFailureIsReportedWithExitCode() = runTest {
        val k = kernel { cmd ->
            if (cmd.contains("test -x /root/node22/bin/node")) ProotRun(0, "v22.23.4", "")
            else ProotRun(1, "", "Error [ERR_MODULE_NOT_FOUND]: Cannot find package '@deepseek-ai/dsh-http-proxy'")
        }
        val health = k.health()
        assertFalse(health.ok)
        assertEquals(1, health.exitCode)
        assertTrue(health.error!!, health.error!!.contains("ERR_MODULE_NOT_FOUND"))
        assertEquals("", health.version)
    }

    @Test
    fun oldNodeIsRejected() = runTest {
        val k = kernel { cmd ->
            if (cmd.contains("test -x /root/node22/bin/node")) ProotRun(0, "v20.15.1", "")
            else ProotRun(0, "0.2.1-rc.2", "")
        }
        val health = k.health()
        assertFalse(health.ok)
        assertEquals("v20.15.1", health.nodeVersion)
        assertTrue(health.error!!, health.error!!.contains("22+"))
        assertEquals("", health.version)
    }

    @Test
    fun startupLogIsNotSuccess() = runTest {
        val k = kernel { cmd ->
            if (cmd.contains("test -x /root/node22/bin/node")) ProotRun(0, "v22.23.4", "")
            else ProotRun(0, "booting profile headless\nready", "")
        }
        val health = k.health()
        assertFalse("退出码 0 但没版本号不能算成功", health.ok)
        assertEquals("", health.version)
        assertTrue(health.error!!, health.error!!.contains("no version"))
    }

    /** 静态运行时缺失/被打断（例如被 3.22 源升级 icu 搞坏）时，第一条命令就短路。 */
    @Test
    fun missingNodeRuntimeShortCircuits() = runTest {
        val commands = mutableListOf<String>()
        val k = kernel(commands) {
            ProotRun(127, "", "sh: /root/node22/bin/node: not found")
        }
        val health = k.health()
        assertFalse(health.ok)
        assertEquals(1, commands.size)
        assertTrue(health.error!!, health.error!!.contains("node runtime missing or broken"))
        assertTrue(health.error!!, health.error!!.contains("not found"))
        assertEquals(127, health.exitCode)
        assertEquals("", health.version)
    }

    @Test
    fun parseHealthAcceptsVersionOnLastLine() {
        val health = DshKernel.parseHealth(
            node = RunResult(0, "v22.19.1\n", ""),
            kernel = RunResult(0, "warning: something\n0.2.1-rc.2\n", ""),
            entry = entry,
        )
        assertTrue(health.error, health.ok)
        assertEquals("0.2.1-rc.2", health.version)
    }

    @Test
    fun parseHealthRejectsNonVersionOutput() {
        val health = DshKernel.parseHealth(
            node = RunResult(0, "v22.19.1", ""),
            kernel = RunResult(0, "Usage: dsh [options]", ""),
            entry = entry,
        )
        assertFalse(health.ok)
        assertTrue(health.error!!, health.error!!.contains("no version"))
    }
}
