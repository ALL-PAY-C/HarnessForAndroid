package dev.dsh.mobile.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DiagramKinds 的归一表 + 路由优先级。
 *
 * 这里只测纯字符串逻辑，不碰 Compose：卡片长什么样由 UI 测试和真机负责，
 * 而「哪种围栏交给谁」是纯函数，值得每一步都钉住。
 */
class DiagramKindsTest {

    @Test
    fun everyAliasMapsToItsKind() {
        // 一行一个别名，右侧是发给插件的 kind。写全是为了防止以后加别名时漏改。
        val table = listOf(
            "svg" to "svg",
            "mermaid" to "mermaid",
            "mmd" to "mermaid",
            "dot" to "graphviz",
            "graphviz" to "graphviz",
            "gv" to "graphviz",
            "plantuml" to "plantuml",
            "puml" to "plantuml",
            "html" to "html",
            "htm" to "html",
            "xhtml" to "html",
            "echarts" to "echarts",
            "echarts-json" to "echarts",
            "chart" to "chartjs",
            "chartjs" to "chartjs",
            "chart.js" to "chartjs",
            "tikz" to "tikz",
            "tex-tikz" to "tikz",
            "python" to "python",
            "py" to "python",
            "python3" to "python",
            "matplotlib" to "python",
            "ascii" to "ascii",
            "asciiart" to "ascii",
            "ascii-art" to "ascii",
            "diagram-text" to "ascii",
        )
        for ((lang, kind) in table) {
            assertEquals("lang=$lang 应归一到 $kind", kind, DiagramKinds.of(lang))
        }
    }

    @Test
    fun caseAndSurroundingWhitespaceAreIgnored() {
        assertEquals("mermaid", DiagramKinds.of("MERMAID"))
        assertEquals("graphviz", DiagramKinds.of("  DoT  "))
        assertEquals("chartjs", DiagramKinds.of("Chart.JS"))
        assertEquals("python", DiagramKinds.of("Python3"))
        assertEquals("ascii", DiagramKinds.of("\tASCII-Art\n"))
    }

    @Test
    fun leadingDotVariantIsAccepted() {
        // Markdown 里常见 ``` .mermaid 这种写法：语言名前面多一个点。
        assertEquals("mermaid", DiagramKinds.of(" .mermaid "))
        assertEquals("graphviz", DiagramKinds.of(".dot"))
        assertEquals("chartjs", DiagramKinds.of(" .chart.js "))
    }

    @Test
    fun blankLangReturnsNull() {
        assertNull(DiagramKinds.of(""))
        assertNull(DiagramKinds.of("   "))
        // 只剩一个点：归一后是空串，按「不是图形围栏」处理。
        assertNull(DiagramKinds.of("."))
    }

    @Test
    fun ordinaryLanguagesReturnNull() {
        for (lang in listOf("kotlin", "js", "bash", "json", "xml", "diff", "text")) {
            assertNull("lang=$lang 不该被当成图形围栏", DiagramKinds.of(lang))
        }
    }

    @Test
    fun htmlKeepsTheHtmlChannel() {
        // HTML_LANGS 优先于 DiagramKinds：Router 里先判 isHtmlFenceLang 再判 kind，
        // 所以 html/htm/xhtml 仍然走 HtmlBlockRenderers（插件 WebView 通道）。
        assertTrue(isHtmlFenceLang("html"))
        assertTrue(isHtmlFenceLang("HTM"))
        assertTrue(isHtmlFenceLang("xhtml"))
        // svg/xml 本来就在 HTML_LANGS 里，抢过来会改变既有渲染结果，所以也保持优先。
        assertTrue(isHtmlFenceLang("svg"))
        assertTrue(isHtmlFenceLang("xml"))
        // 归一表里确实认 html（插件侧 kind 需要它），但入口优先级在 HTML 那边。
        assertEquals("html", DiagramKinds.of("html"))
        assertEquals("svg", DiagramKinds.of("svg"))
        // 解析器不会把 ```html 变成图形围栏，仍是 CodeBlock，交由上面那层判优先级。
        val blocks = MarkdownParser.parseBlocks("```html\n<b>x</b>\n```", false)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.CodeBlock)
        assertEquals("html", (blocks[0] as MdBlock.CodeBlock).lang)
    }

    @Test
    fun chineseNotesDoNotChangeTheKind() {
        // 语言标签带中文说明（```mermaid 流程图）不是合法别名，按未知处理，
        // 免得把「看不懂的标注」硬塞给插件。注释里提到 mermaid 也不影响判定。
        assertNull(DiagramKinds.of("mermaid 流程图"))
        assertNull(DiagramKinds.of("流程图"))
        assertFalse(isHtmlFenceLang("html 页面"))

        val md = "```kotlin\n// 这里提到 mermaid 和 dot，但语言是 kotlin\nval x = 1\n```"
        val block = MarkdownParser.parseBlocks(md, false)[0] as MdBlock.CodeBlock
        assertEquals("kotlin", block.lang)
        assertNull("正文里提到图形名不该改变路由", DiagramKinds.of(block.lang))
    }
}
