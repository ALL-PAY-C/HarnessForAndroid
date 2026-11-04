package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginapi.PluginPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * plugin.json → 权限声明：名字映射、未知名字忽略、不写 permissions 的老包一个字节都不变。
 */
class PluginManifestPermissionTest {

    private fun manifest(json: String) = PluginManifestFile.parse(json).getOrThrow()

    @Test fun parsesDeclaredPermissionsCaseInsensitively() {
        val m = manifest(
            """{"id":"acme.perm","name":"Perm","permissions":["shell","PhoneControl","phone_control"]}""",
        )
        assertEquals(setOf(PluginPermission.Shell, PluginPermission.PhoneControl), m.declaredPermissions())
    }

    /** 认不出的名字丢掉：新宿主遇到未来权限名不该让整包装不上。 */
    @Test fun unknownPermissionNamesAreIgnored() {
        val m = manifest("""{"id":"acme.perm","name":"Perm","permissions":["teleport","shell"]}""")
        assertEquals(setOf(PluginPermission.Shell), m.declaredPermissions())
        assertEquals(setOf("teleport", "shell"), m.permissions)
    }

    /** 老包：没有 permissions 字段 → 空集 → 宿主按「不需要授权」放行。 */
    @Test fun missingFieldMeansNoPermissions() {
        val m = manifest("""{"id":"acme.old","name":"Old"}""")
        assertTrue(m.permissions.isEmpty())
        assertTrue(m.declaredPermissions().isEmpty())
    }

    @Test fun emptyArrayMeansNoPermissions() {
        val m = manifest("""{"id":"acme.old","name":"Old","permissions":[]}""")
        assertTrue(m.declaredPermissions().isEmpty())
    }
}
