package dev.dsh.mobile.feature.plugins

import dev.dsh.mobile.pluginapi.DrawerEntry
import dev.dsh.mobile.pluginapi.PluginRegistry
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.pluginapi.SlotId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 原生 DrawerEntry 桥接：字段映射、单射编码、声明式伪造消解、resolver 三重身份判别。 */
class DrawerEntryBridgeTest {

    private fun entry(pluginId: String = "com.example", id: String = "settings") = DrawerEntry(
        id = id,
        label = "设置",
        subtitle = "管理界面",
        pluginId = pluginId,
        content = {},
    )

    private fun contrib(
        pluginId: String = "com.example",
        id: String = "settings",
        slot: SlotId = SlotId.Drawer,
    ) = SlotContribution(slot = slot, id = id, label = id, pluginId = pluginId)

    @Test
    fun drawerEntryBridgesToSlotContribution() {
        val e = entry("p", "x")
        val c = e.toSlotContribution()
        assertEquals(SlotId.Drawer, c.slot)
        assertEquals(drawerEntryBridgeId("p", "x"), c.id)
        assertTrue(c.id.startsWith(DRAWER_ENTRY_ID_PREFIX))
        assertEquals(e.label, c.label)
        assertEquals(e.subtitle, c.subtitle)
        assertEquals(e.pluginId, c.pluginId)
        assertNull(c.action)
        assertNull(c.ui)
        assertNull(c.icon)
        assertNotNull(c.content)
    }

    /** 冒号 tuple 单射：长度前缀编码让含冒号的 pluginId/entryId 也不会撞 id。 */
    @Test
    fun bridgeIdIsInjectiveAcrossColonTuples() {
        assertNotEquals(
            drawerEntryBridgeId("a:b", "c"),
            drawerEntryBridgeId("a", "b:c"),
        )
        assertNotEquals(
            drawerEntryBridgeId("a", "b"),
            drawerEntryBridgeId("a", "b~1"),
        )
    }

    /** 同 owner 同 id：声明式原始 id 与桥接 id 落不同命名空间，绝不共用。 */
    @Test
    fun bridgeIdIsNamespacedAndDistinctFromDeclarativeId() {
        val registry = PluginRegistry()
        registry.drawerEntry(entry("p", "x"))
        registry.contribute(contrib("p", "x", SlotId.Drawer))

        val candidates = registry.bridgedCandidates()
        assertEquals(2, candidates.size)
        assertEquals(setOf("x", drawerEntryBridgeId("p", "x")), candidates.map { it.id }.toSet())
    }

    /** 声明式刻意伪造完整桥接 id：同 owner 触发碰撞消解，原生项拿到确定性 ~1 后缀。 */
    @Test
    fun declarativeMimickingFullBridgeIdIsCollisionResolved() {
        val registry = PluginRegistry()
        registry.drawerEntry(entry("p", "x"))
        val bridgeId = drawerEntryBridgeId("p", "x")
        registry.contribute(contrib("p", bridgeId, SlotId.Drawer))

        val candidates = registry.bridgedCandidates()
        val resolver = registry.drawerEntryResolver()
        val bridged = candidates.first { resolver.isBridged(it) }
        val declarative = candidates.first { !resolver.isBridged(it) }

        assertEquals(bridgeId, declarative.id)
        assertEquals(bridgeId + "~1", bridged.id)
        assertNull(resolver.resolve(declarative))
        assertEquals("x", resolver.resolve(bridged)?.id)
        assertEquals(SlotId.Drawer, bridged.slot)
    }

    /** 同 owner 同 id、source 不同（Settings 声明式 vs Drawer 原生）：resolver 只认桥接项。 */
    @Test
    fun resolverDiscriminatesSameOwnerSameIdDifferentSource() {
        val registry = PluginRegistry()
        registry.drawerEntry(entry("p", "x"))
        registry.contribute(contrib("p", "x", SlotId.Settings))

        val resolver = registry.drawerEntryResolver()
        val bridged = registry.bridgedCandidates().first { it.slot == SlotId.Drawer }
        val declarative = registry.bridgedCandidates().first { it.slot == SlotId.Settings }

        assertTrue(resolver.isBridged(bridged))
        assertFalse(resolver.isBridged(declarative))
        assertEquals("x", resolver.resolve(bridged)?.id)
        assertNull(resolver.resolve(declarative))
    }

    /** 同 owner 同 id、同 Drawer 源槽位：声明式与桥接项并存，resolver 仍精确判别。 */
    @Test
    fun resolverDiscriminatesSameOwnerSameIdSameDrawerSource() {
        val registry = PluginRegistry()
        registry.drawerEntry(entry("p", "x"))
        registry.contribute(contrib("p", "x", SlotId.Drawer))

        val resolver = registry.drawerEntryResolver()
        val bridged = registry.bridgedCandidates().first { it.id.startsWith(DRAWER_ENTRY_ID_PREFIX) }
        val declarative = registry.bridgedCandidates().first { it.id == "x" }

        assertTrue(resolver.isBridged(bridged))
        assertFalse(resolver.isBridged(declarative))
        assertEquals("x", resolver.resolve(bridged)?.id)
        assertNull(resolver.resolve(declarative))
    }

    /** 伪 owner 同 bridgeId：声明式(a, b 的桥接 id) 与原生(b) 同 id，pluginId 校验拒掉伪造项。 */
    @Test
    fun resolverRejectsPseudoOwnerSameBridgeId() {
        val registry = PluginRegistry()
        registry.drawerEntry(entry("b", "x"))
        val bridgeId = drawerEntryBridgeId("b", "x")
        registry.contribute(contrib("a", bridgeId, SlotId.Drawer))

        val candidates = registry.bridgedCandidates()
        val resolver = registry.drawerEntryResolver()
        val bridged = candidates.first { it.pluginId == "b" }
        val fake = candidates.first { it.pluginId == "a" }

        assertEquals(bridgeId, bridged.id)
        assertEquals(bridgeId, fake.id)
        assertNotNull(resolver.resolve(bridged))
        assertEquals("b", resolver.resolve(bridged)?.pluginId)
        assertNull(resolver.resolve(fake))
    }

    /** 不同 owner 同原始 id：原生(b,x) 与声明式(a,x) 在候选池里 id 已不同（原生走命名空间），
     *  resolver 各自解析正确，不因原始 id 相同而混淆。 */
    @Test
    fun resolverDistinguishesDifferentOwnerSameRawId() {
        val registry = PluginRegistry()
        registry.drawerEntry(entry("b", "x"))
        registry.contribute(contrib("a", "x", SlotId.Drawer))

        val resolver = registry.drawerEntryResolver()
        val bridged = registry.bridgedCandidates().first { resolver.isBridged(it) }
        val declarative = registry.bridgedCandidates().first { !resolver.isBridged(it) }

        assertEquals("b", bridged.pluginId)
        assertEquals("a", declarative.pluginId)
        assertEquals(drawerEntryBridgeId("b", "x"), bridged.id)
        assertEquals("x", declarative.id)
        assertEquals("b", resolver.resolve(bridged)?.pluginId)
        assertNull(resolver.resolve(declarative))
    }

    /** 候选池 (pluginId, id) 二元组唯一：这正是 SlotPlacements.select 按 (pluginId,id,source)
     *  匹配不歧义的前提。混入伪造 bridgeId、冒号 tuple 后仍保证无重复二元组。 */
    @Test
    fun bridgedCandidatesHaveUniqueOwnerIdTuples() {
        val registry = PluginRegistry()
        registry.drawerEntry(entry("p", "x"))
        registry.drawerEntry(entry("a:b", "c"))
        registry.contribute(contrib("p", "x", SlotId.Drawer))
        registry.contribute(contrib("p", drawerEntryBridgeId("p", "x"), SlotId.Drawer))
        registry.contribute(contrib("q", drawerEntryBridgeId("p", "x"), SlotId.Drawer))

        val candidates = registry.bridgedCandidates()
        val tuples = candidates.map { it.pluginId to it.id }
        assertEquals(tuples.size, tuples.distinct().size)
    }
}
