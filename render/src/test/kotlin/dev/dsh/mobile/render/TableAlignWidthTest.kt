package dev.dsh.mobile.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表格的列对齐与列宽权重。
 *
 * 线上现象（设备实测 1080 宽屏、卡片内宽约 1000px）：分隔行里的 :--- / ---: 被
 * 完全忽略（三列全部左对齐），列宽固定 200px 等宽，表格右侧留一大片空白。
 */
class TableAlignWidthTest {

    private fun tables(text: String) = MarkdownParser.parseBlocks(text).filterIsInstance<MdBlock.Table>()

    // ------------------------------------------------------------ 列对齐

    @Test
    fun separatorMarkersBecomeColumnAligns() {
        val text = "| 左 | 右 | 中 |\n|:---|--:|:--:|\n| a | b | c |"
        val t = tables(text).single()
        assertEquals(3, t.aligns.size)
        assertEquals(TableAlign.START, t.aligns[0])
        assertEquals(TableAlign.END, t.aligns[1])
        assertEquals(TableAlign.CENTER, t.aligns[2])
    }

    @Test
    fun plainSeparatorMeansDefaultLeft() {
        val text = "| 项目 | 状态 |\n|---|---|\n| 构建 | 通过 |"
        val t = tables(text).single()
        assertEquals(listOf(TableAlign.START, TableAlign.START), t.aligns)
    }

    @Test
    fun bareTableWithoutSeparatorIsAllDefault() {
        val text = "| 指标 | 值 |\n| 内存 | 4G |\n| 磁盘 | 64G |"
        val t = tables(text).single()
        assertEquals("裸表也要给齐默认对齐: " + t.aligns, listOf(TableAlign.START, TableAlign.START), t.aligns)
    }

    @Test
    fun alignsCountMatchesHeaderCount() {
        // 分隔行列数与表头不一致时，多出来的丢掉、缺的补默认，渲染端按下标取值才不会越界。
        val shorter = tables("| a | b | c |\n|:--|--:|\n| 1 | 2 | 3 |").single()
        assertEquals(3, shorter.aligns.size)
        assertEquals(listOf(TableAlign.START, TableAlign.END, TableAlign.START), shorter.aligns)

        val longer = tables("| a | b |\n|:--|--:|:--:|\n| 1 | 2 |").single()
        assertEquals(2, longer.aligns.size)
        assertEquals(listOf(TableAlign.START, TableAlign.END), longer.aligns)
    }

    // ------------------------------------------------------------ 列宽权重

    @Test
    fun longestColumnGetsTheBiggestWeight() {
        val columns = listOf(
            listOf("#", "1", "2"),
            listOf("项目名称", "构建通过率"),
            listOf("说明", "这一列的描述文字特别长需要换行的那种情况也要计算在内"),
        )
        val w = tableColumnWeights(columns)
        assertEquals(3, w.size)
        assertEquals("最长列权重最大: " + w, 24, w[2])
        assertEquals("最短列压到下限: " + w, TABLE_COLUMN_WEIGHT_MIN, w[0])
        assertTrue("长列必须压过短列: " + w, w[2] > w[1])
        assertTrue("中列必须压过短列: " + w, w[1] > w[0])
    }

    @Test
    fun weightUsesLongestCellNotHeader() {
        val columns = listOf(
            listOf("id", "xxxx-yyyy-zzzz-wwww-vvvv"), // 表头短、数据长 → 按数据算
            listOf("k", "v"),
        )
        val w = tableColumnWeights(columns)
        assertTrue("表头短不影响: " + w, w[0] > w[1])
    }

    @Test
    fun emptyCellsFallBackToLowerBound() {
        val w = tableColumnWeights(listOf(listOf("", ""), listOf("a")))
        assertEquals(listOf(TABLE_COLUMN_WEIGHT_MIN, TABLE_COLUMN_WEIGHT_MIN), w)
    }

    @Test
    fun weightIsClampedToUpperBound() {
        val cjk = "长".repeat(60)
        val w = tableColumnWeights(listOf(listOf(cjk), listOf("短")))
        assertEquals(TABLE_COLUMN_WEIGHT_MAX, w[0])
        assertEquals(TABLE_COLUMN_WEIGHT_MIN, w[1])
    }
}
