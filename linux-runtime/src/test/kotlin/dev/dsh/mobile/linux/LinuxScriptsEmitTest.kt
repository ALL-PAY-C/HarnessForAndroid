package dev.dsh.mobile.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T5 设备证据的源头：把生产代码生成的脚本与配置原样落到 build/t5-generated/。
 * 设备上跑的必须是这几份字节（报告里对 sha256），避免「手抄一份脚本去设备上跑」。
 */
class LinuxScriptsEmitTest {

    @Test
    fun `导出设备要执行的脚本与配置`() {
        val dir = File("build/t5-generated").apply { mkdirs() }
        File(dir, "provision.sh").writeText(LinuxScripts.provision())
        File(dir, "selfcheck.sh").writeText(LinuxScripts.selfCheck())
        val network = LinuxNetwork(listOf("10.0.2.3"))
        File(dir, "resolv.conf").writeText(network.resolvConf())
        File(dir, "hosts").writeText(network.hostsFile())
        File(dir, "pip.conf").writeText(LinuxPip.content())

        assertTrue(File(dir, "provision.sh").length() > 200)
        assertTrue(File(dir, "selfcheck.sh").length() > 400)
        assertEquals("nameserver 10.0.2.3\n", File(dir, "resolv.conf").readText())
        assertTrue(File(dir, "pip.conf").readText().contains("break-system-packages = true"))
        // 导出的 provision.sh 必须和 LinuxScripts 直接产出的一致
        assertEquals(LinuxScripts.provision(), File(dir, "provision.sh").readText())
    }
}
