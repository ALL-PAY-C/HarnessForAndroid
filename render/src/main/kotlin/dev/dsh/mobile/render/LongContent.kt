package dev.dsh.mobile.render

import androidx.compose.ui.unit.dp

/**
 * T4 长内容分流：一条消息超过 [LONG_MESSAGE_COLLAPSE_CHARS] 字符时，渲染层只画前
 * [LONG_MESSAGE_PREVIEW_CHARS] 字符 + 「展开全文」，数据层（message.text）原文不动。
 * 26 万字级别的 HTML 报告整段塞进 AnnotatedString 会直接卡死/撑爆内存，所以先按字符数分流。
 *
 * ponytail: 阈值按字符数硬切，不做「按块数」估算；真要更聪明的截断（保留表格头/目录），
 * 得等渲染层拿到块结构再切，现在这一层只有原文。
 */
internal const val LONG_MESSAGE_COLLAPSE_CHARS = 20_000
internal const val LONG_MESSAGE_PREVIEW_CHARS = 4_000

/**
 * 长代码块 / 长表格只限高滚动，不折叠：内容仍在，只是别把整屏顶掉。
 * ponytail: 按行数硬判，不做「按可视高度」测量；要更准得等布局阶段测量结果回流。
 */
internal const val CODE_BLOCK_SCROLL_LINES = 24
internal const val TABLE_SCROLL_ROWS = 14
internal val CODE_BLOCK_MAX_HEIGHT = 360.dp
internal val TABLE_MAX_HEIGHT = 420.dp

/**
 * 粗略判断这段正文是不是 HTML 源码（≥3 个标签）。折叠态的预览用它决定要不要剥标签：
 * 26 万字 HTML 报告折起来以后，用户想看的是正文，不是一屏 <tr><td> 标签汤。
 */
internal fun looksLikeHtml(text: String): Boolean {
    var hits = 0
    var i = text.indexOf('<')
    while (i >= 0 && i < text.length) {
        val next = text.indexOf('>', i)
        if (next > i + 1 && text[i + 1].isLetter()) {
            hits++
            if (hits >= 3) return true
        }
        i = if (next > i) text.indexOf('<', next) else -1
    }
    return false
}

/** 代码块是否需要限高纵向滚动（行数 >= 阈值；行数 = 换行数 + 1）。 */
internal fun codeNeedsScroll(code: String): Boolean =
    code.count { it == '\n' } + 1 >= CODE_BLOCK_SCROLL_LINES

/** 表格是否需要限高纵向滚动。 */
internal fun tableNeedsScroll(rowCount: Int): Boolean = rowCount > TABLE_SCROLL_ROWS

/** 折叠方案：previewChars 是折叠态要画多少字，totalChars 是原文长度。 */
internal data class CollapsePlan(val previewChars: Int, val totalChars: Int) {
    val hiddenChars: Int get() = (totalChars - previewChars).coerceAtLeast(0)
}

/**
 * 是否需要折叠。流式时不折叠——内容还在长，折叠只会让用户看不到进度。
 * 预览优先切在空行（块边界），避免把围栏/表格切一半；找不到空行就硬切。
 */
internal fun collapsePlan(text: String, streaming: Boolean = false): CollapsePlan? {
    if (streaming) return null
    if (text.length <= LONG_MESSAGE_COLLAPSE_CHARS) return null
    val window = text.take(LONG_MESSAGE_PREVIEW_CHARS)
    val cut = window.lastIndexOf("\n\n")
    val preview = if (cut > LONG_MESSAGE_PREVIEW_CHARS / 2) cut else LONG_MESSAGE_PREVIEW_CHARS
    return CollapsePlan(preview.coerceIn(1, text.length), text.length)
}

/** 极简剥标签：处理 <tag ...> 与 <style>/<script> 整块，并把实体解码成真字符。 */
internal fun stripTags(raw: String): String {
    val noBlocks = Regex("<(style|script|head)\\b[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE)
        .replace(raw, " ")
    val sb = StringBuilder(noBlocks.length)
    var inTag = false
    for (c in noBlocks) {
        when {
            c == '<' -> inTag = true
            c == '>' -> {
                inTag = false
                sb.append(' ')
            }
            !inTag -> sb.append(c)
        }
    }
    return HtmlRenderer.decodeEntities(sb.toString())
        .replace(Regex("[ \\t]{2,}"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
