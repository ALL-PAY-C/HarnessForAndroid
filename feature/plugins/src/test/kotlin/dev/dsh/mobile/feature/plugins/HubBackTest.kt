package dev.dsh.mobile.feature.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HubBackTest {

    @Test
    fun detail_page_closes_first() {
        assertEquals(HubBackStep.ClosePage, hubBackStep(pageOpen = true, slotOpen = true, folderOpen = true))
    }

    @Test
    fun slot_detail_closes_before_the_folder() {
        assertEquals(HubBackStep.CloseSlot, hubBackStep(pageOpen = false, slotOpen = true, folderOpen = true))
    }

    @Test
    fun folder_closes_before_leaving_the_hub() {
        assertEquals(HubBackStep.CloseFolder, hubBackStep(pageOpen = false, slotOpen = false, folderOpen = true))
    }

    @Test
    fun hub_list_hands_back_to_the_navigation_layer() {
        assertEquals(HubBackStep.NotMine, hubBackStep(pageOpen = false, slotOpen = false, folderOpen = false))
    }

    /** 回归：页内任一层级打开时都必须自己吃掉返回，不能漏给 NavHost。 */
    @Test
    fun in_page_levels_never_leak_to_the_navigation_layer() {
        listOf(
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(false, false, true),
        ).forEach { (page, slot, folder) ->
            assertNotEquals(
                "page=" + page + " slot=" + slot + " folder=" + folder + " 必须在本页消费返回",
                HubBackStep.NotMine,
                hubBackStep(page, slot, folder),
            )
        }
    }
}
