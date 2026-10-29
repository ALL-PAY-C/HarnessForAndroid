package dev.dsh.mobile.render

import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 非文档级 HTML 片段（没有 <!DOCTYPE / <html）的识别与降落：
 * 片段必须进 HtmlBlock 而不是正文纯文本，script/style 正文不许显示，br 与实体要解码，
 * 折叠预览（stripTags）与展开渲染（HtmlRenderer）两条路的结论要一致。
 */
class HtmlFragmentTest {

    private fun blocks(md: String) = MarkdownParser.parseBlocks(md, false)

    private fun kinds(md: String) = blocks(md).map { it::class.simpleName }

    private fun renderHtml(raw: String) = HtmlRenderer.toAnnotated(raw, TextStyle.Default).text

    private fun spanText(spans: List<MdInline>): String = spans.joinToString("") { s ->
        when (s) {
            is MdInline.Text -> s.text
            is MdInline.Code -> s.text
            is MdInline.Link -> s.label
            is MdInline.Math -> s.latex
            is MdInline.Bold -> spanText(s.children)
            is MdInline.Italic -> spanText(s.children)
            is MdInline.Strike -> spanText(s.children)
        }
    }

    /** 整条消息实际会显示成什么：HtmlBlock 交给 HtmlRenderer，其余取纯文字。 */
    private fun shown(md: String): String = blocks(md).joinToString("\n") { b ->
        when (b) {
            is MdBlock.HtmlBlock -> renderHtml(b.raw)
            is MdBlock.Paragraph -> spanText(b.spans)
            is MdBlock.CodeBlock -> b.code
            else -> ""
        }
    }

    // ------------------------------------------------------------ 片段识别

    @Test
    fun divFragmentBecomesHtmlBlock() {
        val md = "<div class=\"card\"><p>卡片正文</p></div>"
        assertTrue("非文档级 <div> 片段要走 HtmlBlock，实际 " + kinds(md), blocks(md)[0] is MdBlock.HtmlBlock)
        val out = shown(md)
        assertTrue("正文要在：" + out, out.contains("卡片正文"))
        assertFalse("不能把标签铺给用户：" + out, out.contains("<div"))
    }

    @Test
    fun paragraphFragmentBecomesHtmlBlock() {
        val md = "<p>第一段</p>"
        assertTrue("行首 <p> 片段要走 HtmlBlock，实际 " + kinds(md), blocks(md)[0] is MdBlock.HtmlBlock)
        assertTrue(shown(md).contains("第一段"))
    }

    @Test
    fun tableFragmentKeepsCellsApart() {
        val md = "<table><tr><td>列A</td><td>列B</td></tr></table>"
        val out = shown(md)
        assertTrue("单元格文字要在：" + out, out.contains("列A") && out.contains("列B"))
        assertFalse("相邻单元格不能粘成一串：" + out, out.contains("列A列B"))
    }

    // ------------------------------------------------------------ script / style

    @Test
    fun scriptInsideFragmentIsDropped() {
        val md = "<div><p>正文A</p><script>alert('xss')</script></div>"
        val out = shown(md)
        assertTrue("正文A 要在：" + out, out.contains("正文A"))
        assertFalse("script 内容不能当正文：" + out, out.contains("alert"))
        assertFalse("script 标签不能露出：" + out, out.contains("<script"))
    }

    @Test
    fun standaloneScriptBlockIsDropped() {
        val md = "说明如下：\n<script>\nvar a = 1;\n</script>\n结束"
        val out = shown(md)
        assertFalse("单独成行的 script 不能铺成正文：" + out, out.contains("var a = 1"))
        assertFalse("script 标签不能露出：" + out, out.contains("<script"))
        assertTrue("正文要在：" + out, out.contains("说明如下"))
        assertTrue("正文要在：" + out, out.contains("结束"))
    }

    @Test
    fun styleBlockIsDropped() {
        val md = "<style>\n.c { color: red }\n</style>\n正文"
        val out = shown(md)
        assertFalse("style 内容不能当正文：" + out, out.contains("color: red"))
        assertTrue("正文要在：" + out, out.contains("正文"))
    }

    // ------------------------------------------------------------ 行内标签 / 实体

    @Test
    fun brAndEntitiesAreDecoded() {
        val out = renderHtml("<p>第一行<br>第二行&nbsp;&amp;&lt;结束</p>")
        assertTrue("br 要断行：" + out, out.contains("第一行\n第二行"))
        assertTrue("&nbsp; 要解码成不换行空格：" + out, out.contains('\u00a0'))
        assertTrue("&amp; 要解码成 &：" + out, out.contains("&"))
        assertTrue("&lt; 要解码成 <：" + out, out.contains("<"))
        assertFalse("不能露实体源码：" + out, out.contains("&amp;"))
    }

    @Test
    fun coldBlockTagsDoNotLeak() {
        val md = "<figure>\n<img src=\"x.png\">\n<figcaption>图 1</figcaption>\n</figure>"
        val out = shown(md)
        assertFalse("冷门块级标签不能原样铺给用户：" + out, out.contains("<figcaption"))
        assertFalse("冷门块级标签不能原样铺给用户：" + out, out.contains("<figure"))
        assertTrue("图注文字要在：" + out, out.contains("图 1"))
    }

    // ------------------------------------------------------------ 折叠 / 展开一致

    @Test
    fun multiLineFragmentKeepsAllText() {
        val md = "<div class=\"c\">\n<p>第一段</p>\n<p>第二段</p>\n</div>"
        val out = shown(md)
        assertTrue("第一段要在：" + out, out.contains("第一段"))
        assertTrue("第二段要在：" + out, out.contains("第二段"))
        assertFalse("不能露出标签：" + out, out.contains("<p>"))
        assertFalse("不能露出标签：" + out, out.contains("<div"))
    }

    @Test
    fun collapsePreviewAgreesWithExpandedRender() {
        val unit = "<p>正文 &amp; 说明</p><script>var a = 1</script>"
        val md = "<div>" + unit.repeat(600) + "</div>"
        assertTrue("原文要超过折叠阈值", md.length > LONG_MESSAGE_COLLAPSE_CHARS)
        val plan = collapsePlan(md)
        assertTrue("大块 HTML 必须进折叠计划", plan != null)
        val preview = md.take(plan!!.previewChars)

        assertTrue("折叠态要认得出这是 HTML", looksLikeHtml(preview))
        val folded = stripTags(preview)
        val expanded = shown(md)

        assertFalse("折叠预览不能露 script：" + folded.take(80), folded.contains("var a = 1"))
        assertFalse("展开渲染不能露 script：" + expanded.take(80), expanded.contains("var a = 1"))
        assertFalse("折叠预览不能露实体源码：" + folded.take(80), folded.contains("&amp;"))
        assertFalse("展开渲染不能露实体源码：" + expanded.take(80), expanded.contains("&amp;"))
        assertTrue("折叠预览要有正文：" + folded.take(80), folded.contains("正文"))
        assertTrue("展开渲染要有正文：" + expanded.take(80), expanded.contains("正文"))
    }
}
