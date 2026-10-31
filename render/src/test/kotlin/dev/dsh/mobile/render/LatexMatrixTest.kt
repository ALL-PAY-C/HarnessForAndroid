package dev.dsh.mobile.render

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 矩阵环境 (\begin{pmatrix} 等) 与未知环境兜底的排版测试。
 *
 * JVM 单测没有 Android 字体与 Canvas, 所以不碰 TextMeasurer: 给 LatexLayout 注入假测量器
 * (宽 = 字数 * 0.5em, 高 = 1em, 基线 = 0.8em), 只验证 tokenize/parse/layout 的结构与几何自洽。
 */
class LatexMatrixTest {

    /** 记录每一次文字测量请求, 用来断言"源码有没有被吞"。 */
    private class Recorder {
        val texts = ArrayList<String>()

        fun layout(display: Boolean = false, base: Float = 16f): LatexLayout =
            LatexLayout(Color.Black, base, display) { text, _, sizePx ->
                texts.add(text)
                if (text.isEmpty() || sizePx <= 0f) MathBox(0f, 0f, 0f) { _, _ -> }
                else MathBox(text.length * sizePx * 0.5f, sizePx, sizePx * 0.8f) { _, _ -> }
            }
    }

    @Test
    fun pmatrix2x2LaysOut() {
        val rec = Recorder()
        val box = rec.layout().layout("""\begin{pmatrix} a & b \\ c & d \end{pmatrix}""")
        assertTrue("宽度应大于 0", box.width > 0f)
        assertTrue("高度应大于 0", box.height > 0f)
        assertTrue("基线落在盒内", box.baseline >= 0f && box.baseline <= box.height)
        assertTrue("四个单元格都排版了: " + rec.texts, rec.texts.containsAll(listOf("a", "b", "c", "d")))
        assertTrue("pmatrix 带圆括号: " + rec.texts, rec.texts.contains("(") && rec.texts.contains(")"))
        assertTrue("矩阵体里不该出现环境名", rec.texts.none { it.contains("pmatrix") })
    }

    @Test
    fun plainMatrixHasNoBrackets() {
        val bare = Recorder()
        val bareBox = bare.layout().layout("""\begin{matrix} a & b \\ c & d \end{matrix}""")
        val paren = Recorder()
        val parenBox = paren.layout().layout("""\begin{pmatrix} a & b \\ c & d \end{pmatrix}""")
        assertTrue(bareBox.width > 0f)
        assertTrue("matrix 无括号: " + bare.texts, bare.texts.none { it == "(" || it == ")" })
        assertTrue("pmatrix 应比 matrix 宽", parenBox.width > bareBox.width)
    }

    @Test
    fun everyBracketEnvUsesItsOwnDelimiters() {
        val cases = listOf(
            "bmatrix" to listOf("[", "]"),
            "vmatrix" to listOf("|", "|"),
            "Bmatrix" to listOf("{", "}"),
        )
        for ((env, want) in cases) {
            val rec = Recorder()
            val box = rec.layout().layout("\\begin{" + env + "} a \\end{" + env + "}")
            assertTrue(env + " 应排出版面", box.width > 0f && box.height > 0f)
            assertTrue(env + " 括号应为 " + want + ", 实际 " + rec.texts, rec.texts.containsAll(want))
        }
    }

    @Test
    fun rowsStackedSoTwoRowsAreTaller() {
        val one = Recorder().layout().layout("""\begin{matrix} a & b \end{matrix}""")
        val two = Recorder().layout().layout("""\begin{matrix} a & b \\ c & d \end{matrix}""")
        assertTrue("两行应明显比一行高: " + two.height + " vs " + one.height, two.height > 1.5f * one.height)
    }

    @Test
    fun unknownEnvironmentKeepsSourceVerbatim() {
        val rec = Recorder()
        val src = """\begin{cases} x & y \end{cases}"""
        rec.layout().layout(src)
        assertTrue("原文应整段保留(含反斜杠): " + rec.texts, rec.texts.contains(src))
        assertTrue(
            "不能退化成吞掉反斜杠的文本",
            rec.texts.none { it == "begin{cases} x  y end{cases}" || it == "begincases" },
        )
    }

    @Test
    fun unclosedUnknownEnvironmentStillKeepsBackslash() {
        val rec = Recorder()
        rec.layout().layout("""a \begin{foo} b""")
        assertTrue("未闭合也要保住反斜杠: " + rec.texts, rec.texts.any { it.contains("""\begin{foo}""") })
    }

    @Test
    fun inlineDollarAndDisplayDollarBothReachLayout() {
        val body = """\begin{pmatrix}1&0\\0&1\end{pmatrix}"""

        // 行内入口 $...$
        val inline = MarkdownParser.parseInline("公式 \$" + body + "\$ 结束").filterIsInstance<MdInline.Math>()
        assertEquals("行内应解析出一个公式", 1, inline.size)
        assertEquals(body, inline[0].latex)
        val inlineBox = Recorder().layout(display = false).layout(inline[0].latex)
        assertTrue("行内公式应有版面", inlineBox.width > 0f && inlineBox.height > 0f)

        // 块级入口 $$ ... $$
        val blocks = MarkdownParser.parseBlocks("\$\$\n" + body + "\n\$\$")
        val math = blocks.filterIsInstance<MdBlock.MathBlock>()
        assertEquals("块级应解析出一个公式块", 1, math.size)
        assertEquals(body, math[0].latex)
        val blockBox = Recorder().layout(display = true, base = 16f * 1.15f).layout(math[0].latex)
        assertTrue("块级公式应有版面", blockBox.width > 0f && blockBox.height > 0f)
        assertTrue("display 字号放大后更高", blockBox.height > inlineBox.height)
    }
}
