package dev.dsh.mobile.feature.plugins

import dev.dsh.mobile.pluginhost.ConfigSpec
import dev.dsh.mobile.pluginhost.PluginManifestFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** config 的 "action" 行（重置按钮）：默认值查找、写回、target 缺失时的降级。 */
class PluginSettingsActionTest {

    private val config = listOf(
        ConfigSpec(key = "entryText", label = "注入词", type = "text", default = "默认注入词"),
        ConfigSpec(key = "entryReset", label = "重置注入词为默认", type = "action", target = "entryText"),
    )

    private fun manifest(config: List<ConfigSpec>) = PluginManifestFile(
        id = "dsh.sauce",
        name = "破甲",
        version = "1.0.0",
        description = "",
        homepage = "",
        config = config,
    )

    @Test
    fun actionRowCarriesTargetDefault() {
        val action = configActions(config).single()
        assertEquals("entryReset", action.key)
        assertEquals("重置注入词为默认", action.label)
        assertEquals("entryText", action.target)
        assertEquals("默认注入词", action.default)
    }

    @Test
    fun resetWritesDefaultBack() {
        val action = configActions(config).single()
        val values = mapOf("entryText" to "用户改过的词", "other" to "保留")
        assertEquals(
            mapOf("entryText" to "默认注入词", "other" to "保留"),
            applyReset(values, action),
        )
    }

    @Test
    fun missingTargetDegradesWithoutCrash() {
        val broken = listOf(ConfigSpec(key = "entryReset", label = "重置", type = "action", target = "nope"))
        val action = configActions(broken).single()
        assertNull(action.default)
        val values = mapOf("entryText" to "原值")
        // 拿不到默认值就原样返回：不写空值、不新增键。
        assertEquals(values, applyReset(values, action))
    }

    @Test
    fun blankTargetDegrades() {
        val action = configActions(
            listOf(ConfigSpec(key = "r", label = "重置", type = "action", target = "   ")),
        ).single()
        assertEquals("", action.target)
        assertNull(action.default)
    }

    @Test
    fun actionRowIsNotAnInputField() {
        val entry = pluginSettingsEntries(
            installed = listOf(manifest(config)),
            pages = emptyList(),
            values = { mapOf("entryText" to "改过的") },
        ).single()
        // action 行不该再变成一个叫「重置」的输入框。
        assertEquals(listOf("entryText"), entry.fields.map { it.key })
        assertEquals(listOf("entryReset"), entry.actions.map { it.key })
        assertEquals("改过的", entry.valueOf(entry.fields.single()))
        assertTrue(entry.hasSettings)
        // 重置行的 target 是已声明字段，所以不会被当成「自己加的键值」。
        assertEquals(emptyMap<String, String>(), entry.extraValues)
    }

    @Test
    fun actionOnlyPluginStillHasSettings() {
        val entry = pluginSettingsEntries(
            installed = listOf(manifest(listOf(ConfigSpec(key = "r", label = "清理", type = "action")))),
            pages = emptyList(),
        ).single()
        assertTrue(entry.fields.isEmpty())
        assertTrue(entry.hasSettings)
        assertEquals("共 1 项设置", entry.rowSubtitle)
    }
}
