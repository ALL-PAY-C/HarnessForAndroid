package dev.dsh.mobile.render

import org.junit.Assert.assertTrue
import org.junit.Test

class RealFenceTest {
    /**
     * 可移植离线夹具：一份带多种语言围栏代码块的 markdown 文档。
     * 早期版本硬编码读取本机私有夹具文件（缺文件即 FileNotFoundException），
     * 改为内联样本，断言覆盖不变：≥4 个代码块、流式前缀下围栏标记不泄漏成正文。
     */
    private val fixture = """
        # 渲染管线说明

        模型输出经常在句尾贴围栏，例如「保存成 .html 就能看」：

        ```html
        <h1>渲染测试页</h1>
        <p>这是一段被围栏包裹的 HTML 示例。</p>
        ```

        解析器需要把它识别成代码块，而不是掉成正文纯文本。

        下面是构建脚本：

        ```bash
        #!/usr/bin/env bash
        set -euo pipefail
        ./gradlew :app:assembleDebug --max-workers=1
        echo "构建完成"
        ```

        客户端侧渲染用 Compose：

        ```kotlin
        @Composable
        fun MarkdownBlock(block: MdBlock) {
            when (block) {
                is MdBlock.CodeBlock -> CodeBlockView(block)
                is MdBlock.Paragraph -> Text(block.spans.render())
                else -> Unit
            }
        }
        ```

        序列化配置片段：

        ```yaml
        streaming: true
        normalizeInline: true
        maxWorkers: 1
        ```

        注意行内代码 `val x = 1` 与粗体 **强调** 都要原样保留，
        流式半截（用户还在打字）时也不得丢失字符。
    """.trimIndent()

    private fun text(): String = fixture
    @Test fun real() {
        val t = text()
        val blocks = MarkdownParser.parseBlocks(t, false)
        val codes = blocks.filterIsInstance<MdBlock.CodeBlock>()
        println("FULL codes=" + codes.size + " kinds=" + blocks.size)
        assertTrue(codes.size >= 4)
    }
    @Test fun streamingPrefixes() {
        val t = text()
        var bad = 0
        val report = StringBuilder()
        var k = 40
        while (k <= t.length) {
            val head = t.substring(0, k)
            val cut = safePrefixLength(head)
            val hp = head.substring(0, cut)
            val tp = head.substring(cut)
            val hb = MarkdownParser.parseBlocks(hp, false)
            val tb = MarkdownParser.parseBlocks(tp, true)
            val plain = (hb + tb).filterIsInstance<MdBlock.Paragraph>()
                .joinToString(" ") { p -> p.spans.joinToString("") { s -> (s as? MdInline.Text)?.text ?: "" } }
            if (plain.contains("```")) {
                bad++
                if (report.length < 600) report.append("k=").append(k).append(" cut=").append(cut)
                    .append(" tail=").append(tp.take(30).replace("\n", "\\n")).append(" || ")
            }
            k += 137
        }
        println("STREAM bad=" + bad + " " + report)
        assertTrue(report.toString(), bad == 0)
    }
}