package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginapi.Compat
import dev.dsh.mobile.pluginapi.DshPlugin
import dev.dsh.mobile.pluginapi.PluginManifest
import dev.dsh.mobile.pluginapi.PluginQuota
import dev.dsh.mobile.pluginapi.PluginRegistry
import dev.dsh.mobile.pluginapi.QuickCommand
import dev.dsh.mobile.pluginapi.SlotId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T6.2：宿主侧版本协商（拒载不进注册表）+ 声明式插件的降级与配额。 */
class PluginCompatHostTest {

    private fun spec(
        id: String = "t.plugin",
        contributions: List<ContributionSpec> = emptyList(),
        apiVersion: Int = 1,
        minHostApi: Int = 0,
    ) = PluginManifestFile(
        id = id,
        name = id,
        version = "1",
        contributions = contributions,
        apiVersion = apiVersion,
        minHostApi = minHostApi,
    )

    private fun button(slot: String, id: String) =
        ContributionSpec(slot = slot, id = id, label = id, action = ActionSpec(kind = "prompt", text = id))

    // ------------------------------------------------------------------ 拒载

    /** apiVersion 比宿主新：完全不注册，register() 一次都不跑，行上给出可读原因。 */
    @Test
    fun `host rejects newer apiVersion without registering`() {
        var registrations = 0
        val plugin = object : DshPlugin {
            override val manifest = PluginManifest(id = "t.future", name = "Future", version = "1", apiVersion = 2)
            override fun register(registry: PluginRegistry) {
                registrations++
                registry.quickCommand(QuickCommand(id = "c", label = "C", payload = "/c", pluginId = manifest.id))
            }
        }
        val host = PluginHost()
        host.load(plugin)

        assertEquals("拒载的插件不许进 register()", 0, registrations)
        assertTrue(host.slots.quickCommands.isEmpty())
        val row = host.plugins.value.single()
        assertFalse(row.enabled)
        assertTrue(row.compat is Compat.Rejected)
        assertTrue(row.compat.summary().contains("apiVersion=2"))
        assertTrue(row.error!!.contains("apiVersion=2"))
    }

    /** 开关拨回来也拒载：版本门槛不由用户决定。 */
    @Test
    fun `enabling a rejected plugin still registers nothing`() {
        val host = PluginHost()
        host.load(DeclarativePlugin(spec(id = "t.future", apiVersion = 3)))
        host.setEnabled("t.future", true)
        assertTrue(host.slots.contributions.isEmpty())
        assertFalse(host.plugins.value.single().enabled)
        assertTrue(host.compatOf("t.future") is Compat.Rejected)
    }

    /** 老包（不写 apiVersion）：照旧加载，清单缺省即宿主当前版本。 */
    @Test
    fun `manifest without apiVersion loads as compatible`() {
        val host = PluginHost()
        host.load(DeclarativePlugin(spec(contributions = listOf(button(SlotId.Settings.wire, "s1")))))
        assertEquals(1, host.slots.inSlot(SlotId.Settings).size)
        assertEquals(Compat.Compatible, host.plugins.value.single().compat)
    }

    // ------------------------------------------------------------------ 降级

    /** 未知槽位：忽略该项 + 原因带槽位名，宿主照常起来。 */
    @Test
    fun `unknown slot degrades declarative plugin`() {
        val host = PluginHost()
        host.load(
            DeclarativePlugin(
                spec(contributions = listOf(button("chat.future.slot", "x"), button(SlotId.Settings.wire, "s1"))),
            ),
        )
        assertEquals("只注册认得的那一条", listOf("s1"), host.slots.inSlot(SlotId.Settings).map { it.id })
        val compat = host.plugins.value.single().compat
        assertTrue(compat is Compat.Degraded)
        assertTrue((compat as Compat.Degraded).reasons.single().contains("chat.future.slot"))
        assertTrue(host.plugins.value.single().enabled)
    }

    /** 未知动作：不降级成 prompt（否则替插件乱发消息），贡献保留、动作置空并记原因。 */
    @Test
    fun `unknown action does not fall back to prompt`() {
        val host = PluginHost()
        host.load(
            DeclarativePlugin(
                spec(
                    contributions = listOf(
                        ContributionSpec(slot = SlotId.Settings.wire, id = "a", label = "A", action = ActionSpec(kind = "rce")),
                    ),
                ),
            ),
        )
        val c = host.slots.inSlot(SlotId.Settings).single()
        assertNull("认不出的动作不许变成 prompt", c.action)
        assertTrue(host.plugins.value.single().compat.summary().contains("rce"))
    }

    // ------------------------------------------------------------------ 配额

    /** 声明式插件塞爆输入框「+」面板：前 2 条生效，第 3 条被拒且有原因。 */
    @Test
    fun `declarative over quota is rejected with reason`() {
        val slot = SlotId.ComposerPlus.wire
        val max = PluginQuota.ComposerActions.max
        val host = PluginHost()
        host.load(
            DeclarativePlugin(
                spec(contributions = (1..(max + 1)).map { button(slot, "b" + it) }),
            ),
        )
        assertEquals((1..max).map { "b" + it }, host.slots.inSlot(SlotId.ComposerPlus).map { it.id })
        val rejection = host.slots.rejections.single()
        assertTrue(rejection.reason.contains(PluginQuota.ComposerActions.key))
        assertTrue(host.plugins.value.single().compat.summary().contains(PluginQuota.ComposerActions.key))
    }
}
