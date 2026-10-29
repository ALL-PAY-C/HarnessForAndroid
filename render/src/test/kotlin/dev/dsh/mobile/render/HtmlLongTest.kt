package dev.dsh.mobile.render

import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T4 返工：展开态必须拿到原文，不允许因为 HTML 超过 256k 就被截断成纯文本。
 * 之前 HtmlRenderer.toAnnotated 会在 htmlTooLong 时走 plainFallback（截断 + 提示），
 * 结果「展开全文」拿到的仍是残缺内容。
 */
class HtmlLongTest {

    private fun bigHtml(tailMarker: String): String = buildString {
        append("<html><body><table><tr><td>列A</td><td>列B</td></tr></table>")
        while (length < 260_000) append("<p>数据行 单元内容</p>")
        append("<p>")
        append(tailMarker)
        append("</p></body></html>")
    }

    @Test
    fun largeHtmlKeepsTailContent() {
        val tail = "TAIL-MARKER-END"
        val html = bigHtml(tail)
        assertTrue("构造的原文要超过 256k，实际 " + html.length, html.length > 256_000)

        val out = HtmlRenderer.toAnnotated(html, TextStyle.Default).text

        assertTrue("展开后必须能看到末尾内容，实际长度 " + out.length, out.contains(tail))
    }

    @Test
    fun largeHtmlIsNotTruncatedWithNotice() {
        val html = bigHtml("末尾标记")
        val out = HtmlRenderer.toAnnotated(html, TextStyle.Default).text

        assertFalse("展开后不能再出现截断提示", out.contains("已截断"))
    }

    @Test
    fun largeHtmlKeepsHeadAndTail() {
        val html = bigHtml("TAIL-X")
        val out = HtmlRenderer.toAnnotated(html, TextStyle.Default).text

        assertTrue("头部正文要在", out.contains("列A"))
        assertTrue("末尾正文要在", out.contains("TAIL-X"))
        val expected = Regex("数据行 单元内容").findAll(html).count()
        val actual = Regex("数据行 单元内容").findAll(out).count()
        assertTrue("段落一条都不能丢：原文 " + expected + "，渲染 " + actual, expected == actual)
    }

    /**
     * 原文末尾被截断的标签（如 `</tr` 没有 `>`）不是正文：
     * 渲染时不能把半截标签当文字铺出来，但真正文（哨兵）必须留着。
     */
    @Test
    fun truncatedTrailingTagIsNotRenderedAsText() {
        val html = "<table><tr><td>值</td></tr\n末行哨兵-T4-完"
        val out = HtmlRenderer.toAnnotated(html, TextStyle.Default).text

        assertTrue("末尾正文要在：" + out, out.contains("末行哨兵-T4-完"))
        assertFalse("半截标签不能当正文渲染：" + out, out.contains("</tr"))
        assertFalse("半截标签不能当正文渲染：" + out, out.contains("<tr"))
    }

    /** 正文里单独的 '<' 不是标签，不能被一起丢掉。 */
    @Test
    fun loneLessThanIsKept() {
        val out = HtmlRenderer.toAnnotated("1 < 2 且 a<b", TextStyle.Default).text
        assertTrue("纯文本的 '<' 要保留：" + out, out.contains("< 2"))
    }
}
