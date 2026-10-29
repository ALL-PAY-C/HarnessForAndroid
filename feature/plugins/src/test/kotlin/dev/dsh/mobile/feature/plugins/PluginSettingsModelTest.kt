package dev.dsh.mobile.feature.plugins

import dev.dsh.mobile.pluginapi.ConfigFieldType
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.pluginapi.SlotId
import dev.dsh.mobile.pluginhost.ConfigSpec
import dev.dsh.mobile.pluginhost.PluginManifestFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSettingsModelTest {

    private fun manifest(
        id: String,
        name: String = id,
        description: String = "",
        homepage: String = "",
        config: List<ConfigSpec> = emptyList(),
    ) = PluginManifestFile(
        id = id,
        name = name,
        version = "1.0.0",
        description = description,
        homepage = homepage,
        config = config,
    )

    private fun page(pluginId: String, subtitle: String = "") = SlotContribution(
        slot = SlotId.PluginPage,
        id = pluginId + ".page",
        label = pluginId,
        subtitle = subtitle,
        pluginId = pluginId,
    )

    @Test
    fun entriesAreSortedByName() {
        val entries = pluginSettingsEntries(
            installed = listOf(manifest("b", name = "B 插件"), manifest("a", name = "A 插件")),
            pages = emptyList(),
        )
        assertEquals(listOf("a", "b"), entries.map { it.pluginId })
    }

    @Test
    fun noSettingsUsesFixedText() {
        val entry = pluginSettingsEntries(listOf(manifest("x", description = "有说明但没设置")), emptyList()).single()
        assertFalse(entry.hasSettings)
        assertEquals(NO_SETTINGS_TEXT, entry.rowSubtitle)
        assertEquals(NO_SETTINGS_TEXT, entry.emptyText)
    }

    @Test
    fun configFieldsMeanSettingsExist() {
        val entry = pluginSettingsEntries(
            installed = listOf(
                manifest(
                    "x",
                    description = "网关设置",
                    config = listOf(ConfigSpec(key = "base", label = "网关地址")),
                ),
            ),
            pages = emptyList(),
        ).single()
        assertTrue(entry.hasSettings)
        assertNull(entry.emptyText)
        assertEquals("网关设置", entry.rowSubtitle)
        assertEquals(listOf("base"), entry.fields.map { it.key })
    }

    @Test
    fun subtitleFallsBackToFieldCount() {
        val entry = pluginSettingsEntries(
            installed = listOf(manifest("x", config = listOf(ConfigSpec("a", "A"), ConfigSpec("b", "B")))),
            pages = emptyList(),
        ).single()
        assertEquals("共 2 项设置", entry.rowSubtitle)
    }

    @Test
    fun pageIsTakenFromOwnPluginOnly() {
        val entries = pluginSettingsEntries(
            installed = listOf(manifest("x"), manifest("y")),
            pages = listOf(page("y"), page("x")),
        )
        assertEquals("y", entries.first { it.pluginId == "y" }.page?.pluginId)
        assertEquals("x", entries.first { it.pluginId == "x" }.page?.pluginId)
    }

    @Test
    fun failureSubtitleBecomesFailureText() {
        val entry = pluginSettingsEntries(
            installed = listOf(manifest("x")),
            pages = listOf(page("x", subtitle = "加载失败：entry")),
        ).single()
        assertEquals("加载失败：entry", entry.failureText)
    }

    @Test
    fun onlyHttpHomepageOffersBrowser() {
        val http = pluginSettingsEntries(listOf(manifest("x", homepage = "https://example.com")), emptyList()).single()
        val file = pluginSettingsEntries(listOf(manifest("y", homepage = "file:///sdcard/a.html")), emptyList()).single()
        assertEquals("https://example.com", http.webUrl)
        assertNull(file.webUrl)
    }

    @Test
    fun savedValueWinsOverDefault() {
        val entry = pluginSettingsEntries(
            installed = listOf(manifest("x", config = listOf(ConfigSpec("k", "K", default = "dflt")))),
            pages = emptyList(),
            values = { mapOf("k" to "saved") },
        ).single()
        assertEquals("saved", entry.valueOf(entry.fields.single()))
        assertEquals("saved", entry.values["k"])
    }

    @Test
    fun unknownTypeFallsBackToText() {
        assertEquals(ConfigFieldType.Switch, fieldTypeOf("switch"))
        assertEquals(ConfigFieldType.Secret, fieldTypeOf("Password"))
        assertEquals(ConfigFieldType.Number, fieldTypeOf("int"))
        assertEquals(ConfigFieldType.Select, fieldTypeOf("enum"))
        assertEquals(ConfigFieldType.Text, fieldTypeOf("markdown"))
    }

    @Test
    fun switchStringIsInterpreted() {
        assertTrue(switchOn("true"))
        assertTrue(switchOn("1"))
        assertFalse(switchOn(""))
        assertFalse(switchOn("false"))
    }
}
