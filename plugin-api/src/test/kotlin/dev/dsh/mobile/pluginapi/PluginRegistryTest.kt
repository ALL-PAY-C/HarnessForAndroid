package dev.dsh.mobile.pluginapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRegistryTest {

    private fun quick(id: String, plugin: String) =
        QuickCommand(id = id, label = id, payload = "/$id", pluginId = plugin)

    @Test
    fun `commands are registered per plugin`() {
        val r = PluginRegistry()
        r.quickCommand(quick("a", "p1"))
        r.quickCommand(quick("b", "p1"))
        r.quickCommand(quick("c", "p2"))

        assertEquals(3, r.quickCommands.size)
        assertEquals(listOf("a", "b", "c"), r.quickCommands.map { it.id })
    }

    @Test
    fun `clearing one plugin keeps the others`() {
        val r = PluginRegistry()
        r.quickCommand(quick("a", "p1"))
        r.quickCommand(quick("b", "p2"))
        r.messageRenderer("p1.renderer", "p1") { }
        r.theme(ThemeContribution("t1", "T1", 0xFF112233, 0xFF112233, 0xFFEEEEEE))

        r.clearPlugin("p1")

        assertEquals(listOf("b"), r.quickCommands.map { it.id })
        assertTrue(r.messageRenderers.isEmpty())
        assertEquals(1, r.themes.size)
    }

    @Test
    fun `re-registering after clear restores slots`() {
        val r = PluginRegistry()
        r.quickCommand(quick("a", "p1"))
        r.clearPlugin("p1")
        r.quickCommand(quick("a", "p1"))
        assertEquals(1, r.quickCommands.size)
    }

    @Test
    fun `same slot id from different plugins both kept`() {
        val r = PluginRegistry()
        r.contribute(SlotContribution(SlotId.Settings, "dup", "A", pluginId = "p1"))
        r.contribute(SlotContribution(SlotId.Settings, "dup", "B", pluginId = "p2"))
        assertEquals(2, r.inSlot(SlotId.Settings).size)
        // 同 owner 重投仍按 id+slot+owner 去重
        r.contribute(SlotContribution(SlotId.Settings, "dup", "A2", pluginId = "p1"))
        assertEquals(2, r.inSlot(SlotId.Settings).size)
        assertEquals(listOf("A2", "B"), r.inSlot(SlotId.Settings).map { it.label })
    }

    @Test
    fun `clearPlugin removes theme and extras by owner`() {
        val r = PluginRegistry()
        r.theme(ThemeContribution("t1", "T1", 0xFF112233, 0xFF112233, 0xFFEEEEEE, pluginId = "p1"))
        r.theme(ThemeContribution("t2", "T2", 0xFF112233, 0xFF112233, 0xFFEEEEEE, pluginId = "p2"))
        r.sessionRowExtra("p1") { }
        r.sessionRowExtra("p2") { }
        r.agentCard("p1") { }
        r.agentCard("p2") { }

        r.clearPlugin("p1")

        assertEquals(listOf("t2"), r.themes.map { it.id })
        assertEquals(1, r.sessionRowExtras.size)
        assertEquals(1, r.agentCardExtras.size)
    }
}
