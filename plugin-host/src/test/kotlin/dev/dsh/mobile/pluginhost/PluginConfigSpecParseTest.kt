package dev.dsh.mobile.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * plugin.json 的 config[] 新字段：滑杆边界、分组标题、占位符、空选项过滤。
 *
 * 这些字段决定了「插件只声明、宿主自己画」能不能成立 —— 解析期丢掉一个 min/max，
 * 设置页的滑杆就会退化成 0..100，用户改不到真实区间。
 */
class PluginConfigSpecParseTest {

    private val manifest = PluginManifestFile.parse(
        """
        {
          "id": "demo.settings",
          "name": "设置演示",
          "description": "只有声明，没有代码",
          "config": [
            { "key": "volume", "label": "音量", "type": "slider", "default": "30",
              "min": 0, "max": 120, "step": 10, "hint": "拖动调整" },
            { "key": "mode", "label": "模式", "type": "select",
              "options": ["fast", "", "safe"] },
            { "key": "token", "label": "密钥", "type": "secret",
              "placeholder": "粘贴 token", "group": "账号" },
            { "key": "note", "label": "备注", "type": "text", "section": "其它" },
            { "key": "raw" }
          ]
        }
        """.trimIndent()
    ).getOrThrow()

    private fun spec(key: String): ConfigSpec = manifest.config.first { it.key == key }

    @Test
    fun sliderBoundsAndStepAreParsed() {
        val volume = spec("volume")
        assertEquals(0.0, volume.min!!, 0.0)
        assertEquals(120.0, volume.max!!, 0.0)
        assertEquals(10.0, volume.step!!, 0.0)
        assertEquals("拖动调整", volume.hint)
    }

    @Test
    fun groupIsAcceptedAsSectionAlias() {
        assertEquals("账号", spec("token").section)
        assertEquals("其它", spec("note").section)
        assertTrue("没写分组的字段应当留空", spec("mode").section.isEmpty())
    }

    @Test
    fun placeholderIsParsed() {
        assertEquals("粘贴 token", spec("token").placeholder)
        assertTrue(spec("volume").placeholder.isEmpty())
    }

    @Test
    fun blankOptionsAreDropped() {
        assertEquals(listOf("fast", "safe"), spec("mode").options)
    }

    @Test
    fun missingTypeFallsBackToText() {
        val raw = spec("raw")
        assertEquals("text", raw.type)
        assertEquals("raw", raw.label)
    }

    @Test
    fun nonNumericBoundsAreIgnoredInsteadOfThrowing() {
        val parsed = PluginManifestFile.parse(
            """
            { "id": "demo.bad", "name": "坏边界",
              "config": [ { "key": "k", "type": "slider", "min": "abc", "max": false } ] }
            """.trimIndent()
        ).getOrThrow()
        val k = parsed.config.single()
        assertNull("非数字的 min 必须当成没声明", k.min)
        assertNull("非数字的 max 必须当成没声明", k.max)
    }
}
