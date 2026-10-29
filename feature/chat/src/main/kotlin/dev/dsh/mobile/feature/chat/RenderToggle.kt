package dev.dsh.mobile.feature.chat

/** 正文渲染路径。 */
internal enum class BodyRender {
    /** :render 的内置富文本（Markdown / LaTeX / 代码块 / 表格）。 */
    Rich,

    /** 交给 HTML 渲染插件（"html.web"）用 WebView 渲染整块文档或 html 代码块。 */
    Html,

    /** 关掉特殊格式渲染：逐字原样显示源码。 */
    Source,
}

/**
 * 一条消息的正文该走哪条路径。
 *
 * 抽成纯函数是为了能在 JVM 单测里钉死这条契约：只要 [renderSpecial] 为 false，
 * 无论消息是不是 HTML 文档、插件有没有给它指定 rendererId，都必须走 [BodyRender.Source]——
 * 关掉开关不能出现「什么都没渲染出来」的空消息。
 *
 * @param rendererId 插件给这条消息指定的渲染器（如加载历史插件消息时带的 "html.web"）。
 * @param looksLikeHtml 整条消息是否是完整 HTML 文档的判定（[looksLikeHtmlDocument]）。
 */
internal fun bodyRender(
    text: String,
    renderSpecial: Boolean,
    rendererId: String?,
    streaming: Boolean,
    looksLikeHtml: (String) -> Boolean,
): BodyRender = when {
    !renderSpecial -> BodyRender.Source
    rendererId != null -> BodyRender.Html
    !streaming && looksLikeHtml(text) -> BodyRender.Html
    else -> BodyRender.Rich
}
