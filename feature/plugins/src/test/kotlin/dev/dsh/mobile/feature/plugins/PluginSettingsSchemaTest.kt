package dev.dsh.mobile.feature.plugins

import dev.dsh.mobile.pluginhost.ConfigSpec
import dev.dsh.mobile.pluginhost.PluginManifestFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置 schema：插件只声明 config[]，宿主把它翻成可渲染的行。
 *
 * 三条必须成立的底线：
 *  · 未知类型降级成说明行，整页不受影响（用户的诉求是「插件设置界面能用」，不是「坏插件白屏」）；
 *  · 分组标题只是插入，不重排字段；
 *  · 滑杆没声明边界时也要有一个能拖的区间。
 */
class PluginSettingsSchemaTest {

    private fun spec(
        key: String,
        type: String,
        label: String = key,
        default: String = "",
        hint: String = "",
        options: List<String> = emptyList(),
        target: String = "",
        section: String = "",
        min: Double? = null,
        max: Double? = null,
        step: Double? = null,
    ) = ConfigSpec(
        key = key,
        label = label,
        type = type,
        default = default,
        hint = hint,
        options = options,
        target = target,
        section = section,
        min = min,
        max = max,
        step = step,
    )

    @Test
    fun groupRowWithoutKeyBecomesSectionHeader() {
        // 老插件习惯把分组写成独立一行 type=group，而且常常不给 key（它不存值）。
        // 以前这种行会被「空 key 跳过」规则吃掉，用户看到的是字段全平铺、没有分组。
        val schema = settingsSchema(
            listOf(
                spec("", "group", label = "基础"),
                spec("a", "switch"),
                spec("", "section", label = "调参"),
                spec("b", "slider", min = 0.0, max = 10.0),
            )
        )
        assertEquals(
            listOf("section:基础", "a", "section:调参", "b"),
            schema.items.map { it.key },
        )
        assertTrue("分组行不是不支持的写法", schema.unsupported.isEmpty())
    }

    @Test
    fun unknownTypeBecomesInfoRowAndIsReported() {
        val schema = settingsSchema(listOf(spec("weird", "colorPicker"), spec("ok", "text")))
        val info = schema.items.filterIsInstance<SettingItem.Info>().single()
        assertEquals("weird", info.key)
        assertTrue("降级说明要写清是哪个类型", info.hint.contains("colorPicker"))
        assertEquals(listOf("weird:colorPicker"), schema.unsupported)
        assertTrue("其余字段照常渲染", schema.items.any { it is SettingItem.Entry && it.key == "ok" })
    }

    @Test
    fun sectionHeaderIsInsertedOnceWithoutReordering() {
        val schema = settingsSchema(
            listOf(
                spec("a", "text", section = "基础"),
                spec("b", "text", section = "基础"),
                spec("c", "text", section = "高级"),
                spec("d", "text"),
            )
        )
        assertEquals(
            listOf("a", "b", "c", "d"),
            schema.items.mapNotNull { if (it is SettingItem.Section) null else it.key },
        )
        assertEquals(
            listOf("基础", "高级"),
            schema.items.filterIsInstance<SettingItem.Section>().map { it.title },
        )
    }

    @Test
    fun blankKeyIsSkipped() {
        val schema = settingsSchema(listOf(spec("", "text"), spec("ok", "switch")))
        assertEquals(1, schema.items.size)
        assertTrue(schema.items.single() is SettingItem.Toggle)
    }

    @Test
    fun sliderUsesDeclaredBoundsAndSteps() {
        val slider = settingsSchema(
            listOf(spec("v", "slider", default = "30", min = 0.0, max = 120.0, step = 10.0))
        ).items.single() as SettingItem.Slider
        assertEquals(0f, slider.min, 0f)
        assertEquals(120f, slider.max, 0f)
        assertEquals(30f, slider.value, 0f)
        assertEquals("0..120 步长 10 → 11 档，steps = 档数-1", 11, slider.steps)
    }

    @Test
    fun sliderWithoutBoundsFallsBackToWideRange() {
        val slider = settingsSchema(listOf(spec("v", "range", default = "999")))
            .items.single() as SettingItem.Slider
        assertEquals(0f, slider.min, 0f)
        assertEquals(100f, slider.max, 0f)
        assertEquals("默认值必须夹进区间", 100f, slider.value, 0f)
        assertEquals(0, slider.steps)
    }

    @Test
    fun sliderWithInvertedBoundsStillDraggable() {
        val slider = settingsSchema(listOf(spec("v", "slider", min = 50.0, max = 10.0)))
            .items.single() as SettingItem.Slider
        assertEquals(50f, slider.min, 0f)
        assertEquals(150f, slider.max, 0f)
    }

    @Test
    fun actionResolvesTargetDefault() {
        val schema = settingsSchema(
            listOf(
                spec("token", "text", default = "abc"),
                spec("reset", "action", label = "恢复默认", target = "token", default = "ignored"),
            )
        )
        val action = schema.items.filterIsInstance<SettingItem.Action>().single()
        assertEquals("reset", action.key)
        assertEquals("token", action.target)
        assertEquals("abc", action.default)
    }

    @Test
    fun typesMapToExpectedRows() {
        val schema = settingsSchema(
            listOf(
                spec("s", "switch"),
                spec("p", "password"),
                spec("n", "number"),
                spec("c", "select", options = listOf("x", "y")),
                spec("t", "text"),
                spec("i", "info", hint = "只是说明"),
            )
        )
        assertEquals(SettingItem.Toggle::class, schema.items[0]::class)
        assertTrue((schema.items[1] as SettingItem.Entry).secret)
        assertTrue((schema.items[2] as SettingItem.Entry).numeric)
        assertEquals(listOf("x", "y"), (schema.items[3] as SettingItem.Choice).options)
        assertTrue((schema.items[4] as SettingItem.Entry).multiline)
        assertTrue(schema.items[5] is SettingItem.Info)
    }

    @Test
    fun emptyConfigProducesEmptySchema() {
        assertTrue(settingsSchema(emptyList()).isEmpty)
    }

    @Test
    fun oldPluginWithoutConfigKeepsLegacyEmptyText() {
        // 老插件只写 description：没有 config、没有 page ⇒ 设置页明确说「没有设置项」，
        // 不是空白页，也不是一行看不出所以然的简介。
        val manifest = PluginManifestFile(
            id = "old.token",
            name = "Token Saver",
            version = "0.9",
            description = "省 token 的老插件",
        )
        val entry = pluginSettingsEntries(listOf(manifest), emptyList()).single()
        assertTrue(entry.schema.isEmpty)
        assertFalse(entry.hasSettings)
        assertEquals(NO_SETTINGS_TEXT, entry.emptyText)
        assertEquals(NO_SETTINGS_TEXT, entry.rowSubtitle)
    }

    @Test
    fun schemaMakesSettingsExist() {
        val manifest = PluginManifestFile(
            id = "new.settings",
            name = "新插件",
            version = "1.0",
            description = "",
            config = listOf(spec("v", "slider", default = "5")),
        )
        val entry = pluginSettingsEntries(listOf(manifest), emptyList()).single()
        assertTrue(entry.hasSettings)
        assertEquals(1, entry.schema.items.size)
    }
}
