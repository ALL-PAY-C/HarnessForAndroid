package dev.dsh.mobile.core.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 特殊格式渲染的契约：全局默认 + 会话级覆盖。
 *
 * 钉死两件事：①默认是开的；②没有覆盖记录的会话（= 新会话）严格跟随全局默认，
 * 单独切过的会话只影响它自己。
 */
class RenderSpecialSettingsTest {

    @Test
    fun `默认开启`() {
        assertTrue(AppSettings.DEFAULT.renderSpecialFormats)
        assertTrue(AppSettings.DEFAULT.renderSpecialFor("s1"))
        // 拿不到会话 id 时也不能意外关掉渲染。
        assertTrue(AppSettings.DEFAULT.renderSpecialFor(null))
    }

    @Test
    fun `新会话跟随全局默认`() {
        val off = AppSettings.DEFAULT.copy(renderSpecialFormats = false)
        assertFalse(off.renderSpecialFor("brand-new"))
        val on = AppSettings.DEFAULT.copy(renderSpecialFormats = true)
        assertTrue(on.renderSpecialFor("brand-new"))
    }

    @Test
    fun `会话覆盖优先于全局默认`() {
        val s = AppSettings.DEFAULT.copy(
            renderSpecialFormats = true,
            renderSpecialOverrides = mapOf("a" to false),
        )
        assertFalse(s.renderSpecialFor("a"))
        assertTrue(s.renderSpecialFor("b"))
    }

    @Test
    fun `覆盖序列化可往返`() {
        // 会话 id 里带 '=' 也不能被切错（按最后一个 '=' 分）。
        val map = linkedMapOf("s1" to true, "s2" to false, "群:会话=1" to true)
        assertEquals(map, parseRenderSpecialOverrides(encodeRenderSpecialOverrides(map)))
    }

    @Test
    fun `脏值不崩`() {
        assertTrue(parseRenderSpecialOverrides(null).isEmpty())
        assertTrue(parseRenderSpecialOverrides("").isEmpty())
        assertEquals(mapOf("a" to true), parseRenderSpecialOverrides("a=1\u0001broken\u0001=1"))
    }
}
