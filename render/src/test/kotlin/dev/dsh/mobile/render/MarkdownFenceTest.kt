package dev.dsh.mobile.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 围栏自检：模型把 ```html 贴在句尾、或者把闭合围栏贴在一行 HTML 尾巴上时，
 * 解析结果必须仍然是一段 CodeBlock("html")，而不是掉成正文纯文本（用户截图里的现象）。
 */
class MarkdownFenceTest {

    @Test
    fun inlineFenceAfterSentenceIsRecognised() {
        val text = "下面这段保存成 .html 就能看：```html\n<h1>渲染测试页</h1>\n```\n结束"
        val blocks = MarkdownParser.parseBlocks(text)
        val code = blocks.filterIsInstance<MdBlock.CodeBlock>().firstOrNull()
        assertTrue("行内围栏没被识别成代码块: " + blocks, code != null)
        assertEquals("html", code!!.lang)
        assertTrue("围栏正文丢了: " + code.code, code.code.contains("<h1>渲染测试页</h1>"))
    }

    @Test
    fun closingFenceOnSameLineAsHtml() {
        val text = "```html\n<div>x</div>```\n尾巴"
        val blocks = MarkdownParser.parseBlocks(text)
        val code = blocks.filterIsInstance<MdBlock.CodeBlock>().firstOrNull()
        assertEquals("<div>x</div>", code?.code?.trim())
        val plain = blocks.filterIsInstance<MdBlock.Paragraph>()
            .joinToString("") { p -> p.spans.joinToString("") { s -> (s as? MdInline.Text)?.text ?: "" } }
        assertTrue("闭合后的正文丢了: " + plain, plain.contains("尾巴"))
    }

    @Test
    fun halfStreamedFenceKeepsCharacters() {
        val text = "看这个：```html\n<h1>半截"
        val blocks = MarkdownParser.parseBlocks(text, streaming = true)
        val code = blocks.filterIsInstance<MdBlock.CodeBlock>().firstOrNull()
        assertTrue("流式半截没成代码块: " + blocks, code != null)
        assertTrue("半截内容丢了: " + code?.code, code!!.code.contains("<h1>半截"))
    }
}