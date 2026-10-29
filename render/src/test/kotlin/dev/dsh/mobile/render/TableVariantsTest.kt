package dev.dsh.mobile.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表格识别的回归钉：模型给的表格并不总是标准 GFM。
 *
 * 线上现象（用户截图）：一条回答里有三张表，只有最后一张渲染成了表格，前面几张
 * 整段露出 | 竖线源码。根因是没有分隔行 |---|---| 的表格被判成普通正文。
 */
class TableVariantsTest {

    private fun tables(text: String) = MarkdownParser.parseBlocks(text).filterIsInstance<MdBlock.Table>()

    @Test
    fun gfmTableWithSeparator() {
        val text = "| 项目 | 状态 |\n|---|---|\n| 构建 | 通过 |"
        val t = tables(text)
        assertEquals(1, t.size)
        assertEquals(listOf("项目", "状态"), t[0].headers)
        assertEquals(listOf(listOf("构建", "通过")), t[0].rows)
    }

    @Test
    fun bareTableWithoutSeparatorIsStillATable() {
        val text = "| 指标 | 值 |\n| 内存 | 4G |\n| 磁盘 | 64G |"
        val t = tables(text)
        assertEquals("没有分隔行也该成表: " + MarkdownParser.parseBlocks(text), 1, t.size)
        assertEquals(listOf("指标", "值"), t[0].headers)
        assertEquals(2, t[0].rows.size)
    }

    @Test
    fun threeTablesInOneAnswerAllRender() {
        val text = """
            ## 结论

            | 项目 | 状态 |
            |---|---|
            | 构建 | 通过 |

            说明文字。

            | 环境 | 版本 |
            | JDK | 17 |

            紧接表格（无空行）：
            | 指标 | 值 |
            | 内存 | 4G |

            结束。
        """.trimIndent()
        val t = tables(text)
        assertEquals("三张表都要被认出: " + MarkdownParser.parseBlocks(text), 3, t.size)
        assertEquals(listOf("项目", "状态"), t[0].headers)
        assertEquals(listOf("环境", "版本"), t[1].headers)
        assertEquals(listOf(listOf("JDK", "17")), t[1].rows)
        assertEquals(listOf("指标", "值"), t[2].headers)
        val tail = MarkdownParser.parseBlocks(text).last()
        assertTrue("结尾正文丢了: " + tail, tail is MdBlock.Paragraph)
    }

    @Test
    fun proseWithSinglePipeIsNotATable() {
        val text = "命令用 a | b 表示管道，别当成表格。"
        assertTrue(MarkdownParser.parseBlocks(text).none { it is MdBlock.Table })
    }

    @Test
    fun inlineCodeWithPipeDoesNotExplodeTable() {
        val text = "| 语法 | 含义 |\n|---|---|\n| a \\| b | 管道 |"
        val t = tables(text)
        assertEquals(1, t.size)
        assertEquals(1, t[0].rows.size)
    }
}
