package dev.dsh.mobile.feature.plugins

import dev.dsh.mobile.pluginhost.ConfigSpec
import dev.dsh.mobile.pluginhost.PluginManifestFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 声明了 config 的插件，存储里的「未声明键」是旧版本遗留，不能平铺成自定义配置；
 * 没声明 config 的插件照旧展示用户自己加的键。
 */
class PluginSettingsExtraValuesTest {

    private fun manifest(id: String, config: List<ConfigSpec>) = PluginManifestFile(
        id = id,
        name = id,
        version = "0.7.0",
        description = "",
        homepage = "",
        config = config,
    )

    private fun entryOf(config: List<ConfigSpec>, stored: Map<String, String>) =
        pluginSettingsEntries(
            installed = listOf(manifest("dsh.sauce", config)),
            pages = emptyList(),
            values = { stored },
        ).single()

    @Test
    fun declaredConfigHidesLegacyKeys() {
        val entry = entryOf(
            config = listOf(
                ConfigSpec(key = "entryText", label = "破甲注入词", type = "text", default = "默认"),
                ConfigSpec(key = "entryReset", label = "重置注入词为默认", type = "action", target = "entryText"),
            ),
            stored = mapOf(
                "entryText" to "用户改过的词",
                "injectSession" to "1",
                "sessions" to "2",
                "rewrite" to "3",
                "autoPickModel" to "4",
            ),
        )
        assertEquals(emptyMap<String, String>(), entry.extraValues)
    }

    @Test
    fun actionOnlyConfigAlsoHidesLegacyKeys() {
        val entry = entryOf(
            config = listOf(ConfigSpec(key = "entryReset", label = "重置", type = "action", target = "entryText")),
            stored = mapOf("rewrite" to "1"),
        )
        assertEquals(emptyMap<String, String>(), entry.extraValues)
    }

    @Test
    fun pluginWithoutConfigStillShowsCustomKeys() {
        val entry = entryOf(config = emptyList(), stored = mapOf("myKey" to "myValue"))
        assertEquals(mapOf("myKey" to "myValue"), entry.extraValues)
        assertTrue(entry.hasSettings)
    }

    @Test
    fun declaredFieldStillReadsStoredValue() {
        val entry = entryOf(
            config = listOf(ConfigSpec(key = "entryText", label = "破甲注入词", type = "text", default = "默认")),
            stored = mapOf("entryText" to "用户改过的词", "rewrite" to "3"),
        )
        assertEquals("用户改过的词", entry.values["entryText"])
        assertEquals("用户改过的词", entry.valueOf(entry.fields.single()))
    }
}
