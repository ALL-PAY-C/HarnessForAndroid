package dev.dsh.mobile.feature.plugins

import org.junit.Assert.assertEquals
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
        assertEquals("已启用", row.text)
        assertFalse(row.failed)
        assertNull(row.detail)
    }

    @Test
    fun disabledWithoutFailureShowsDisabled() {
        val row = pluginRowStatus(enabled = false, loadError = "")
        assertEquals("已停用", row.text)
        assertFalse("空白错误不算失败", row.failed)
    }

    @Test
    fun loadErrorWinsOverEnabled() {
        val row = pluginRowStatus(enabled = true, loadError = " [load] Cannot find package '@deepseek-ai/dsh-credentials' ")
        assertEquals("加载失败", row.text)
        assertTrue(row.failed)
        assertEquals("[load] Cannot find package '@deepseek-ai/dsh-credentials'", row.detail)
    }

    @Test
    fun longErrorIsClippedWithEllipsis() {
        val row = pluginRowStatus(enabled = true, loadError = "x".repeat(500))
        assertEquals("加载失败", row.text)
        assertEquals(LOAD_ERROR_MAX_CHARS + 1, row.detail!!.length)
        assertTrue(row.detail!!.endsWith("…"))
    }
}
