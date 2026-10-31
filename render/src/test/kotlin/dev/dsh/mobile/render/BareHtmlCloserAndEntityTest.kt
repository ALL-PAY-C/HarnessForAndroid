package dev.dsh.mobile.render

import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 裸 HTML 块（无围栏）的两个真机缺陷：
 * 1) 收尾闭标签 </section> 会原样留在正文里；
 * 2) 正文里的字符实体（&nbsp; 等）不解码。
 */
class BareHtmlCloserAndEntityTest {

    private fun blocks(md: String) = MarkdownParser.parseBlocks(md, false)

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

    private fun shown(md: String): String = blocks(md).joinToString("\n") { b ->
        when (b) {
            is MdBlock.HtmlBlock -> HtmlRenderer.toAnnotated(b.raw, TextStyle.Default).text
            is MdBlock.Paragraph -> spanText(b.spans)
            is MdBlock.Heading -> spanText(b.spans)
            is MdBlock.Quote -> spanText(b.spans)
            is MdBlock.BulletList -> b.items.joinToString(" ") { spanText(it) }
            is MdBlock.CodeBlock -> b.code
            else -> ""
        }
    }

    /** 块原文（HtmlBlock 的 raw 会进 WebView / 复制文本，孤立闭标签不能留在这里）。 */
    private fun blockRaws(md: String): String = blocks(md).joinToString("\n") { b ->
        if (b is MdBlock.HtmlBlock) b.raw else ""
    }

    private val sectionLines = listOf(
        "<section>",
        "<h1>裸 HTML 标题</h1>",
        "<p>这是一段正文</p>",
        "<ul><li>项一</li></ul>",
        "</section>",
    )

    // ------------------------------------------------------------ 孤立闭标签

    @Test
    fun orphanClosingTagIsDroppedFromBlockRaw() {
        val md = sectionLines.joinToString("\n")
        assertFalse("孤立闭标签不能留在块原文里：" + blockRaws(md), blockRaws(md).contains("</section>"))
        assertFalse("渲染结果不能露闭标签：" + shown(md), shown(md).contains("</section>"))
        assertFalse("渲染结果不能露任何标签：" + shown(md), shown(md).contains("<"))
    }

    @Test
    fun closingTagInItsOwnBlockDoesNotLeak() {
        // 开标签在上一块、闭标签自成一块：这就是真机上闭标签漏网的那种切法。
        val md = "<section>\n<h1>标题</h1>\n</section>"
        assertFalse(blockRaws(md).contains("</section>"))
        assertTrue("正文要在：" + shown(md), shown(md).contains("标题"))
    }

    @Test
    fun singleLineSectionFragmentStillRenders() {
        val md = "<section><h1>裸 HTML 标题</h1><p>这是一段正文</p><ul><li>项一</li></ul></section>"
        val out = shown(md)
        assertTrue("富文本要在：" + out, out.contains("裸 HTML 标题"))
        assertTrue("正文要在：" + out, out.contains("这是一段正文"))
        assertTrue("列表项要在：" + out, out.contains("项一"))
        assertFalse("标签不能铺给用户：" + out, out.contains("<section") || out.contains("</section>"))
    }

    @Test
    fun balancedClosingTagsAreKept() {
        // 配对正常的闭标签不能被误删（删了就会把 </div> 这类整段吞进上一块）。
        val stripped = HtmlRenderer.stripOrphanClosers("<div><p>正文</p></div>")
        assertEquals("<div><p>正文</p></div>", stripped)
    }

    @Test
    fun scriptBodyInsideFragmentIsNotRewritten() {
        val src = "<div><script>var s = \"</section>\";</script><p>正文</p></div>"
        val stripped = HtmlRenderer.stripOrphanClosers(src)
        assertTrue("脚本字符串里的伪标签不能被改：" + stripped, stripped.contains("</section>"))
        assertFalse("裸露的孤立闭标签要清掉：" + stripped, stripped.contains("</div></section>"))
    }

    // ------------------------------------------------------------ 字符实体

    @Test
    fun namedEntitiesDecodedInParagraph() {
        val md = "正文 &nbsp; &middot; &copy; &times; &plusmn; &mdash; &hellip; 结束"
        val out = shown(md)
        assertTrue("&nbsp; 要是 NBSP：" + out, out.contains('\u00a0'))
        assertTrue("&middot; 要是 ·：" + out, out.contains('\u00b7'))
        assertTrue("&copy; 要是 ©：" + out, out.contains('\u00a9'))
        assertTrue("&times; 要是 ×：" + out, out.contains('\u00d7'))
        assertTrue("&plusmn; 要是 ±：" + out, out.contains('\u00b1'))
        assertTrue("&mdash; 要是 —：" + out, out.contains('\u2014'))
        assertTrue("&hellip; 要是 …：" + out, out.contains('\u2026'))
        assertFalse("不能露实体源码：" + out, out.contains("&nbsp;") || out.contains("&hellip;"))
    }

    @Test
    fun numericEntitiesDecodedInParagraph() {
        val out = shown("字符 &#123; 与 &#x1F600; 结束")
        assertTrue("{ 要解出来：" + out, out.contains("{"))
        assertTrue("emoji 要解出来：" + out, out.contains("\uD83D\uDE00"))
        assertFalse("不能露数字实体源码：" + out, out.contains("&#123;"))
    }

    @Test
    fun inlineCodeKeepsEntitiesVerbatim() {
        val md = "代码 `&nbsp;&amp;` 与正文 &nbsp; 结束"
        val spans = blocks(md).filterIsInstance<MdBlock.Paragraph>().first().spans
        val codes = spans.filterIsInstance<MdInline.Code>().map { it.text }
        assertEquals("行内代码要原样保留实体", listOf("&nbsp;&amp;"), codes)
        val text = spanText(spans)
        assertTrue("正文那侧的实体要解码：" + text, text.contains('\u00a0'))
        assertTrue("正文里不能再留实体源码：" + text, !text.replace("&nbsp;&amp;", "").contains("&nbsp;"))
    }

    @Test
    fun decodeHappensOnceOnly() {
        // 只解一遍：&amp;nbsp; 解出来的是字面量 "&nbsp;"，不会被再解成 NBSP。
        assertEquals("&nbsp;", HtmlRenderer.decodeEntities("&amp;nbsp;"))
    }
}
