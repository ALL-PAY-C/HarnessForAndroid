package dev.dsh.mobile.render

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T4 性能：巨型文本分块虚拟化的纯逻辑（不改内容、不丢样式）。 */
class VirtualTextTest {

    private fun doc(lines: Int, lineText: (Int) -> String = { "第${it}行 内容" }): AnnotatedString =
        AnnotatedString((0 until lines).joinToString("\n") { lineText(it) })

    @Test
    fun chunkOffsetsCoversWholeTextWithoutGaps() {
        val text = doc(10)
        val chunks = chunkOffsets(text, 3)
        assertEquals(4, chunks.size)
        assertEquals(0, chunks.first().first)
        assertEquals(text.length - 1, chunks.last().last)
        // 相邻块首尾相接，一个字都不丢。
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        // 拼回来等于原文。
        assertEquals(text.text, chunks.joinToString("") { text.text.substring(it.first, it.last + 1) })
    }

    @Test
    fun chunkBoundariesSitRightAfterNewline() {
        val text = doc(7)
        chunkOffsets(text, 2).dropLast(1).forEach { range ->
            assertEquals('\n', text[range.last])
        }
    }

    @Test
    fun chunksKeepSpanStyles() {
        val text = buildAnnotatedString {
            append("第一行\n第二行\n")
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            append("第三行加粗")
            pop()
        }
        val chunks = chunkOffsets(text, 1)
        val bold = chunks.map { text.subSequence(it.first, it.last + 1) }
            .first { it.text.contains("加粗") }
        assertTrue(bold.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertEquals("第三行加粗", bold.text)
    }

    @Test
    fun shortTextIsOneChunk() {
        val text = AnnotatedString("一行")
        assertEquals(1, chunkOffsets(text, TEXT_CHUNK_LINES).size)
        assertEquals(1, lineCount("一行"))
        assertEquals(3, lineCount("a\nb\nc"))
    }
}
