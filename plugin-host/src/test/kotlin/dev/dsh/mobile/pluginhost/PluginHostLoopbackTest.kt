package dev.dsh.mobile.pluginhost

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端口登记器的三条分支（B1 修复）：稳定不漂移、占用时重分配、释放后回到首选。
 *
 * 用真 socket 模拟「插件自己的服务」：这条缺陷的本质就是宿主拿自己的服务当占用者，
 * 打桩等于绕开唯一值得验证的现场。
 */
class PluginHostLoopbackTest {

    private fun hold(port: Int): ServerSocket = ServerSocket().apply {
        reuseAddress = false
        bind(InetSocketAddress(InetAddress.getByName(LoopbackPorts.LOOPBACK), port), 1)
    }

    private class MapBridge {
        val values = mutableMapOf<String, String>()
        var writes = 0
        fun registry() = LoopbackPortRegistry(
            read = { id -> values[id] },
            write = { id, value -> writes++; values[id] = value },
        )
    }

    @Test
    fun stablePortSurvivesWhileServiceHoldsIt() {
        val port = LoopbackPorts.allocate(49_700)
        assertTrue(port > 0)
        val held = hold(port)                       // 模拟插件自己的服务已经绑在这个端口
        val bridge = MapBridge()
        bridge.values["pl.a"] = port.toString()     // 上次已分配并落盘
        val registry = bridge.registry()
        try {
            assertEquals("第一次读必须是已落盘的那个端口", port, registry.port("pl.a", port))
            assertEquals("服务自己占着它时，第二次读必须原样返回、不得顺延", port, registry.port("pl.a", port))
            assertEquals("稳定路径不许改写 config", 0, bridge.writes)
            assertEquals(port.toString(), bridge.values["pl.a"])
        } finally {
            held.close()
        }
    }

    @Test
    fun reallocateMovesWhenStoredPortHeldByOther() {
        val port = LoopbackPorts.allocate(49_720)
        assertTrue(port > 0)
        val bridge = MapBridge()
        bridge.values["pl.a"] = port.toString()
        val registry = bridge.registry()

        val held = hold(port)                       // 这次占着它的是别人（插件绑不上）
        try {
            val moved = registry.reallocate("pl.a", port)
            assertTrue("被外部占用时必须顺延，实际=" + moved, moved > port)
            assertEquals("新端口必须写盘", moved.toString(), bridge.values["pl.a"])
            assertEquals("重分配后缓存也必须是新端口", moved, registry.port("pl.a", port))
        } finally {
            held.close()
        }
    }

    @Test
    fun reallocateResetsToPreferredAfterRelease() {
        val port = LoopbackPorts.allocate(49_740)
        assertTrue(port > 0)
        val bridge = MapBridge()
        bridge.values["pl.a"] = port.toString()
        val registry = bridge.registry()

        val held = hold(port)
        val moved = try {
            registry.reallocate("pl.a", port)
        } finally {
            held.close()
        }
        assertTrue(moved > port)

        val back = registry.reallocate("pl.a", port)
        assertEquals("旧占用释放后必须回到首选端口", port, back)
        assertEquals(port.toString(), bridge.values["pl.a"])
    }

    @Test
    fun forgetDropsCacheButKeepsStoredValue() {
        val port = LoopbackPorts.allocate(49_760)
        assertTrue(port > 0)
        val bridge = MapBridge()
        bridge.values["pl.a"] = port.toString()
        val registry = bridge.registry()

        assertEquals(port, registry.port("pl.a", port))
        registry.forget("pl.a")                     // 禁用 / 卸载
        assertEquals("缓存清掉后按存储重新判定，端口不变", port, registry.port("pl.a", port))
        assertEquals("仍然没有改写 config", 0, bridge.writes)
    }
    @Test
    fun twoPluginsNeverSharePortWhenNeitherBinds() {
        val base = LoopbackPorts.allocate(49_780)
        assertTrue(base > 0)
        val bridge = MapBridge()
        val registry = bridge.registry()
        // 谁都没真绑端口：isFree 对两者都为真，能把它们分开的只有「已发放」集合。
        val a = registry.port("pl.a", base)
        val b = registry.port("pl.b", base)
        assertTrue("两个插件不许拿到同一个端口，实际=" + a + "/" + b, a != b)
        assertEquals("先来的拿首选端口", base, a)
        assertTrue("后来的必须顺延", b > a)
    }

    @Test
    fun forgetReleasesIssuedPort() {
        val base = LoopbackPorts.allocate(49_800)
        assertTrue(base > 0)
        val bridge = MapBridge()
        val registry = bridge.registry()
        assertEquals(base, registry.port("pl.a", base))
        registry.forget("pl.a")                     // 禁用 / 卸载
        assertEquals(
            "forget 之后旧端口必须回到可发放池",
            base,
            registry.port("pl.b", base),
        )
    }

    @Test
    fun reallocateReleasesOldAndReservesNew() {
        val base = LoopbackPorts.allocate(49_820)
        assertTrue(base > 0)
        val bridge = MapBridge()
        val registry = bridge.registry()
        assertEquals(base, registry.port("pl.a", base))

        val held = hold(base)                       // 别的进程占住旧端口 → 必须换
        val moved = try {
            registry.reallocate("pl.a", base)
        } finally {
            held.close()
        }
        assertTrue("旧端口被外部占用时必须顺延，实际=" + moved, moved > base)
        assertEquals("重分配后 A 仍持有新端口", moved, registry.port("pl.a", base))
        assertEquals("旧端口已释放，可以发给别的插件", base, registry.port("pl.b", base))
    }
}
