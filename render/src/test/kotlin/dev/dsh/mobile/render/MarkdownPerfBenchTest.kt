package dev.dsh.mobile.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * 长消息解析基准（纯 JVM，无新依赖）。
 *
 * 场景（均约 5 万字符）：
 *  - plain-50k          纯段落
 *  - tables-50k-20      20 张 GFM 表格（5 列）
 *  - fences-50k-30      30 个代码围栏
 *  - inline-unmatched   大量未配对 [ / ** 的正文（parseInline 前向扫描的压力场景）
 *
 * 每档既测单次全量解析，也测流式逐段重解析（safePrefixLength + 缓存 head + 重解析 tail，
 * 即 RichText.rememberBlocks 在主线程上干的活）。Compose 的组合/测量需要设备，JVM 侧测不到。
 */
class MarkdownPerfBenchTest {

    private fun repeatTo(unit: String, target: Int): String {
        val sb = StringBuilder(target + unit.length)
        while (sb.length < target) sb.append(unit)
        return sb.toString()
    }

    private fun plainParagraphs(target: Int = 50_000): String {
        val sb = StringBuilder(target + 256)
        var n = 0
        while (sb.length < target) {
            sb.append("这是一段用来模拟真实模型输出的中文正文，长度大致和聊天里差不多。")
            n++
            sb.append('\n')
            if (n % 3 == 0) sb.append('\n')
        }
        return sb.toString()
    }

    private fun tablesDocument(count: Int = 20, target: Int = 50_000): String {
        val quota = target / count
        val sb = StringBuilder(target + 1024)
        for (t in 0 until count) {
            val until = (t + 1) * quota
            sb.append("表 " + t + " 的说明文字。\n\n")
            sb.append("| 编号 | 名称 | 状态 | 负责人 | 备注 |\n")
            sb.append("| --- | :--- | :---: | ---: | --- |\n")
            var r = 0
            while (sb.length < until) {
                sb.append("| " + r + " | 条目" + r + " | 完成 | 张三 | 备注" + r + " |\n")
                r++
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun fencesDocument(count: Int = 30, target: Int = 50_000): String {
        val quota = target / count
        val sb = StringBuilder(target + 1024)
        for (b in 0 until count) {
            val until = (b + 1) * quota
            sb.append("下面是第 " + b + " 段代码：\n\n")
            sb.append("\u0060\u0060\u0060kotlin\n")
            var l = 0
            while (sb.length < until) {
                sb.append("    val value" + l + " = compute(a" + l + ", b" + l + ")\n")
                l++
            }
            sb.append("\u0060\u0060\u0060\n\n")
        }
        return sb.toString()
    }

    /**
     * 单个超长段落（无空行）里密集出现未配对的 [ 与 **：修复前每处都从当前位置扫到文末，
     * 这是 ANR 栈上 String.indexOf 的直接来源（O(n^2)）。必须是一整段，否则每段各自短扫描，压不出问题。
     */
    private fun inlineUnmatched(target: Int = 50_000): String {
        val sb = StringBuilder(target + 256)
        var n = 0
        while (sb.length < target) {
            sb.append("第" + n + "条 见附录 [引用 **重点 说明\n")
            n++
        }
        return sb.toString()
    }

    private fun best(n: Int = 7, body: () -> Unit): Long {
        var min = Long.MAX_VALUE
        repeat(n) {
            val ms = measureTimeMillis { body() }
            if (ms < min) min = ms
        }
        return min
    }

    private fun benchParse(name: String, text: String) {
        MarkdownParser.parseBlocks(text, false) // 预热 JIT
        var blocks = 0
        val ms = best { blocks = MarkdownParser.parseBlocks(text, false).size }
        val cells = MarkdownParser.parseBlocks(text, false)
            .filterIsInstance<MdBlock.Table>()
            .sumOf { it.rows.size * it.headers.size }
        println(
            "[bench] parse/" + name + " chars=" + text.length + " bestOf7=" + ms + "ms blocks=" + blocks +
                " tableCells=" + cells
        )
        assertTrue("解析结果不该为空: " + name, blocks > 0)
    }

    private fun benchStreaming(name: String, text: String, chunk: Int = 64) = benchStreamingRun(name, text, chunk)

    private fun benchStreamingRun(name: String, text: String, chunk: Int) {
        var cachedHead = ""
        var cachedBlocks: List<MdBlock> = emptyList()
        var frames = 0
        var worst = 0L
        var worstAt = 0
        val total = measureTimeMillis {
            var len = 0
            while (len < text.length) {
                len = minOf(text.length, len + chunk)
                val frameText = text.substring(0, len)
                frames++
                val frameMs = measureTimeMillis {
                    val cut = safePrefixLength(frameText)
                    val head = frameText.substring(0, cut)
                    if (head != cachedHead) {
                        cachedBlocks = MarkdownParser.parseBlocks(head, false)
                        cachedHead = head
                    }
                    MarkdownParser.parseBlocks(frameText.substring(cut), true)
                }
                if (frameMs > worst) { worst = frameMs; worstAt = len }
            }
        }
        println(
            "[bench] streaming/" + name + " chars=" + text.length + " frames=" + frames +
                " total=" + total + "ms worstFrame=" + worst + "ms@" + worstAt + " cachedBlocks=" + cachedBlocks.size
        )
    }

    @Test
    fun benchThreeScenarios() {
        benchParse("plain-50k", plainParagraphs())
        benchParse("tables-50k-20", tablesDocument())
        benchParse("fences-50k-30", fencesDocument())
        benchParse("inline-unmatched", inlineUnmatched())
        benchStreaming("plain-50k", plainParagraphs())
        benchStreaming("tables-50k-20", tablesDocument())
        benchStreaming("fences-50k-30", fencesDocument())
    }

    /** 未配对标记的规模曲线：修复前应超线性（O(n^2)），修复后应接近线性。 */
    @Test
    fun benchInlineScaling() {
        listOf(50_000, 100_000, 200_000, 400_000).forEach { n ->
            val text = inlineUnmatched(n)
            MarkdownParser.parseBlocks(text, false)
            val ms = best(5) { MarkdownParser.parseBlocks(text, false) }
            println("[bench] scaling/unmatched chars=" + text.length + " bestOf5=" + ms + "ms")
        }
    }

    /** 基准场景自检：别在生成器退化成短文本时还“跑得很快”。 */
    @Test
    fun scenariosAreLongEnough() {
        assertTrue(plainParagraphs().length >= 50_000)
        assertTrue(tablesDocument().length >= 50_000)
        assertTrue(fencesDocument().length >= 50_000)
        assertTrue(inlineUnmatched().length >= 50_000)
        assertEquals(20, MarkdownParser.parseBlocks(tablesDocument()).filterIsInstance<MdBlock.Table>().size)
        assertEquals(30, MarkdownParser.parseBlocks(fencesDocument()).filterIsInstance<MdBlock.CodeBlock>().size)
    }
}
