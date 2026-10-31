package dev.dsh.mobile.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * barePipeTable（无分隔行表格）的边界钉。
 *
 * 覆盖四类风险：正常正文被误吞、代码块/引用块里的 pipe、表格与前后块的切分、
 * 以及 isBlockStartAt 里新增那次调用的复杂度。带「已知取舍」注释的用例是刻意把
 * 当前行为钉住，防止以后再改时悄悄变形。
 */
class BarePipeTableBoundaryTest {

    private fun blocks(text: String) = MarkdownParser.parseBlocks(text)

    private fun tables(text: String) = blocks(text).filterIsInstance<MdBlock.Table>()

    private fun plain(spans: List<MdInline>): String = spans.joinToString("") {
        if (it is MdInline.Text) it.text else ""
    }

    // ---------------------------------------------------------------- 误判正文

    @Test
    fun proseWithPipesMidLineIsNotTable() {
        val text = "命令用 a | b 表示管道。\n第二行也有 c | d，但不是表格。"
        assertEquals(0, tables(text).size)
        assertEquals("两行应该合成一个段落: " + blocks(text), 1, blocks(text).size)
    }

    @Test
    fun singlePipePrefixedLineIsParagraph() {
        // 以 | 开头、列数也够，但只有一行 → 不是表格（rows >= 2 才收）
        val text = "| 这是正文 | 不是表格 |"
        assertEquals(0, tables(text).size)
        assertTrue(blocks(text).single() is MdBlock.Paragraph)
    }

    @Test
    fun pipeInsideFenceStaysCodeBlock() {
        val text = "```\n| a | b |\n| c | d |\n```"
        val b = blocks(text)
        assertEquals(0, b.filterIsInstance<MdBlock.Table>().size)
        assertTrue("围栏里的 pipe 必须留在代码块里: " + b, (b.single() as MdBlock.CodeBlock).code.contains("| a | b |"))
    }

    @Test
    fun pipeInsideQuoteStaysQuote() {
        val text = "> | a | b |\n> | c | d |"
        val b = blocks(text)
        assertEquals(0, b.filterIsInstance<MdBlock.Table>().size)
        assertTrue(b.single() is MdBlock.Quote)
    }

    // ---------------------------------------------------------------- 块边界

    @Test
    fun paragraphGluedTableSplitsAtTheTable() {
        val text = "以下是结果：\n| a | b |\n| 1 | 2 |\n收尾正文。"
        val b = blocks(text)
        assertEquals("段落 / 表 / 段落 三段: " + b, 3, b.size)
        assertEquals("以下是结果：", plain((b[0] as MdBlock.Paragraph).spans))
        val t = b[1] as MdBlock.Table
        assertEquals(listOf("a", "b"), t.headers)
        assertEquals(listOf(listOf("1", "2")), t.rows)
        assertEquals("收尾正文。", plain((b[2] as MdBlock.Paragraph).spans))
    }

    @Test
    fun bulletListThenBareTable() {
        val text = "- 第一项\n| a | b |\n| 1 | 2 |"
        val b = blocks(text)
        assertEquals("列表 / 表 两段: " + b, 2, b.size)
        assertTrue(b[0] is MdBlock.BulletList)
        assertEquals(listOf("a", "b"), (b[1] as MdBlock.Table).headers)
    }

    @Test
    fun bareTableThenFence() {
        val text = "| a | b |\n| 1 | 2 |\n```\nx\n```"
        val b = blocks(text)
        assertEquals("表 / 代码块 两段: " + b, 2, b.size)
        assertTrue(b[0] is MdBlock.Table)
        assertTrue(b[1] is MdBlock.CodeBlock)
    }

    @Test
    fun twoAdjacentTablesKeepTheirOwnShapes() {
        val text = "| a | b |\n| 1 | 2 |\n\n| c | d |\n|---|---|\n| 3 | 4 |"
        val t = tables(text)
        assertEquals(2, t.size)
        assertEquals(listOf(listOf("1", "2")), t[0].rows)
        assertEquals(listOf("c", "d"), t[1].headers)
        assertEquals(listOf(listOf("3", "4")), t[1].rows)
    }

    /**
     * 已知取舍：HTML 块里如果出现裸 pipe 行，HTML 块会在那行断掉、pipe 行变成表格。
     * 以前这些行是 HTML 块的原始文本；现在切成 HtmlBlock + Table。
     * 末尾的 </div> 在这一切法里没有配对开标签，会被当成孤立闭标签剥掉（不再多出一个
     * 只含 </div> 的 HtmlBlock——那条块原文会原样进 WebView 与复制文本）。
     * 只在「HTML 里手写 pipe 行」时可见，不属于正文渲染路径。
     */
    @Test
    fun htmlBlockSplitsAtBareTable() {
        val text = "<div>\n| a | b |\n| c | d |\n</div>"
        val b = blocks(text)
        assertEquals(2, b.size)
        assertTrue(b[0] is MdBlock.HtmlBlock)
        assertTrue(b[1] is MdBlock.Table)
    }

    // ------------------------------------------------- 没弄坏原来的 |---|---| 分支

    @Test
    fun separatorTableIsStillParsedByTheOldBranch() {
        val text = "| 项目 | 状态 |\n|:--|--:|\n| 构建 | 通过 |\n| 测试 | 通过 |"
        val t = tables(text)
        assertEquals(1, t.size)
        assertEquals(listOf("项目", "状态"), t[0].headers)
        assertEquals(listOf(listOf("构建", "通过"), listOf("测试", "通过")), t[0].rows)
        assertEquals("分隔行不能混进数据行: " + t[0].rows, 2, t[0].rows.size)
    }

    @Test
    fun noLeadingPipeSeparatorTableStillWorks() {
        val text = "项目 | 状态\n--- | ---\n构建 | 通过"
        val t = tables(text)
        assertEquals(1, t.size)
        assertEquals(listOf("项目", "状态"), t[0].headers)
        assertEquals(listOf(listOf("构建", "通过")), t[0].rows)
    }

    // ---------------------------------------------------------------- 已知取舍

    /** 已知取舍：bare 分支要求每行列数一致，模型表里有一行列数不对时整块退回正文。 */
    @Test
    fun raggedColumnsFallBackToParagraph() {
        val text = "| a | b |\n| 1 | 2 | 3 |"
        assertEquals(0, tables(text).size)
        assertEquals("列数不一致时两行都留在同一段正文里", 1, blocks(text).size)
    }

    /** 已知取舍：splitRow 不认 \\|，裸表里出现转义竖线会按朴素 split 分格。 */
    @Test
    fun escapedPipeSplitsNaively() {
        val text = "| a \\| b | c |\n| d \\| e | f |"
        val t = tables(text)
        assertEquals(1, t.size)
        assertEquals(listOf("a \\", "b", "c"), t[0].headers)
    }

    /** 已知取舍：数据行里恰好写成 |---|---| 时会被当成普通数据行（外观上多一行横线）。 */
    @Test
    fun separatorLookingRowInsideBareRunIsKeptAsRow() {
        val text = "| a | b |\n| 1 | 2 |\n|---|---|"
        val t = tables(text)
        assertEquals(1, t.size)
        assertEquals(2, t[0].rows.size)
        assertEquals(listOf("---", "---"), t[0].rows[1])
    }

    /** 流式半截行：最后一行还没写完时整块退回正文，写完后恢复成表（不会丢字符）。 */
    @Test
    fun streamingPartialRowFallsBackToParagraph() {
        val text = "| a | b |\n| 1 |"
        assertEquals(0, tables(text).size)
        assertEquals("半截行不能丢: " + blocks(text), 1, blocks(text).size)
    }

    // ---------------------------------------------------------------- 复杂度

    @Test
    fun twentyThousandBarePipeLinesParseLinearly() {
        val sb = StringBuilder("| c1 | c2 |\n")
        for (k in 1..19_999) sb.append("| r").append(k).append(" | v |\n")
        val text = sb.toString()

        val t0 = System.nanoTime()
        val b = blocks(text)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("[PERF] 20k 同宽 pipe 行 = " + ms + "ms")

        assertEquals(1, b.size)
        val t = b.single() as MdBlock.Table
        assertEquals(listOf("c1", "c2"), t.headers)
        assertEquals(19_999, t.rows.size)
        assertTrue("20k 行 pipe 解析应是线性的，实测 " + ms + "ms", ms < 10_000)
    }

    @Test
    fun twentyThousandMixedWidthPipeLinesStayLinear() {
        val sb = StringBuilder()
        for (k in 1..20_000) {
            sb.append(if (k % 2 == 0) "| a | b |\n" else "| a | b | c |\n")
        }
        val text = sb.toString()

        val t0 = System.nanoTime()
        val b = blocks(text)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("[PERF] 20k 混宽 pipe 行 = " + ms + "ms")

        assertEquals("列数交错时都不成表", 0, b.filterIsInstance<MdBlock.Table>().size)
        assertTrue("列数交错也不该 O(n^2)，实测 " + ms + "ms", ms < 10_000)
    }
}
