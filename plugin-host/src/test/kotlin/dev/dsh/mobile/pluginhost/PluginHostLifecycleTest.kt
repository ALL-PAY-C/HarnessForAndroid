package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginapi.*
import org.junit.Assert.*
import org.junit.Test

class PluginHostLifecycleTest {
    private fun plugin(fail: Boolean = false) = object : DshPlugin {
        override val manifest = PluginManifest(id = "test.plugin", name = "Test", version = "1")
        override fun register(registry: PluginRegistry) {
            registry.quickCommand(QuickCommand("test.command", "Test", "test", manifest.id))
            if (fail) error("broken registration")
        }
    }

    @Test fun repeatedEnableDoesNotDuplicateCommands() {
        val host = PluginHost()
        host.load(plugin())
        host.setEnabled("test.plugin", true)
        assertEquals(1, host.slots.quickCommands.size)
    }

    @Test fun failedRegistrationRollsBackContributions() {
        val host = PluginHost()
        host.load(plugin(fail = true))
        assertTrue(host.slots.quickCommands.isEmpty())
        assertFalse(host.plugins.value.single().enabled)
    }

    private fun pluginThrowing(message: String?) = object : DshPlugin {
        override val manifest = PluginManifest(id = "test.throw", name = "Throw", version = "1")
        override fun register(registry: PluginRegistry) {
            throw RuntimeException(message)
        }
    }

    @Test fun nullMessageFailureCountsAsFailure() {
        val host = PluginHost()
        host.load(pluginThrowing(null))
        assertFalse(host.plugins.value.single().enabled)
        assertNotNull(host.plugins.value.single().error)
    }

    @Test fun emptyMessageFailureCountsAsFailure() {
        val host = PluginHost()
        host.load(pluginThrowing(""))
        assertFalse(host.plugins.value.single().enabled)
        assertNotNull(host.plugins.value.single().error)
    }

    @Test fun forceReloadReRegisters() {
        val host = PluginHost()
        var registrations = 0
        val p = object : DshPlugin {
            override val manifest = PluginManifest(id = "test.reload", name = "Reload", version = "1")
            override fun register(registry: PluginRegistry) {
                registrations++
                registry.quickCommand(QuickCommand("c", "C", "c", manifest.id))
            }
        }
        host.load(p)
        assertEquals(1, registrations)
        host.load(p, force = true)
        assertEquals(2, registrations)
        assertEquals(1, host.slots.quickCommands.size)
        assertEquals(1, host.plugins.value.size)
    }

    @Test fun backgroundPluginGatedBySafeModeAndEnabled() {
        val byDefault: (String, Boolean) -> Boolean = { _, d -> d }
        assertFalse(shouldStartBackgroundPlugin("foo.x", true, safeMode = true, byDefault))
        assertTrue(shouldStartBackgroundPlugin("dsh.x", true, safeMode = true, byDefault))
        assertTrue(shouldStartBackgroundPlugin("foo.x", true, safeMode = false, byDefault))
        assertFalse(shouldStartBackgroundPlugin("foo.x", true, safeMode = false) { _, _ -> false })
    }

    @Test fun isEnabledWithoutStoreFallsBackToDefault() {
        val host = PluginHost()
        assertTrue(host.isEnabled("foo.x", enabledByDefault = true))
        assertFalse(host.isEnabled("foo.x", enabledByDefault = false))
        assertTrue(host.isEnabled("dsh.x", enabledByDefault = true))
    }

    @Test fun refreshDoesNotResurrectUninstalledPlugin() {
        val host = PluginHost()
        val p = plugin()
        host.load(p)
        assertEquals(1, host.plugins.value.size)
        host.uninstall("test.plugin")
        assertTrue(host.plugins.value.isEmpty())
        // 卸载后 refresh 旧实例必须 no-op（identity 守卫），不能把旧实例复活回来。
        host.refresh(p)
        assertTrue(host.plugins.value.isEmpty())
        assertTrue(host.slots.quickCommands.isEmpty())
    }

    @Test fun failedUninstallKeepsPluginAndRegistry() {
        var onUninstallCalls = 0
        val host = PluginHost(onUninstall = { onUninstallCalls++; error("uninstall failed") })
        host.load(plugin())
        assertEquals(1, host.plugins.value.size)
        assertEquals(1, host.slots.quickCommands.size)
        try {
            host.uninstall("test.plugin")
            fail("预期 uninstall 上抛异常")
        } catch (e: IllegalStateException) {
            assertEquals("uninstall failed", e.message)
        }
        assertEquals(1, onUninstallCalls)
        // onUninstall 先抛，后续 clearPlugin / instances.remove / plugins 过滤都不该发生。
        assertEquals(1, host.plugins.value.size)
        assertEquals(1, host.slots.quickCommands.size)
    }
}
