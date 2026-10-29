package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginapi.PluginUiNode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面端插件写法的兼容性：大小写/连字符/下划线归一，以及 markdown/html/code 三类富内容节点。
 * 断言全部落在 [PluginUiJson] 的产出类型上，不碰 Compose。
 */
class PluginUiJsonDesktopTest {

    private val desktop = JSONObject(
        """
        {
          "type": "column",
          "children": [
            { "type": "IconButton", "icon": "star", "label": "收藏" },
            { "type": "hstack", "children": [
              { "type": "text", "text": "A" },
              { "type": "text", "text": "B" }
            ] },
            { "type": "icon_button", "icon": "add", "label": "新增" },
            { "type": "Markdown", "text": "# 标题\n正文 **加粗**" },
            { "type": "html", "html": "<b>hi</b>" },
            { "type": "code", "text": "val a = 1", "lang": "kotlin" },
            { "type": "reactComponent", "props": { "foo": 1 } }
          ]
        }
        """.trimIndent()
    )

    @Test
    fun desktopSpellingsMapToKnownNodes() {
        val root = PluginUiJson.node(desktop)
        assertNotNull("column 必须翻译成 Column", root)
        root as PluginUiNode.Column
        assertEquals(7, root.children.size)

        val camel = root.children[0] as PluginUiNode.IconButton
        assertEquals("star", camel.icon)
        assertEquals("收藏", camel.label)

        val row = root.children[1] as PluginUiNode.Row
        assertEquals(2, row.children.size)
        assertTrue(row.children[0] is PluginUiNode.Text)

        assertTrue("icon_button 应该和 IconButton 等价", root.children[2] is PluginUiNode.IconButton)
    }

    @Test
    fun richContentNodesKeepTheirType() {
        val root = PluginUiJson.node(desktop) as PluginUiNode.Column

        val markdown = root.children[3] as PluginUiNode.Markdown
        assertTrue(markdown.text.contains("# 标题"))

        val html = root.children[4] as PluginUiNode.Html
        assertEquals("<b>hi</b>", html.html)

        val code = root.children[5] as PluginUiNode.Code
        assertEquals("val a = 1", code.text)
        assertEquals("kotlin", code.lang)
    }

    @Test
    fun unknownTypeDegradesToTextWithoutThrowing() {
        val root = PluginUiJson.node(desktop) as PluginUiNode.Column
        val fallback = root.children[6]
        assertTrue("未知类型要降级成一行文本，不是 null、不是异常", fallback is PluginUiNode.Text)
        assertTrue((fallback as PluginUiNode.Text).text.isNotBlank())

        assertNotNull(PluginUiJson.node(JSONObject()))
        assertNotNull(PluginUiJson.node(JSONObject("""{"children":[{"type":"unknown","label":"x"}]}""")))
    }

    @Test
    fun specWrapsDesktopTree() {
        val raw = JSONObject().put("width", "fill").put("scrollable", true).put("root", desktop)
        val spec = PluginUiJson.spec(raw)
        assertNotNull(spec)
        assertTrue(spec!!.scrollable)
    }
}
