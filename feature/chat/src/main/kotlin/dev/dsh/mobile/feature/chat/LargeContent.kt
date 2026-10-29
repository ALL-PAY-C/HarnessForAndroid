package dev.dsh.mobile.feature.chat

/**
 * 大块内容的渲染分诊（T4）：会话里会进来整页 HTML / 几十万字的表格，
 * 原文一个字都不能丢（历史、导出、搜索、复制全靠它），但排版不能把它当普通
 * Markdown 段落整段塞给渲染器——那一次测量就会把界面卡死。
 *
 * 这里只做「怎么渲染」的判定，不做任何字符替换或截断：原文仍在 ChatMessage.text 里，
 * 渲染层拿 [ContentShape] 决定内联 / 折叠预览。截断、展开、复制按钮由渲染侧实现。
 *
 * ponytail: 阈值按「首屏可读 + 一次测量不卡」定，20k 字符以下 Markdown 逐段排版仍流畅；
 * 升级路径是把阈值挪到 settings（不同设备量级不同），不在这里加自适应测量。
 */
internal object LargeContent {
    /** 超过这个字符数就不当普通文本整段渲染。 */
    const val RAW_RENDER_MAX_CHARS = 20_000

    /** 折叠态默认放出的前缀字数（够看清开头是什么东西）。 */
    const val PREVIEW_MAX_CHARS = 4_000

    /** 结构化标记：命中即说明它是表格/HTML，折叠态要提示「这是表格」而不是普通长文。 */
    private val MARKUP_HINTS = listOf("<table", "<html", "<div", "<pre", "|---", "| ---")

    fun shape(text: String): ContentShape {
        if (text.length <= RAW_RENDER_MAX_CHARS) return ContentShape.Inline
        val head = text.take(2048).lowercase()
        return ContentShape.Collapsed(
            totalChars = text.length,
            previewChars = PREVIEW_MAX_CHARS,
            markup = MARKUP_HINTS.any { head.contains(it) },
        )
    }
}

/** 一条消息的渲染形态。 */
internal sealed interface ContentShape {
    /** 普通消息：整段交给渲染器。 */
    data object Inline : ContentShape

    /** 大块内容：只渲染前 [previewChars] 字，其余折叠（原文不动）。 */
    data class Collapsed(val totalChars: Int, val previewChars: Int, val markup: Boolean) : ContentShape
}
