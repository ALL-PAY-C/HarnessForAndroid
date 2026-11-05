package dev.dsh.mobile.pluginhost

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1 端口分配：占用顺延、释放回落、全占满如实失败、落盘复用。
 *
 * 开真 socket 而不是打桩：这里要证明的正是「另一个进程占着这个端口时能不能探出来」，
 * 打桩等于把被测行为自己实现一遍。
 */
class LoopbackPortsTest {

    private fun hold(port: Int): ServerSocket = ServerSocket().apply {
        reuseAddress = false
        bind(InetSocketAddress(InetAddress.getByName(LoopbackPorts.LOOPBACK), port), 1)
    }

    @Test
    fun occupiedPortShiftsUpAndComesBackAfterRelease() {
        val preferred = LoopbackPorts.allocate(49_100)
        assertTrue("至少要能分配到一个端口", preferred > 0)

        val held = hold(preferred)
        try {
            assertFalse("被占用的端口不该判为空闲", LoopbackPorts.isFree(preferred))
            val next = LoopbackPorts.allocate(preferred)
            assertTrue("首选端口被占用时必须顺延，实际=" + next, next > preferred)
            assertTrue("顺延后最多 MAX_PROBES 个之内", next <= preferred + LoopbackPorts.MAX_PROBES)
        } finally {
            held.close()
        }

        assertTrue("释放后端口应立刻可用", LoopbackPorts.isFree(preferred))
        assertEquals("释放后必须回到首选端口", preferred, LoopbackPorts.allocate(preferred))
    }

    @Test
    fun exhaustedWindowReturnsMinusOne() {
        val base = LoopbackPorts.allocate(49_300)
        assertTrue(base > 0)
        val held = mutableListOf<ServerSocket>()
        try {
            for (port in base until base + LoopbackPorts.MAX_PROBES) held += hold(port)
            assertEquals("连续 32 个都占满时必须返回 -1", -1, LoopbackPorts.allocate(base))
        } finally {
            held.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun outOfRangePortsAreRejected() {
        assertEquals(-1, LoopbackPorts.allocate(0))
        assertEquals(-1, LoopbackPorts.allocate(70_000))
        assertFalse(LoopbackPorts.isFree(0))
    }

    @Test
    fun allocatedPortIsPersistedAndReused() {
        val bridge = MapConfigBridge()
        val host = PluginHost(configBridge = bridge)

        val preferred = LoopbackPorts.allocate(49_500)
        val held = hold(preferred)
        val first = try {
            host.loopbackPort("pl.a", preferred)
        } finally {
            held.close()
        }
        assertTrue("首选被占时必须换一个", first > preferred)
        assertEquals(
            "分配结果必须写回插件 config 的 loopback.port",
            first.toString(),
            bridge.read("pl.a")[LoopbackPorts.CONFIG_KEY],
        )

        // 首选端口此刻已空，但上次分配的端口要优先复用：换端口等于让已经连上的客户端断线。
        assertEquals("重启后应优先复用上次分配的端口", first, host.loopbackPort("pl.a", preferred))
    }

    private class MapConfigBridge : PluginConfigBridge {
        private val values = mutableMapOf<String, Map<String, String>>()
        override fun read(pluginId: String): Map<String, String> = values[pluginId].orEmpty()
        override fun write(pluginId: String, key: String, value: String) {
            values[pluginId] = values[pluginId].orEmpty() + (key to value)
        }
        override fun reload() = Unit
    }
}
