package dev.dsh.mobile.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** T5：resolv.conf / hosts 的生成与幂等落盘。DNS 根因见 REPORT.md。 */
class LinuxNetworkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `resolv conf 每个 server 一行`() {
        val n = LinuxNetwork(listOf("10.0.2.3", "192.168.1.1"))
        assertEquals("nameserver 10.0.2.3\nnameserver 192.168.1.1\n", n.resolvConf())
    }

    @Test
    fun `没有实测应答的 DNS 时写空表而不是塞公网地址`() {
        assertEquals("", LinuxNetwork(emptyList()).resolvConf())
    }

    @Test
    fun `hosts 只有回环 不含写死的公网 IP`() {
        val hosts = LinuxNetwork(listOf("10.0.2.3")).hostsFile()
        assertTrue(hosts, hosts.startsWith("127.0.0.1 localhost\n"))
        assertTrue(hosts, hosts.contains("::1 localhost"))
        // 之前为了绕过 DNS 故障把 CDN 域名写死成公网 IP，那是掩盖问题。
        assertFalse(hosts, hosts.contains("dl-cdn.alpinelinux.org"))
        val foreign = Regex("\\d+\\.\\d+\\.\\d+\\.\\d+").findAll(hosts)
            .map { it.value }.filter { !it.startsWith("127.") }.toList()
        assertEquals(hosts, emptyList<String>(), foreign)
    }

    @Test
    fun `applyTo 写出两份文件 第二次不再改写`() {
        val rootfs = tmp.newFolder("rootfs")
        val n = LinuxNetwork(listOf("10.0.2.3"))
        assertTrue(n.applyTo(rootfs))
        assertEquals("nameserver 10.0.2.3\n", File(rootfs, LinuxNetwork.RESOLV_CONF).readText())
        assertTrue(File(rootfs, LinuxNetwork.HOSTS).readText().contains("127.0.0.1 localhost"))
        assertFalse("内容一致时不该再写盘", n.applyTo(rootfs))
    }

    @Test
    fun `内容被改坏后 applyTo 重写回正确内容`() {
        val rootfs = tmp.newFolder("rootfs2")
        File(rootfs, "etc").mkdirs()
        File(rootfs, LinuxNetwork.RESOLV_CONF).writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        val n = LinuxNetwork(listOf("10.0.2.3"))
        assertTrue(n.applyTo(rootfs))
        assertEquals("nameserver 10.0.2.3\n", File(rootfs, LinuxNetwork.RESOLV_CONF).readText())
    }

    @Test
    fun `rootfs 不存在时不凭空造目录`() {
        val rootfs = File(tmp.root, "no-such-rootfs")
        assertFalse(LinuxNetwork(listOf("10.0.2.3")).applyTo(rootfs))
        assertFalse(File(rootfs, "etc").exists())
    }
}
