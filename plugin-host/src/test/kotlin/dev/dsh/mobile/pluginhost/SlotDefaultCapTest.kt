package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginapi.SlotId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 会话内槽位的默认可见上限。
 *
 * 这是「装上插件毫无反应」的正面修复：以前 visibleSlots 只认用户手动摆放的结果，
 * 没摆放就返回空列表，插件注册了也不会出现在界面上。
 */
class SlotDefaultCapTest {

    @Test
    fun sessionSlotsShowContributionsWithoutPlacement() {
        assertEquals(4, defaultSlotCap(SlotId.ComposerPlus))
        assertEquals(2, defaultSlotCap(SlotId.ChatOverflow))
        assertEquals(2, defaultSlotCap(SlotId.ChatBottom))
        assertEquals(3, defaultSlotCap(SlotId.ChatSettings))
    }

    @Test
    fun structuralSlotsStillRequireExplicitPlacement() {
        // 抽屉 / 插件页 / 设置页改的是主界面结构，装上就自动长出来比「没反应」更烦人。
        assertNull(defaultSlotCap(SlotId.Drawer))
        assertNull(defaultSlotCap(SlotId.PluginPage))
        assertNull(defaultSlotCap(SlotId.Settings))
        assertNull(defaultSlotCap(SlotId.SessionRow))
        assertNull(defaultSlotCap(SlotId.AgentPreset))
    }
}
