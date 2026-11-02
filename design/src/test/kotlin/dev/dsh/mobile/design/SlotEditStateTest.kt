package dev.dsh.mobile.design

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可编辑态：默认关、开后在五个位置都出现「+」、关回去后全部消失。
 *
 * 这就是界面判断可编辑的唯一入口（插件槽位与内置布局共用这一个布尔），
 * 所以只测这一层，不去测各个屏幕的渲染。
 */
class SlotEditStateTest {

    @After
    fun tearDown() {
        SlotEditState.enabled = false
    }

    @Test
    fun offByDefault() {
        assertFalse(SlotEditState.enabled)
        SlotEditState.slots.forEach { assertFalse(SlotEditState.plusVisible(it)) }
    }

    @Test
    fun onShowsPlusEverywhere() {
        SlotEditState.enabled = true
        SlotEditState.slots.forEach { assertTrue(SlotEditState.plusVisible(it)) }
    }

    @Test
    fun offAgainHidesPlus() {
        SlotEditState.enabled = true
        SlotEditState.enabled = false
        SlotEditState.slots.forEach { assertFalse(SlotEditState.plusVisible(it)) }
    }
}
