package dev.dsh.mobile.feature.plugins

import org.junit.Assert.assertEquals
import dev.dsh.mobile.design.UiText
import dev.dsh.mobile.design.R as DesignR
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件管理页每行的文案逻辑：失败优先、截断、空白不算失败。
 *
 * 这里守的是「装上了但加载失败却被显示成已启用」那个缺陷 —— 状态只能由真实加载回执决定。
 */
class PluginRowStatusTest {

    @Test
    fun enabledWithoutFailureShowsEnabled() {
        val row = pluginRowStatus(enabled = true, loadError = null)
        assertEquals(UiText.Res(DesignR.string.plugins_row_enabled), row.text)
        assertFalse(row.failed)
        assertNull(row.detail)
    }

    @Test
    fun disabledWithoutFailureShowsDisabled() {
        val row = pluginRowStatus(enabled = false, loadError = "")
        assertEquals(UiText.Res(DesignR.string.plugins_row_disabled), row.text)
        assertFalse("空白错误不算失败", row.failed)
    }

    @Test
    fun loadErrorWinsOverEnabled() {
        val row = pluginRowStatus(enabled = true, loadError = " [load] Cannot find package '@deepseek-ai/dsh-credentials' ")
        assertEquals(UiText.Res(DesignR.string.plugins_row_load_failed), row.text)
        assertTrue(row.failed)
        assertEquals("[load] Cannot find package '@deepseek-ai/dsh-credentials'", row.detail)
    }

    /**
     * T9.18-③ 的现场形态：插件跑不起来时 enabled 恒为 false，旧逻辑据此写「已停用」，
     * 用户看到的是「我没启用」而不是「它坏了」。失败态必须压过 enabled=false。
     */
    @Test
    fun disabledRowWithLoadErrorStillShowsFailure() {
        val row = pluginRowStatus(enabled = false, loadError = "[exec] index.js 执行失败")
        assertEquals(UiText.Res(DesignR.string.plugins_row_load_failed), row.text)
        assertTrue(row.failed)
        assertEquals("[exec] index.js 执行失败", row.detail)
    }

    @Test
    fun longErrorIsClippedWithEllipsis() {
        val row = pluginRowStatus(enabled = true, loadError = "x".repeat(500))
        assertEquals(UiText.Res(DesignR.string.plugins_row_load_failed), row.text)
        assertEquals(LOAD_ERROR_MAX_CHARS + 1, row.detail!!.length)
        assertTrue(row.detail!!.endsWith("…"))
    }
}
