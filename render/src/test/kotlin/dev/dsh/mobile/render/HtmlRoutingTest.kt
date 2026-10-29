package dev.dsh.mobile.render

import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T4 返工：整份 HTML 文档（以 <!DOCTYPE / <html 开头、外面没有围栏）必须走 HtmlBlock，
 * 由 HtmlRenderer（或 HTML 插件）真渲染成可读正文。
 *
 * 设备实测过的问题：262k 字 HTML 消息展开后是原始标签逐行铺出
 * （`<!DOCTYPE html><html>…<tr><td class="c">col</td>`），
 * 因为行式解析的 HTML_OPEN 只认行首 <字母，<!DOCTYPE 让整段掉成 Paragraph。
 */
class HtmlRoutingTest {

    private fun bigDoc(tailMarker: String): String = buildString {
        append("<!DOCTYPE html><html><head><style>.c{color:red}</style></head><body>")
        append("<h1>压力测试文档</h1><table><tr><td>列A</td><td>列B</td></tr></table>")
        while (length < 260_000) append("<tr><td class=\"c\">col</td><td>数据</td></tr>")
        append("<p>")
        append(tailMarker)
        append("</p></body></html>")
    }

    @Test
    fun doctypeDocumentBecomesHtmlBlock() {
        val html = "<!DOCTYPE html><html><body><h1>标题</h1><p>正文</p></body></html>"
        val blocks = MarkdownParser.parseBlocks(html, false)

        assertEquals("整份文档应该是一个 HtmlBlock，实际 " + blocks.map { it::class.simpleName }, 1, blocks.size)
        assertTrue("必须是 HtmlBlock", blocks[0] is MdBlock.HtmlBlock)
    }

    @Test
    fun largeDocumentExpandedShowsReadableTextAndTail() {
        val tail = "TAIL-MARKER-END"
        val html = bigDoc(tail)
        assertTrue("构造的原文要超过 256k，实际 " + html.length, html.length > 256_000)

        val blocks = MarkdownParser.parseBlocks(html, false)
        assertEquals("整份文档应该是一个 HtmlBlock，实际 " + blocks.map { it::class.simpleName }, 1, blocks.size)
        val block = blocks[0] as MdBlock.HtmlBlock
        assertTrue("HtmlBlock 必须带原文（含末行）", block.raw.contains(tail))

        val out = HtmlRenderer.toAnnotated(block.raw, TextStyle.Default).text
        assertFalse("展开后不能再看到 HTML 标签：<td", out.contains("<td"))
        assertFalse("展开后不能再看到 HTML 标签：<table", out.contains("<table"))
        assertTrue("正文要可读（含列A）", out.contains("列A"))
        assertTrue("末行内容要在（" + tail + "）", out.contains(tail))
    }

    @Test
    fun fencedHtmlStaysCodeBlock() {
        val md = "```html\n<!DOCTYPE html><html><body><p>围栏内容</p></body></html>\n```"
        val blocks = MarkdownParser.parseBlocks(md, false)

        assertEquals("围栏 html 仍然是一个代码块，实际 " + blocks.map { it::class.simpleName }, 1, blocks.size)
        val code = blocks[0] as MdBlock.CodeBlock
        assertEquals("html", code.lang)
        assertTrue("围栏源码要保留", code.code.contains("围栏内容"))
    }

    @Test
    fun inlineHtmlLineStillBecomesHtmlBlock() {
        val blocks = MarkdownParser.parseBlocks("<div>短片段</div>", false)

        assertTrue("行首标签的老路径不能坏", blocks[0] is MdBlock.HtmlBlock)
    }
}
