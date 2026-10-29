package dev.dsh.mobile.feature.plugins

import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.pluginapi.SlotId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 插件中心列表拼装自检：顺序、去重、多槽位并存。 */
class PluginHubRowsTest {

    private fun slot(id: String, slot: SlotId = SlotId.PluginPage) =
        SlotContribution(slot = slot, id = id, label = id, pluginId = "p")

    /** 配置页永远排在槽位页前面，插件的声明式页面不能挤掉内置的那几页。 */
    @Test
    fun pagesComeBeforeSlotRows() {
        val rows = mergeHubRows(
            pageKeys = listOf("app.plugins.market", "app.plugins.manage"),
            slotKeys = listOf("plugin.page:hello"),
        )
        assertEquals(3, rows.size)
        assertEquals(listOf(0, 1), rows.take(2).map { it.pageIndex })
        assertEquals(-1, rows[2].pageIndex)
        assertEquals(0, rows[2].slotIndex)
    }

    /** 同一个 id 重复注册（插件热加载常见）只留第一次。 */
    @Test
    fun duplicateKeysCollapse() {
        val rows = mergeHubRows(
            pageKeys = listOf("a", "a", "b"),
            slotKeys = listOf("x", "x"),
        )
        assertEquals(listOf("page:a", "page:b", "slot:x"), rows.map { it.key })
        // 留下的是第一次出现的位置，索引不能跟着被压掉。
        assertEquals(0, rows[0].pageIndex)
        assertEquals(2, rows[1].pageIndex)
        assertEquals(0, rows[2].slotIndex)
    }

    /** 两个槽位里的同 id 贡献是两条不同的行，不能被合并。 */
    @Test
    fun sameIdInDifferentSlotsStaysTwoRows() {
        val settings = slot("greet", SlotId.Settings)
        val drawer = slot("greet", SlotId.Drawer)
        assertTrue(slotRowKey(settings) != slotRowKey(drawer))

        val merged = mergeHubSlots(listOf(settings), listOf(drawer))
        assertEquals(2, merged.size)
        val rows = mergeHubRows(emptyList(), merged.map { slotRowKey(it) })
        assertEquals(2, rows.size)
    }

    /** 合并只做拼接，顺序 = 调用方参数顺序，且不动既有 entry 列表。 */
    @Test
    fun mergeKeepsOrderAndLength() {
        val merged = mergeHubSlots(
            listOf(slot("a"), slot("b")),
            listOf(slot("c")),
            emptyList(),
        )
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
        assertTrue(mergeHubSlots().isEmpty())
    }

    /** 空输入不能炸，也不能凭空造行。 */
    @Test
    fun emptyInputYieldsNoRows() {
        assertTrue(mergeHubRows(emptyList(), emptyList()).isEmpty())
    }
}
