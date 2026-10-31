package dev.dsh.mobile.design

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「UI 修改模式」状态机：默认关、开后在五个位置都出现「+」、关回去后全部消失。
 *
 * 这就是界面判断可编辑的唯一入口，所以只测这一层，不去测各个屏幕的渲染。
 */
class UiEditModeTest {

    @After
    fun tearDown() {
        UiEditMode.setEditEnabled(false)
    }

    @Test
    fun offByDefault() {
        assertFalse(UiEditMode.enabled)
        UiEditMode.slots.forEach { assertFalse(UiEditMode.plusVisible(it)) }
    }

    @Test
    fun onShowsPlusEverywhere() {
        UiEditMode.setEditEnabled(true)
        assertTrue(UiEditMode.enabled)
        UiEditMode.slots.forEach { assertTrue(UiEditMode.plusVisible(it)) }
    }

    @Test
    fun offAgainHidesPlus() {
        UiEditMode.setEditEnabled(true)
        UiEditMode.setEditEnabled(false)
        UiEditMode.slots.forEach { assertFalse(UiEditMode.plusVisible(it)) }
    }
}
