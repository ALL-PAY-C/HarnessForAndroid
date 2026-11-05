package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginapi.DshPlugin
import dev.dsh.mobile.pluginapi.PluginManifest
import dev.dsh.mobile.pluginapi.PluginPermission
import dev.dsh.mobile.pluginapi.PluginRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T9.27：「没跑」必须说清是谁决定的，且不能混成「加载失败」。
 *
 * 两条线互为反证：loadError 有值 = 引擎真跑过又炸了；notLoadedReason 有值 = 压根没跑。
 * 两者同时有值，界面就没法把「点开关能修」和「要看日志」分开。
 */
class PluginNotLoadedTest {

    private class MemoryGrants : PermissionGrants {
        private val map = LinkedHashMap<String, Set<String>>()
        override fun permissions(id: String): Set<String> = map[id] ?: emptySet()
        override fun grant(id: String, permissions: Set<String>) {
            map[id] = permissions.toSet()
        }
    }

    private fun plugin(
        id: String = "test.plain",
        permissions: Set<PluginPermission> = emptySet(),
    ) = object : DshPlugin {
        override val manifest = PluginManifest(id = id, name = "Plain", version = "1", permissions = permissions)

        override fun register(registry: PluginRegistry) = Unit
    }

    @Test fun disabledByUserIsNotLoadedNotFailed() {
        val host = PluginHost()
        host.load(plugin())
        host.setEnabled("test.plain", false)

        val row = host.plugins.value.single()
        assertFalse(row.enabled)
        assertEquals("关掉开关是用户的选择，不是加载失败", NotLoadedReason.Disabled, row.notLoadedReason)
        assertNull("禁用态下不许有失败原文", row.loadError)
    }

    @Test fun safeModeBlockReportsSafeModeReason() {
        val host = PluginHost(extraSafeMode = { true })
        host.load(plugin())

        val row = host.plugins.value.single()
        assertFalse(row.enabled)
        assertTrue(row.blockedBySafeMode)
        assertEquals(NotLoadedReason.SafeMode, row.notLoadedReason)
        assertNull("被策略挡住不是插件自己炸了", row.loadError)
    }

    @Test fun missingPermissionReportsPermissionReason() {
        val host = PluginHost(grantsStore = MemoryGrants())
        host.load(plugin(permissions = setOf(PluginPermission.Shell)))

        val row = host.plugins.value.single()
        assertFalse(row.enabled)
        assertEquals(NotLoadedReason.Permission, row.notLoadedReason)
        assertNull(row.loadError)
    }

    @Test fun engineDownReportsEngineUnavailableAndClearsOnRealLoad() {
        val host = PluginHost()
        host.load(plugin())
        assertTrue("前提：这一行本来是启用的", host.plugins.value.single().enabled)

        host.noteNotLoaded("test.plain", NotLoadedReason.EngineUnavailable)

        val blocked = host.plugins.value.single()
        assertFalse("引擎没起来就不能还挂着「已启用」", blocked.enabled)
        assertEquals(NotLoadedReason.EngineUnavailable, blocked.notLoadedReason)
        assertNull("引擎没起来不是插件的加载失败", blocked.loadError)

        // 引擎修好、真跑起来一次：宿主回执把「没跑」的原因清掉，行回到已启用。
        host.noteLoadResult("test.plain", null)
        val running = host.plugins.value.single()
        assertTrue(running.enabled)
        assertNull(running.notLoadedReason)
    }

    @Test fun realFailureKeepsFailureShape() {
        val host = PluginHost()
        host.load(plugin())
        host.noteNotLoaded("test.plain", NotLoadedReason.EngineUnavailable)
        host.noteLoadResult("test.plain", "[load] Cannot find package '@deepseek-ai/dsh-credentials'")

        val row = host.plugins.value.single()
        assertEquals("[load] Cannot find package '@deepseek-ai/dsh-credentials'", row.loadError)
        assertNull("真失败不占「没跑」字段，否则两种态会互相覆盖", row.notLoadedReason)
        assertFalse(row.enabled)
    }

    @Test fun uninstallDropsNotLoadedReceipt() {
        val host = PluginHost(onUninstall = {})
        host.load(plugin())
        host.noteNotLoaded("test.plain", NotLoadedReason.EngineUnavailable)
        host.uninstall("test.plain")
        assertTrue(host.plugins.value.isEmpty())

        // 同名重装：旧回执不能跟过来（否则「修好了还显示未加载」）。
        host.load(plugin())
        val row = host.plugins.value.single()
        assertNull(row.notLoadedReason)
        assertTrue(row.enabled)
    }
}
