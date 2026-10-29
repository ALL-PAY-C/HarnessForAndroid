package dev.dsh.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T4：26 万字的 HTML 表格不能被当成普通文本整段渲染，但原文必须完整保留。 */
class LargeContentTest {

    private val bigTable = buildString {
        append("<table><tr><th>列</th></tr>")
        while (length < 260_000) append("<tr><td>很长的表格单元内容</td></tr>")
        append("</table>")
    }

    @Test
    fun bigHtmlTableCollapsesInsteadOfInlineRender() {
        val shape = LargeContent.shape(bigTable)
        assertTrue("26 万字必须判定为大块内容", shape is ContentShape.Collapsed)
        shape as ContentShape.Collapsed
        assertEquals(bigTable.length, shape.totalChars)
        assertEquals(LargeContent.PREVIEW_MAX_CHARS, shape.previewChars)
        assertTrue("表格要标出来，折叠态提示「这是表格」", shape.markup)
    }

    @Test
    fun smallMessageStaysInline() {
        assertEquals(ContentShape.Inline, LargeContent.shape("普通的一句话"))
        assertEquals(ContentShape.Inline, LargeContent.shape("<html><body>短</body></html>"))
    }

    @Test
    fun thresholdBoundaryIsInclusive() {
        val atLimit = "a".repeat(LargeContent.RAW_RENDER_MAX_CHARS)
        assertEquals("刚好到阈值仍内联", ContentShape.Inline, LargeContent.shape(atLimit))
        assertTrue(LargeContent.shape(atLimit + "a") is ContentShape.Collapsed)
    }

    @Test
    fun plainLongTextCollapsesWithoutMarkupFlag() {
        val shape = LargeContent.shape("长".repeat(50_000))
        shape as ContentShape.Collapsed
        assertEquals("纯长文不是表格", false, shape.markup)
    }
}
