package dev.dsh.mobile.pluginapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T6.2：版本契约 + 三态协商 + 硬配额 + 撤销清单。 */
class PluginCompatTest {

    private fun quick(id: String, plugin: String) =
        QuickCommand(id = id, label = id, payload = "/" + id, pluginId = plugin)

    private fun button(id: String, plugin: String) =
        ComposerButton(id = id, label = id, pluginId = plugin, transform = { it })

    private fun contribution(slot: SlotId, id: String, plugin: String) =
        SlotContribution(slot = slot, id = id, label = id, pluginId = plugin)

    // ---------------------------------------------------------------- ① 老插件

    /** 老清单不写 apiVersion：默认就是宿主当前版本，协商通过、照旧加载。 */
    @Test
    fun `manifest without apiVersion is compatible`() {
        val manifest = PluginManifest(id = "old.plugin", name = "Old", version = "1")
        assertEquals(PLUGIN_API_VERSION, manifest.apiVersion)

        val compat = PluginCompat.negotiate(
            PluginDeclaration(apiVersion = manifest.apiVersion, minHostApi = manifest.minHostApi),
        )
        assertEquals(Compat.Compatible, compat)
        assertTrue(compat.accepted)

        // 老插件注册照旧生效，且没有留下任何降级原因。
        val r = PluginRegistry()
        r.quickCommand(quick("old", manifest.id))
        assertEquals(1, r.quickCommands.size)
        assertEquals(Compat.Compatible, r.compat(manifest.id))
    }

    /** 显式写 1 与不写等价。 */
    @Test
    fun `explicit apiVersion 1 is compatible`() {
        assertEquals(
            Compat.Compatible,
            PluginCompat.negotiate(PluginDeclaration(apiVersion = 1, minHostApi = 0)),
        )
    }

    // ---------------------------------------------------------------- ② 拒载

    /** apiVersion 比宿主新：拒载，且不抛异常。 */
    @Test
    fun `apiVersion 2 is rejected`() {
        val compat = PluginCompat.negotiate(PluginDeclaration(apiVersion = 2))
        assertTrue(compat is Compat.Rejected)
        assertFalse(compat.accepted)
        assertTrue((compat as Compat.Rejected).reason.contains("apiVersion=2"))

        // minHostApi 高过宿主同样拒载。
        val min = PluginCompat.negotiate(PluginDeclaration(apiVersion = 1, minHostApi = 9))
        assertTrue(min is Compat.Rejected)
        assertTrue((min as Compat.Rejected).reason.contains("v9"))
    }

    /** 拒载清单里的槽位一个都不许被注册（协商先于 register）。 */
    @Test
    fun `rejected declaration keeps registry empty`() {
        val compat = PluginCompat.negotiate(PluginDeclaration(apiVersion = 2, slots = listOf(SlotId.Settings.wire)))
        assertFalse(compat.accepted)
        val r = PluginRegistry()
        assertEquals(Compat.Compatible, r.compat("new.plugin"))
        assertTrue(r.inSlot(SlotId.Settings).isEmpty())
    }

    // ---------------------------------------------------------------- ③ 降级

    /** 认不出的槽位：降级 + 原因里带槽位名，注册表不受影响。 */
    @Test
    fun `unknown slot degrades with slot name in reason`() {
        val compat = PluginCompat.negotiate(
            PluginDeclaration(apiVersion = 1, slots = listOf("chat.future.slot")),
        )
        assertTrue(compat is Compat.Degraded)
        assertTrue(compat.accepted)
        val reasons = (compat as Compat.Degraded).reasons
        assertTrue(reasons.single().contains("chat.future.slot"))
    }

    /** 认不出的动作同样降级；不是降成 prompt（那等于替插件乱发消息）。 */
    @Test
    fun `unknown action degrades`() {
        assertFalse(PluginCompat.isKnownAction("rce"))
        val compat = PluginCompat.negotiate(PluginDeclaration(apiVersion = 1, actions = listOf("rce")))
        assertTrue(compat is Compat.Degraded)
        assertTrue((compat as Compat.Degraded).reasons.single().contains("rce"))
        assertTrue("空 kind 等同 prompt", PluginCompat.isKnownAction(""))
    }

    /** 注册期记下的降级原因会进 [PluginRegistry.compat]，界面直接读得到。 */
    @Test
    fun `registry reports degraded reasons`() {
        val r = PluginRegistry()
        r.degrade("p1", "未知槽位 chat.future.slot，已忽略")
        val compat = r.compat("p1")
        assertTrue(compat is Compat.Degraded)
        assertTrue((compat as Compat.Degraded).reasons.single().contains("chat.future.slot"))
    }

    // ---------------------------------------------------------------- ④ 配额

    /** 输入框按钮单独计数：超过 composer.buttons 上限的那条被拒，原因可读，且没进列表。 */
    @Test
    fun `composer button over quota is rejected with reason`() {
        val r = PluginRegistry()
        val max = PluginQuota.ComposerButtons.max
        (1..max).forEach { assertNull(r.composerButton(button("ok" + it, "p1"))) }
        val reason = r.composerButton(button("over", "p1"))

        assertTrue(reason != null && reason.contains("composer.buttons") && reason.contains("上限 " + max))
        assertEquals(max, r.composerButtons.size)
        assertTrue(r.quotaUsed("p1", PluginQuota.ComposerButtons) == max)
        assertEquals(reason, r.rejections.single().reason)
        assertTrue(r.compat("p1") is Compat.Degraded)
    }

    /** 「+」面板条目与工具栏按钮分开计数：塞满面板不会把按钮挤掉（历史插件就是这种组合）。 */
    @Test
    fun `composer panel entries and buttons do not share a quota`() {
        val r = PluginRegistry()
        repeat(PluginQuota.ComposerActions.max) {
            assertNull(r.contribute(contribution(SlotId.ComposerPlus, "e" + it, "p1")))
        }
        assertNull(r.composerButton(button("btn", "p1")))
        assertEquals(1, r.composerButtons.size)
        // 按钮占自己那一档的 1 个名额，面板那档已满不影响它。
        assertEquals(1, r.quotaUsed("p1", PluginQuota.ComposerButtons))
        assertEquals(PluginQuota.ComposerActions.max, r.quotaUsed("p1", PluginQuota.ComposerActions))
    }

    /** 七类配额各自生效：快捷指令 2、面板 4、输入框上方 1。 */
    @Test
    fun `quota limits per slot kind`() {
        val r = PluginRegistry()
        assertNull(r.quickCommand(quick("q1", "p1")))
        assertNull(r.quickCommand(quick("q2", "p1")))
        assertTrue(r.quickCommand(quick("q3", "p1"))!!.contains("micro"))

        repeat(4) { assertNull(r.configPage(PluginConfigPage("pg" + it, "P" + it, pluginId = "p1") { })) }
        assertTrue(r.configPage(PluginConfigPage("pg9", "P9", pluginId = "p1") { })!!.contains("panels"))

        assertNull(r.contribute(contribution(SlotId.ChatBottom, "strip", "p1")))
        assertTrue(
            r.contribute(contribution(SlotId.ChatBottom, "strip2", "p1"))!!.contains("composer.strip"),
        )
    }

    /** 内置插件（pluginId 为空）不参与配额，避免宿主自己的贡献被挤掉。 */
    @Test
    fun `builtin contributions skip quota`() {
        val r = PluginRegistry()
        repeat(5) { assertNull(r.quickCommand(quick("b" + it, ""))) }
        assertEquals(5, r.quickCommands.size)
        assertTrue(r.quotaUsed("", PluginQuota.Micro) == 0)
    }

    // ---------------------------------------------------------------- ⑤ 撤销

    /** clearPlugin 之后所有槽位/动作/配额计数归零，且不留残条。 */
    @Test
    fun `clearPlugin zeroes every slot and quota`() {
        val r = PluginRegistry()
        SlotId.entries.forEach { r.contribute(contribution(it, "id-" + it.wire, "p1")) }
        r.messageRenderer("p1.renderer", "p1") { }
        r.sessionRowExtra("p1") { }
        r.agentCard("p1") { }
        r.settingsItem(SettingsItem("s1", "S1", pluginId = "p1") { })
        r.bottomTab(BottomTab("t1", "T1", pluginId = "p1") { })
        r.drawerEntry(DrawerEntry("d1", "D1", pluginId = "p1") { })
        r.theme(ThemeContribution("th1", "TH1", 0xFF112233, 0xFF112233, 0xFFEEEEEE, pluginId = "p1"))
        r.quickCommand(quick("q1", "p1"))
        r.composerButton(button("c1", "p1"))
        r.configPage(PluginConfigPage("pg1", "PG1", pluginId = "p1") { })

        assertTrue(r.contributions.isNotEmpty())
        r.clearPlugin("p1")

        assertTrue("贡献必须清空", r.contributions.isEmpty())
        assertTrue(r.messageRenderers.isEmpty())
        assertTrue(r.sessionRowExtras.isEmpty())
        assertTrue(r.agentCardExtras.isEmpty())
        assertTrue(r.settingsItems.isEmpty())
        assertTrue(r.bottomTabs.isEmpty())
        assertTrue(r.drawerEntries.isEmpty())
        assertTrue(r.themes.isEmpty())
        assertTrue(r.quickCommands.isEmpty())
        assertTrue(r.composerButtons.isEmpty())
        assertTrue(r.configPages.isEmpty())
        SlotId.entries.forEach { assertTrue(it.wire + " 未清空", r.inSlot(it).isEmpty()) }
        PluginQuota.entries.forEach { assertTrue(it.key + " 配额未归零", r.quotaUsed("p1", it) == 0) }
        assertEquals(Compat.Compatible, r.compat("p1"))
    }

    /** 撤销只清自己那一家：另一个插件的注册与配额不动。 */
    @Test
    fun `clearPlugin leaves other plugins intact`() {
        val r = PluginRegistry()
        r.composerButton(button("a", "p1"))
        r.composerButton(button("b", "p2"))
        r.clearPlugin("p1")
        assertEquals(listOf("b"), r.composerButtons.map { it.id })
        assertTrue(r.quotaUsed("p1", PluginQuota.ComposerButtons) == 0)
        assertTrue(r.quotaUsed("p2", PluginQuota.ComposerButtons) == 1)
    }

    /** 宿主函数 ABI 清单是版本契约的一部分：12 个，改名即破坏兼容。 */
    @Test
    fun `host function set is the published abi`() {
        assertEquals(12, PLUGIN_HOST_FUNCTIONS.size)
        assertTrue(PLUGIN_HOST_FUNCTIONS.contains("registerContribution"))
        assertTrue(PLUGIN_HOST_FUNCTIONS.contains("setSessionPersona"))
    }
}
