package dev.dsh.mobile.toolbridge.privilege

import dev.dsh.mobile.toolbridge.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯逻辑：形态选择与 argv 拼装不碰进程，直接喂假的 run。 */
class PrivilegedExecutorTest {

    private fun ok(out: String) = ShellResult(0, out, "")

    @Test
    fun wrapDashCUsesDashC() {
        assertEquals(listOf("su", "-c", "id"), RootForm.DashC.wrap("id"))
    }

    @Test
    fun wrapWhoArgPutsUidBeforeShell() {
        // AOSP 的 su 把第一个参数当 uid：必须 su 0 sh -c，写成 su -c 会报 invalid uid/gid '-c'。
        assertEquals(listOf("su", "0", "sh", "-c", "id"), RootForm.WhoArg.wrap("id"))
    }

    @Test
    fun wrapNoneHasNoArgv() {
        assertNull(RootForm.None.wrap("id"))
        assertTrue(!RootForm.None.available)
    }

    @Test
    fun probeCodeDistinguishesNoSuFromDeniedSu() {
        assertEquals(0, RootProbe(RootForm.WhoArg, "/system/xbin/su", "").code)
        assertEquals(RootProbe.CODE_NO_SU, RootProbe(RootForm.None, null, "").code)
        assertEquals(RootProbe.CODE_SU_DENIED, RootProbe(RootForm.None, "/system/xbin/su", "").code)
    }

    @Test
    fun explicitCodeOverridesNullPathInference() {
        // su 在对本进程不可见时 suPath 仍为 null，但原因码必须是「拒绝」而不是「没有 su」。
        val probe = RootProbe(RootForm.None, null, "su 存在，但 app 无权执行", RootProbe.CODE_SU_DENIED)
        assertEquals(RootProbe.CODE_SU_DENIED, probe.code)
        assertEquals(RootProbe.CODE_NO_SU, RootProbe(RootForm.None, null, "未检测到 su").code)
    }

    @Test
    fun pickPrefersDashCWhenBothWork() {
        val seen = mutableListOf<List<String>>()
        val form = PrivilegedExecutor.pick { argv ->
            seen += argv
            ok("uid=0(root) gid=0(root)")
        }
        assertEquals(RootForm.DashC, form)
        assertEquals(listOf(listOf("su", "-c", "id")), seen)
    }

    @Test
    fun pickFallsBackToWhoArgWhenDashCFails() {
        // 本机 AOSP 实况：su -c 返回 su: invalid uid/gid '-c'，su 0 sh -c 才返回 uid=0。
        val seen = mutableListOf<List<String>>()
        val form = PrivilegedExecutor.pick { argv ->
            seen += argv
            if (argv.contains("-c") && argv.size == 3) {
                ShellResult(1, "su: invalid uid/gid '-c'", "")
            } else {
                ok("uid=0(root) gid=0(root) groups=0(root)")
            }
        }
        assertEquals(RootForm.WhoArg, form)
        assertEquals(listOf(listOf("su", "-c", "id"), listOf("su", "0", "sh", "-c", "id")), seen)
    }

    @Test
    fun pickReturnsNoneWhenSuExistsButDenies() {
        val form = PrivilegedExecutor.pick { ShellResult(1, "su: permission denied", "") }
        assertEquals(RootForm.None, form)
    }

    @Test
    fun pickIgnoresZeroExitWithoutRootUid() {
        // 退出码 0 但输出不是 root（例如 su 把命令空跑）不算可用，否则 exec 会静默以普通用户跑。
        val form = PrivilegedExecutor.pick { ok("uid=2000(shell) gid=2000(shell)") }
        assertEquals(RootForm.None, form)
    }

    @Test
    fun pickKeepsTryingWhenProbeThrows() {
        var calls = 0
        val form = PrivilegedExecutor.pick { argv ->
            calls += 1
            if (argv.size == 3) throw java.io.IOException("su missing") else ok("uid=0(root)")
        }
        assertEquals(RootForm.WhoArg, form)
        assertEquals(2, calls)
    }
}
