package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginapi.DshPlugin
import dev.dsh.mobile.pluginapi.PluginManifest
import dev.dsh.mobile.pluginapi.PluginPermission
import dev.dsh.mobile.pluginapi.PluginRegistry
import dev.dsh.mobile.pluginapi.QuickCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T9.25：清单声明权限 → 未授予不 register、行显示「需授权」；授予即生效；撤销即回退。
 *
 * 用内存实现 [PermissionGrants]，不拖 Context/Robolectric 进来：授权判定是纯逻辑，
 * 判定本身被存储细节绑住就测不到「声明了但没授予」这个关键分支。
 */
class PluginPermissionsTest {

    private class MemoryGrants : PermissionGrants {
        private val map = LinkedHashMap<String, Set<String>>()
        override fun permissions(id: String): Set<String> = map[id] ?: emptySet()
        override fun grant(id: String, permissions: Set<String>) {
            map[id] = permissions.toSet()
        }
    }

    private fun plugin(
        id: String = "test.perm",
        permissions: Set<PluginPermission> = setOf(PluginPermission.Shell),
        onRegister: () -> Unit = {},
    ) = object : DshPlugin {
        override val manifest = PluginManifest(
            id = id,
            name = "Perm",
            version = "1",
            permissions = permissions,
        )

        override fun register(registry: PluginRegistry) {
            onRegister()
            registry.quickCommand(QuickCommand("perm.command", "Perm", "p", manifest.id))
        }
    }

    @Test fun declaredButNotGrantedDoesNotRegister() {
        var registrations = 0
        val host = PluginHost(grantsStore = MemoryGrants())
        host.load(plugin { registrations++ })

        val row = host.plugins.value.single()
        assertEquals("未授予时 register() 一次都不该跑", 0, registrations)
        assertTrue(host.slots.quickCommands.isEmpty())
        assertFalse("没授权的行不许显示已启用", row.enabled)
        assertTrue("行内必须能说出「需授权」", row.blockedByPermission)
        assertEquals(setOf(PluginPermission.Shell), host.missingPermissions("test.perm"))
        assertTrue(host.permissionGrants("test.perm").isEmpty())
        assertFalse("未授权的插件不许被 Node 编排当成可加载", host.isEnabled("test.perm", true))
    }

    @Test fun grantingRegistersAndEnablesTheRow() {
        var registrations = 0
        val host = PluginHost(grantsStore = MemoryGrants())
        host.load(plugin { registrations++ })

        host.grantPermissions("test.perm", setOf(PluginPermission.Shell))

        assertEquals("授予后必须立刻注册", 1, registrations)
        assertEquals(1, host.slots.quickCommands.size)
        val row = host.plugins.value.single()
        assertTrue(row.enabled)
        assertFalse(row.blockedByPermission)
        assertTrue(host.missingPermissions("test.perm").isEmpty())
        assertEquals(setOf(PluginPermission.Shell), host.permissionGrants("test.perm"))
        assertTrue(host.isEnabled("test.perm", true))
    }

    @Test fun revokingReturnsToNeedsPermission() {
        val host = PluginHost(grantsStore = MemoryGrants())
        host.load(plugin())
        host.grantPermissions("test.perm", setOf(PluginPermission.Shell))
        assertEquals(1, host.slots.quickCommands.size)

        host.revokePermissions("test.perm", setOf(PluginPermission.Shell))

        assertEquals("撤销后贡献必须立刻下架", 0, host.slots.quickCommands.size)
        val row = host.plugins.value.single()
        assertFalse(row.enabled)
        assertTrue(row.blockedByPermission)
        assertEquals(setOf(PluginPermission.Shell), host.missingPermissions("test.perm"))
    }

    /** 老插件（不写 permissions）：空集走同一条路径，不因这次改动多一步判定。 */
    @Test fun pluginWithoutPermissionsIsUnaffected() {
        var registrations = 0
        val grants = MemoryGrants()
        val host = PluginHost(grantsStore = grants)
        host.load(plugin(permissions = emptySet()) { registrations++ })

        val row = host.plugins.value.single()
        assertEquals(1, registrations)
        assertTrue(row.enabled)
        assertFalse(row.blockedByPermission)
        assertTrue(host.missingPermissions("test.perm").isEmpty())
        assertTrue(host.permissionGrants("test.perm").isEmpty())
    }

    /** 没有授予存储（老调用方 / 单测里的裸 PluginHost）时，声明了权限就是没授权，不能静默放行。 */
    @Test fun declaredPermissionsWithoutGrantsStoreStayBlocked() {
        val host = PluginHost()
        host.load(plugin())

        val row = host.plugins.value.single()
        assertTrue(host.slots.quickCommands.isEmpty())
        assertTrue(row.blockedByPermission)
        assertFalse(row.enabled)
        assertEquals(setOf(PluginPermission.Shell), host.missingPermissions("test.perm"))
    }

    /** 授一项与声明无关的权限不能解锁：判定按清单声明逐项比，不是「授过就算」。 */
    @Test fun grantingUndeclaredPermissionDoesNotUnlock() {
        val host = PluginHost(grantsStore = MemoryGrants())
        host.load(plugin(permissions = setOf(PluginPermission.Shell, PluginPermission.Workspace)))

        host.grantPermissions("test.perm", setOf(PluginPermission.Workspace))

        assertTrue("还有 Shell 没给", host.slots.quickCommands.isEmpty())
        assertEquals(setOf(PluginPermission.Shell), host.missingPermissions("test.perm"))

        host.grantPermissions("test.perm", setOf(PluginPermission.Shell))
        assertEquals(1, host.slots.quickCommands.size)
        assertTrue(host.plugins.value.single().enabled)
    }

    /** 给不存在的插件授权：不抛、不造行、不写存储。 */
    @Test fun grantingForUnknownPluginIsNoOp() {
        val grants = MemoryGrants()
        val host = PluginHost(grantsStore = grants)

        host.grantPermissions("ghost", setOf(PluginPermission.Shell))

        assertTrue(host.plugins.value.isEmpty())
        assertTrue(grants.permissions("ghost").isEmpty())
    }
}
