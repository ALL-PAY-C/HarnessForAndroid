package dev.dsh.mobile.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T5：DNS 选取逻辑。
 * 设备实测：模拟器只放行 10.0.2.3，8.8.8.8 / 1.1.1.1 不应答；而 musl 只要
 * resolv.conf 里有不可达的 nameserver 排在可达的后面，getaddrinfo 就整体 EAI_AGAIN。
 * 所以这里钉的是「只保留实测应答的 server」。
 */
class LinuxDnsTest {

    @Test
    fun `deviceProps 去空白去空值`() {
        val props = mapOf("net.dns1" to "10.0.2.3\n", "net.dns2" to "   ", "net.dns3" to "192.168.1.1")
        assertEquals(listOf("10.0.2.3", "192.168.1.1"), LinuxDns.deviceProps { props[it] })
    }

    @Test
    fun `resolve 只保留应答的 server 且保持候选顺序`() {
        val probed = mutableListOf<String>()
        val got = LinuxDns.resolve(
            props = listOf("10.0.2.3", "8.8.8.8"),
            fallback = listOf("1.1.1.1", "10.0.2.3"),
            probe = { probed += it; it == "10.0.2.3" || it == "1.1.1.1" },
        )
        assertEquals(listOf("10.0.2.3", "1.1.1.1"), got)
        // 去重后每个候选只探测一次
        assertEquals(listOf("10.0.2.3", "8.8.8.8", "1.1.1.1"), probed)
    }

    @Test
    fun `resolve 最多写两个 nameserver`() {
        val got = LinuxDns.resolve(
            props = emptyList(),
            fallback = listOf("10.0.2.3", "8.8.8.8", "1.1.1.1"),
            probe = { true },
        )
        assertEquals(listOf("10.0.2.3", "8.8.8.8"), got)
    }

    @Test
    fun `一个都不应答时返回空表`() {
        assertEquals(
            emptyList<String>(),
            LinuxDns.resolve(props = emptyList(), fallback = LinuxDns.FALLBACK, probe = { false }),
        )
    }

    @Test
    fun `query 根区 NS 报文形状`() {
        val q = LinuxDns.query(".", 2)
        assertEquals(17, q.size)
        assertEquals(0x4d, q[0].toInt() and 0xFF)
        assertEquals(0x01, q[2].toInt() and 0xFF)   // RD
        assertEquals(0, q[4].toInt())              // qdcount 高字节
        assertEquals(1, q[5].toInt())              // qdcount = 1
        assertEquals(0, q[12].toInt())              // 根名 = 单个 0 字节
        assertEquals(0, q[13].toInt())
        assertEquals(2, q[14].toInt())              // qtype = NS
        assertEquals(1, q[16].toInt())              // qclass = IN
    }

    @Test
    fun `query 点分域名逐段编码`() {
        val q = LinuxDns.query("example.com", 1)
        assertEquals(29, q.size)
        assertEquals(7, q[12].toInt())
        assertEquals("example", String(q, 13, 7, Charsets.US_ASCII))
        assertEquals(3, q[20].toInt())
        assertEquals("com", String(q, 21, 3, Charsets.US_ASCII))
        assertEquals(0, q[24].toInt())
        assertEquals(1, q[26].toInt())              // qtype = A
    }

    @Test
    fun `answers 对不可达地址返回 false 而不是抛异常`() {
        // 192.0.2.0/24 是 TEST-NET-1，不会有人应答：要么路由失败要么超时，两条都必须是 false。
        assertFalse(LinuxDns.answers("192.0.2.1", timeoutMs = 150))
    }

    @Test
    fun `主线程不发 UDP 探测包，直接判不可用`() {
        // 回归：真机点 Agent 页时 AppContainer 在主线程构造 proot 通道，
        // 探测包一走 socket 就 StrictMode NetworkOnMainThreadException，进程 FATAL。
        val saved = LinuxDns.mainThreadProbe
        LinuxDns.mainThreadProbe = { true }
        try {
            val started = System.nanoTime()
            assertFalse(LinuxDns.answers("8.8.8.8", timeoutMs = 800))
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("主线程不该真等超时，用时 ${elapsedMs}ms", elapsedMs < 100)
        } finally {
            LinuxDns.mainThreadProbe = saved
        }
    }
}
