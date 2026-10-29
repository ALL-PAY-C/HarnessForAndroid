package dev.dsh.mobile.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数学定界符边界：\$ … \$、\$\$ … \$\$、\\( … \\)、\\[ … \\]，加 \\\$ 转义与货币写法。
 * 每条用例对应模型的一种真实写法；先跑红/绿，再决定改不改实现。
 */
class MathDelimiterTest {

    private fun inlineMath(text: String): List<String> {
        val out = ArrayList<String>()
        fun walk(spans: List<MdInline>) {
            for (s in spans) when (s) {
                is MdInline.Math -> out.add(s.latex)
                is MdInline.Bold -> walk(s.children)
                is MdInline.Italic -> walk(s.children)
                is MdInline.Strike -> walk(s.children)
                else -> Unit
            }
        }
        walk(MarkdownParser.parseInline(text))
        return out
    }

    private fun spanText(spans: List<MdInline>): String = spans.joinToString("") { s ->
        when (s) {
            is MdInline.Text -> s.text
            is MdInline.Code -> "`" + s.text + "`"
            is MdInline.Link -> s.label
            is MdInline.Math -> "«" + s.latex + "»"
            is MdInline.Bold -> spanText(s.children)
            is MdInline.Italic -> spanText(s.children)
            is MdInline.Strike -> spanText(s.children)
        }
    }

    private fun inlineText(text: String): String = spanText(MarkdownParser.parseInline(text))

    private fun mathBlocks(md: String): List<String> =
        MarkdownParser.parseBlocks(md, false).filterIsInstance<MdBlock.MathBlock>().map { it.latex }

    private fun kinds(md: String): List<String> =
        MarkdownParser.parseBlocks(md, false).map { it::class.simpleName.orEmpty() }

    // ------------------------------------------------------------ 行内 \$

    @Test
    fun inlineDollarDelimiter() {
        val md = "质能 \$E=mc^2\$ 在此"
        assertEquals(listOf("E=mc^2"), inlineMath(md))
        assertEquals("质能 «E=mc^2» 在此", inlineText(md))
    }

    @Test
    fun inlineBackslashParenDelimiter() {
        val md = "当 \\(x+y\\) 成立时"
        assertEquals("\\( \\) 行内定界要出公式，实际 " + inlineMath(md), listOf("x+y"), inlineMath(md))
        assertEquals("当 «x+y» 成立时", inlineText(md))
    }

    @Test
    fun inlineBackslashBracketDelimiter() {
        val md = "由 \\[E=mc^2\\] 可知"
        assertEquals("\\[ \\] 行内定界要出公式，实际 " + inlineMath(md), listOf("E=mc^2"), inlineMath(md))
    }

    @Test
    fun escapedDollarStaysLiteral() {
        val md = "价格 \\\$5 起"
        assertEquals(emptyList<String>(), inlineMath(md))
        assertEquals("价格 \$5 起", inlineText(md))
    }

    @Test
    fun unclosedDollarIsLiteral() {
        assertEquals(emptyList<String>(), inlineMath("成本 \$100 左右"))
    }

    @Test
    fun dollarInsideCodeSpanIsLiteral() {
        assertEquals(emptyList<String>(), inlineMath("写法 `\$x\$` 即可"))
    }

    // ------------------------------------------------------------ 货币不是公式

    @Test
    fun currencyIsNotMath() {
        assertEquals("\$5 不是公式", emptyList<String>(), inlineMath("\$5"))
        assertEquals("\$1,200.50 不是公式", emptyList<String>(), inlineMath("\$1,200.50"))
        assertEquals("5\$ 不是公式", emptyList<String>(), inlineMath("共 5\$ 运费"))
        assertEquals("预算区间不是公式", emptyList<String>(), inlineMath("预算 \$5 到 10\$ 之间"))
        assertEquals("\$5 与 5\$ 不是公式", emptyList<String>(), inlineMath("\$5 与 5\$"))
    }

    @Test
    fun formulaStartingWithDigitStillMath() {
        assertEquals(listOf("2x+1"), inlineMath("\$2x+1\$"))
        assertEquals(listOf("100 + 200"), inlineMath("\$100 + 200\$"))
    }

    // ------------------------------------------------------------ 展示态

    @Test
    fun displayDollarOnOwnLines() {
        val md = "\$\$\n\\tau = 0.75 R_0 C\n\$\$"
        val bs = MarkdownParser.parseBlocks(md, false)
        assertEquals("实际 " + kinds(md), 1, bs.size)
        assertEquals(listOf("\\tau = 0.75 R_0 C"), bs.filterIsInstance<MdBlock.MathBlock>().map { it.latex })
    }

    @Test
    fun displayDollarInsideSentence() {
        val md = "结论：\$\$\\tau = 1\$\$ 成立"
        assertEquals(listOf("Paragraph", "MathBlock", "Paragraph"), kinds(md))
        assertEquals(listOf("\\tau = 1"), mathBlocks(md))
    }

    @Test
    fun displayDollarOpenLineWithContent() {
        val md = "\$\$x = 1\ny = 2\n\$\$"
        assertEquals("实际 " + kinds(md), 1, kinds(md).size)
        assertEquals("跨行 \$\$ 公式：{x = 1; y = 2} 要收成一个 MathBlock", listOf("x = 1\ny = 2"), mathBlocks(md))
    }

    @Test
    fun displayBracketBlock() {
        assertEquals(listOf("E=mc^2"), mathBlocks("\\[ E=mc^2 \\]"))
        val multi = "\\[\na+b\n\\]"
        assertEquals(listOf("a+b"), mathBlocks(multi))
    }

    // ------------------------------------------------------------ 围栏优先

    @Test
    fun fenceSwallowsDollarNotMath() {
        val md = "```\n\$\$x\$\$\n\$y = 1\$ 变量\n```"
        val bs = MarkdownParser.parseBlocks(md, false)
        assertEquals("围栏里不许出公式，实际 " + kinds(md), 1, bs.size)
        val code = bs[0] as MdBlock.CodeBlock
        assertTrue("围栏源码要原样保留：" + code.code, code.code.contains("\$\$x\$\$"))
    }

    @Test
    fun latexFenceStaysCodeBlock() {
        val md = "```latex\n\\tau = 0.75 R_0 C\n```"
        val bs = MarkdownParser.parseBlocks(md, false)
        assertEquals("latex 围栏按代码块走（现状锁定），实际 " + kinds(md), 1, bs.size)
        val code = bs[0] as MdBlock.CodeBlock
        assertEquals("latex", code.lang)
        assertEquals("\\tau = 0.75 R_0 C", code.code)
    }
}
