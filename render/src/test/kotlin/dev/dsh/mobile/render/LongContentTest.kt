package dev.dsh.mobile.render

import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T4 长内容分流自检：26 万字默认折叠、展开拿到原文全量（不再截断）；
 * HTML 渲染要剥掉标签，不能把 <table><tr><td> 当正文铺出去。
 */
class LongContentTest {

    private fun bigDoc(chars: Int): String =
        "<html><body><table><tr><td>单元</td></tr></table>" + "内容".repeat(chars / 2) + "</body></html>"

    @Test
    fun shortTextIsNotCollapsed() {
        assertNull(collapsePlan("短消息"))
        assertNull(collapsePlan("x".repeat(LONG_MESSAGE_COLLAPSE_CHARS)))
    }

    @Test
    fun bigMessageCollapsesToPreview() {
        val text = "x".repeat(260_000)
        val plan = collapsePlan(text)
        assertTrue("26 万字必须折叠", plan != null)
        assertEquals(260_000, plan!!.totalChars)
        assertEquals(LONG_MESSAGE_PREVIEW_CHARS, plan.previewChars)
        assertEquals(260_000 - LONG_MESSAGE_PREVIEW_CHARS, plan.hiddenChars)
    }

    @Test
    fun streamingIsNeverCollapsed() {
        assertNull(collapsePlan("x".repeat(260_000), streaming = true))
    }

    @Test
    fun previewCutsOnBlockBoundary() {
        val text = "a".repeat(3_000) + "\n\n" + "b".repeat(30_000)
        val plan = collapsePlan(text)!!
        assertEquals(3_000, plan.previewChars)
    }

    @Test
    fun largeHtmlRendersWithoutTags() {
        val html = bigDoc(300_000)
        val out = HtmlRenderer.toAnnotated(html, TextStyle.Default).text
        assertFalse("渲染结果不能留着 <table: " + out.take(80), out.contains("<table"))
        assertFalse("渲染结果不能留着 <td: " + out.take(80), out.contains("<td"))
        assertTrue("要保留正文", out.contains("内容"))
        assertTrue("展开态不能再截断", !out.contains("已截断"))
    }

    @Test
    fun stripTagsDropsStyleAndScriptBodies() {
        val out = stripTags("<style>p{color:red}</style><p>正文</p><script>var a=1</script>")
        assertEquals("正文", out)
    }

    @Test
    fun stripTagsDecodesNumericAndNamedEntities() {
        // 折叠预览也必须是可读文字：数字实体与命名实体都要解码。
        assertEquals("ٲ & <", stripTags("<td>&#1650;</td><td>&amp;</td><td>&lt;</td>"))
        assertEquals("值 ٲ", stripTags("<td>值 &#1650;</td>"))
    }

    @Test
    fun detectsHtmlSource() {
        assertTrue(looksLikeHtml("<table><tr><td>数据</td></tr></table>"))
        assertFalse(looksLikeHtml("普通文本，只有一点 <b> 标记"))
    }

    @Test
    fun shortCodeBlockIsNotHeightLimited() {
        assertFalse(codeNeedsScroll("fun main() {\n  println(1)\n}"))
    }

    @Test
    fun longCodeBlockIsHeightLimited() {
        val code = (1..CODE_BLOCK_SCROLL_LINES).joinToString("\n") { "line " + it }
        assertTrue(codeNeedsScroll(code))
    }

    @Test
    fun shortTableIsNotHeightLimited() {
        assertFalse(tableNeedsScroll(3))
        assertFalse(tableNeedsScroll(TABLE_SCROLL_ROWS))
        assertTrue(tableNeedsScroll(TABLE_SCROLL_ROWS + 1))
    }
}
