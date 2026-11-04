package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginapi.SlotAction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 声明式 ui 里的「写配置」动作：以前 PluginUiJson 认不出 setconfig，
 * 于是插件面板里那些开关点了毫无反应（plugin.json 的 contributions 认，ui 子树不认）。
 */
class PluginUiJsonConfigActionTest {

    private fun action(json: String): SlotAction? = PluginUiJson.action(JSONObject(json))

    @Test
    fun setConfigCarriesKeyAndValue() {
        val a = action("""{ "kind": "setconfig", "key": "mode", "value": "safe" }""")
        assertTrue(a is SlotAction.SetConfig)
        a as SlotAction.SetConfig
        assertEquals("mode", a.key)
        assertEquals("safe", a.value)
        assertEquals(false, a.toggle)
        assertEquals("pluginId 解析期留空，装配时补", "", a.pluginId)
    }

    @Test
    fun setConfigSnakeCaseAndTypeAlias() {
        val snake = action("""{ "kind": "set_config", "key": "a", "value": "1" }""")
        val viaType = action("""{ "type": "config", "key": "b", "value": "2" }""")
        assertEquals("a", (snake as SlotAction.SetConfig).key)
        assertEquals("b", (viaType as SlotAction.SetConfig).key)
    }

    @Test
    fun flipIsToggleWithoutValue() {
        val a = action("""{ "kind": "flip", "key": "enabled" }""") as SlotAction.SetConfig
        assertTrue(a.toggle)
        assertEquals("", a.value)
    }
}
