package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginapi.*
import org.junit.Assert.*
import org.junit.Test

class SlotPlacementsTest {
    private val a = SlotContribution(SlotId.Settings, "component", "A", pluginId = "a")
    private val b = a.copy(pluginId = "b")
    @Test fun installedComponentsAreInvisibleUntilPlaced() {
        assertTrue(SlotPlacements.select(emptyList(), SlotId.Settings, listOf(a, b)).isEmpty())
    }
    @Test fun placementResolvesOwnerSourceAndDestinationAndPreservesOrder() {
        val items = listOf(
            SlotPlacement("b", b.id, b.slot, SlotId.ComposerPlus),
            SlotPlacement("a", a.id, a.slot, SlotId.ComposerPlus),
        )
        val restored = SlotPlacements.decode(SlotPlacements.encode(items))
        assertEquals(items, restored)
        val selected = SlotPlacements.select(restored, SlotId.ComposerPlus, listOf(a, b))
        assertEquals(listOf("b", "a"), selected.map { it.pluginId })
        assertTrue(selected.all { it.slot == SlotId.ComposerPlus })
        assertTrue(SlotPlacements.select(restored, SlotId.Settings, listOf(a, b)).isEmpty())
    }
    @Test fun uninstalledAndCorruptedRecordsProduceNoComponents() {
        assertTrue(SlotPlacements.decode("broken").isEmpty())
        assertTrue(SlotPlacements.select(listOf(SlotPlacement("missing", a.id, a.slot, a.slot)), a.slot, listOf(a)).isEmpty())
    }
    @Test fun moveReordersWithinTargetSlotOnly() {
        val drawerA = SlotPlacement("a", "component", SlotId.Settings, SlotId.Drawer)
        val composerX = SlotPlacement("x", "component", SlotId.ComposerPlus, SlotId.ComposerPlus)
        val drawerB = SlotPlacement("b", "component", SlotId.Settings, SlotId.Drawer)
        val drawerC = SlotPlacement("c", "component", SlotId.Settings, SlotId.Drawer)
        val items = listOf(drawerA, composerX, drawerB, drawerC)

        // 把 b 在抽屉槽内上移一位：只能与 a 交换，composerX 属于别的槽位，必须保持原位。
        val moved = SlotPlacements.move(items, "b", "component", SlotId.Drawer, -1)
        assertEquals(listOf("b", "x", "a", "c"), moved.map { it.pluginId })

        // 顶部的 a 再上移、底部的 c 再下移都是 no-op，整体顺序不变。
        assertEquals(items, SlotPlacements.move(items, "a", "component", SlotId.Drawer, -1))
        assertEquals(items, SlotPlacements.move(items, "c", "component", SlotId.Drawer, +1))

        // 下移一位：c 与 b 交换，同样不碰 composerX。
        val down = SlotPlacements.move(items, "b", "component", SlotId.Drawer, +1)
        assertEquals(listOf("a", "x", "c", "b"), down.map { it.pluginId })
    }
    @Test fun selectDiscriminatesByOwnerIdAndSourceForBridgeShapedIds() {
        // 桥接命名空间 id 的判别：同 id 不同 owner、同 owner+id 不同 source 都不能顶替真项。
        val bridgeId = "drawer-entry:1:p1:x"
        val bridged = SlotContribution(SlotId.Drawer, bridgeId, "原生", pluginId = "p")
        val sameIdOtherOwner = SlotContribution(SlotId.Drawer, bridgeId, "伪造owner", pluginId = "q")
        val sameOwnerSameIdOtherSource = SlotContribution(SlotId.Settings, bridgeId, "伪造source", pluginId = "p")

        val candidates = listOf(sameIdOtherOwner, sameOwnerSameIdOtherSource, bridged)
        val placement = SlotPlacement("p", bridgeId, SlotId.Drawer, SlotId.Drawer)

        val selected = SlotPlacements.select(listOf(placement), SlotId.Drawer, candidates)
        assertEquals(listOf(bridged), selected)
        // copy(slot=target) 后，桥接项 target=Drawer，故 slot 仍为 Drawer（source 语义保留）。
        assertTrue(selected.all { it.slot == SlotId.Drawer })
    }
}
