package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginapi.PluginUiNode
import dev.dsh.mobile.pluginapi.PluginUiSpec
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 三种包形态：带 dsh.ui、只有 HTML 面板、什么都没有。 */
class DesktopUiAdapterTest {

    private val roots = mutableListOf<File>()

    private fun pkg(name: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "dsh-deskui-" + name + "-" + System.nanoTime())
        dir.mkdirs()
        roots += dir
        return dir
    }

    @After
    fun cleanup() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun packageJsonDshUiBecomesSpec() {
        val dir = pkg("dshui")
        File(dir, "package.json").writeText(
            """
            { "name": "dsh-demo", "version": "1.0.0",
              "dsh": { "client": { "platform": "desktop" },
                       "ui": { "type": "column", "children": [
                         { "type": "text", "text": "from package.json" },
                         { "type": "markdown", "text": "# 面板" } ] } } }
            """.trimIndent()
        )
        val spec = DesktopUiAdapter.fromDirectory(dir)
        assertNotNull("dsh.ui 应该被翻译成 spec", spec)
        val root = spec!!.root as PluginUiNode.Column
        assertEquals(2, root.children.size)
        assertTrue(root.children[1] is PluginUiNode.Markdown)
    }

    @Test
    fun pluginJsonUiWinsOverPackageJson() {
        val dir = pkg("priority")
        File(dir, "plugin.json").writeText("""{"id":"a.b","ui":{"type":"text","text":"from-plugin-json"}}""")
        File(dir, "package.json").writeText("""{"dsh":{"ui":{"type":"text","text":"from-package-json"}}}""")
        val spec: PluginUiSpec? = DesktopUiAdapter.fromDirectory(dir)
        assertNotNull(spec)
        assertEquals("from-plugin-json", (spec!!.root as PluginUiNode.Text).text)
    }

    @Test
    fun htmlPanelBecomesHtmlNode() {
        val dir = pkg("html")
        File(dir, "panel.html").writeText("<html><body><h1>hello panel</h1></body></html>")
        val spec = DesktopUiAdapter.fromDirectory(dir)
        assertNotNull("有 panel.html 就应该给出 Html 节点", spec)
        val html = spec!!.root as PluginUiNode.Html
        assertTrue(html.html.contains("hello panel"))
    }

    @Test
    fun emptyPackageYieldsNull() {
        val dir = pkg("empty")
        assertNull("没有界面描述就不要伪造界面", DesktopUiAdapter.fromDirectory(dir))
        assertNull(DesktopUiAdapter.fromJson(""))
        assertNull(DesktopUiAdapter.fromJson("""{"name":"x"}"""))
    }

    @Test
    fun brokenJsonYieldsNullInsteadOfThrowing() {
        val dir = pkg("broken")
        File(dir, "plugin.json").writeText("{ this is not json")
        assertNull(DesktopUiAdapter.fromDirectory(dir))
    }
}
