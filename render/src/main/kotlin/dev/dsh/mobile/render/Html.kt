package dev.dsh.mobile.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse

/** Tag used for clickable link annotations; shared with [RichText]. */
internal const val LinkAnnotationTag = "URL"

/**
 * Minimal HTML → AnnotatedString translator.
 *
 * Not a parser: a single left-to-right scan with an open-tag stack. Unknown tags
 * are dropped and their text kept; script/style/head bodies are dropped whole;
 * a handful of named entities plus numeric ones are decoded.
 */
object HtmlRenderer {

    private val LINK_COLOR = Color(0xFF3B82F6)
    private val CODE_BG = Color(0x1A808080)

    private val VOID_TAGS = setOf("br", "hr", "img", "input", "meta", "link", "source", "col")
    private val DROP_TAGS = setOf("script", "style", "head", "title", "noscript", "template")
    /** 表格单元格：闭合时补一个空格，否则相邻单元格的文字会粘成一串（列A列B）。 */
    private val CELL_TAGS = setOf("td", "th")
    private val BLOCK_TAGS = setOf(
        "p", "div", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6",
        "blockquote", "pre", "table", "ul", "ol", "section", "article",
    )

    /** 只认「像标签但没写完」的尾巴，避免把正文里的 '<' 也当标签丢掉。 */
    private val TRUNCATED_TAG = Regex("^</?[a-zA-Z][a-zA-Z0-9]*")

    private val ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to "\u00a0", "ensp" to "\u2002", "emsp" to "\u2003", "middot" to "\u00b7",
        "hellip" to "\u2026", "mdash" to "\u2014", "ndash" to "\u2013", "times" to "\u00d7",
        "copy" to "\u00a9", "reg" to "\u00ae", "deg" to "\u00b0", "plusmn" to "\u00b1",
        "laquo" to "\u00ab", "raquo" to "\u00bb", "ldquo" to "\u201c", "rdquo" to "\u201d",
        "lsquo" to "\u2018", "rsquo" to "\u2019", "bull" to "\u2022", "check" to "\u2713",
    )

    private class Frame(val tag: String, val start: Int, val href: String?)

    fun toAnnotated(text: String, base: TextStyle): AnnotatedString {
        // T4：展开态必须拿到原文，超大 HTML 也走完整扫描——截断会让「展开全文」变成残缺内容。
        // ponytail: 单次左到右扫描是 O(n)，26 万字约几十毫秒；真到百万字级再考虑后台解析。
        val builder = AnnotatedString.Builder()
        val stack = ArrayList<Frame>()
        val baseSize = base.fontSize.takeOrElse { 16.sp }
        var i = 0
        val n = text.length

        while (i < n) {
            val c = text[i]

            if (c == '<') {
                val gt = text.indexOf('>', i + 1)
                if (gt < 0) {
                    // T4：原文末尾被截断的标签（"</tr" 没有 '>'）不是正文，丢掉；
                    // 正文里单独的 '<'（"1 < 2"）仍然照原样保留。
                    val rest = text.substring(i)
                    val tag = TRUNCATED_TAG.find(rest)
                    builder.append(if (tag != null) rest.substring(tag.range.last + 1) else rest)
                    break
                }
                val raw = text.substring(i + 1, gt)
                i = gt + 1
                if (raw.startsWith("!--")) {
                    val end = text.indexOf("-->", gt + 1)
                    i = if (end < 0) n else end + 3
                    continue
                }
                val closing = raw.startsWith("/")
                val body = (if (closing) raw.substring(1) else raw).trim()
                if (body.isEmpty()) continue
                val name = body.substringBefore(' ').substringBefore('\t').trimEnd('/').lowercase()
                if (name.isEmpty()) continue

                if (name in DROP_TAGS) {
                    if (!closing) {
                        val closeIdx = text.indexOf("</" + name, gt + 1, ignoreCase = true)
                        if (closeIdx >= 0) {
                            val g2 = text.indexOf('>', closeIdx)
                            i = if (g2 < 0) n else g2 + 1
                        } else {
                            i = n
                        }
                    }
                    continue
                }

                if (name in VOID_TAGS) {
                    if (name == "br" || name == "hr") builder.append('\n')
                    continue
                }

                if (closing) {
                    val idx = stack.indexOfLast { it.tag == name }
                    if (idx >= 0) {
                        while (stack.size > idx + 1) {
                            apply(builder, stack.removeAt(stack.size - 1), builder.length, baseSize)
                        }
                        apply(builder, stack.removeAt(stack.size - 1), builder.length, baseSize)
                        if (name in BLOCK_TAGS) builder.append('\n')
                        else if (name in CELL_TAGS) builder.append(' ')
                    }
                    continue
                }

                val href = if (name == "a") attr(body, "href") else null
                if (name == "li") builder.append("\u2022 ")
                stack.add(Frame(name, builder.length, href))
                continue
            }

            if (c == '&') {
                val semi = text.indexOf(';', i + 1)
                if (semi in (i + 1)..(i + 12)) {
                    val decoded = entity(text.substring(i + 1, semi))
                    if (decoded != null) { builder.append(decoded); i = semi + 1; continue }
                }
                builder.append(c); i++; continue
            }

            builder.append(c); i++
        }

        while (stack.isNotEmpty()) {
            apply(builder, stack.removeAt(stack.size - 1), builder.length, baseSize)
        }
        return builder.toAnnotatedString()
    }

    private fun apply(builder: AnnotatedString.Builder, frame: Frame, end: Int, baseSize: TextUnit) {
        if (end <= frame.start) return
        when (frame.tag) {
            "b", "strong" -> builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold), frame.start, end)
            "i", "em", "cite", "var" -> builder.addStyle(SpanStyle(fontStyle = FontStyle.Italic), frame.start, end)
            "u", "ins" -> builder.addStyle(SpanStyle(textDecoration = TextDecoration.Underline), frame.start, end)
            "s", "strike", "del" -> builder.addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), frame.start, end)
            "code", "pre", "kbd", "samp" ->
                builder.addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = CODE_BG), frame.start, end)
            "small" -> builder.addStyle(SpanStyle(fontSize = baseSize * 0.85f), frame.start, end)
            "h1" -> heading(builder, frame, end, baseSize * 1.6f)
            "h2" -> heading(builder, frame, end, baseSize * 1.35f)
            "h3" -> heading(builder, frame, end, baseSize * 1.15f)
            "h4", "h5", "h6" -> heading(builder, frame, end, baseSize)
            "a" -> {
                builder.addStyle(
                    SpanStyle(color = LINK_COLOR, textDecoration = TextDecoration.Underline),
                    frame.start,
                    end,
                )
                frame.href?.takeIf { it.isNotBlank() }?.let {
                    builder.addStringAnnotation(LinkAnnotationTag, it, frame.start, end)
                }
            }
        }
    }

    /**
     * 只做实体解码（`&#1650;` / `&amp;` 之类）。折叠预览走 [stripTags]，那里也要解码，
     * 否则预览里全是 `&#1650;` 这种原样实体。
     */
    internal fun decodeEntities(text: String): String {
        if (text.indexOf('&') < 0) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '&') {
                val semi = text.indexOf(';', i + 1)
                if (semi in (i + 1)..(i + 12)) {
                    val decoded = entity(text.substring(i + 1, semi))
                    if (decoded != null) { sb.append(decoded); i = semi + 1; continue }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    private fun heading(builder: AnnotatedString.Builder, frame: Frame, end: Int, size: TextUnit) {
        builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = size), frame.start, end)
    }

    private fun attr(body: String, key: String): String? {
        val lower = body.lowercase()
        var from = 0
        while (true) {
            val at = lower.indexOf(key, from)
            if (at < 0) return null
            var p = at + key.length
            while (p < body.length && body[p].isWhitespace()) p++
            if (p < body.length && body[p] == '=') {
                p++
                while (p < body.length && body[p].isWhitespace()) p++
                if (p < body.length && (body[p] == '"' || body[p] == '\'')) {
                    val quote = body[p]
                    val close = body.indexOf(quote, p + 1)
                    if (close > p) return body.substring(p + 1, close)
                    return null
                }
                val endAttr = body.indexOfFirst(p) { it.isWhitespace() || it == '>' }
                return if (endAttr < 0) body.substring(p) else body.substring(p, endAttr)
            }
            from = at + 1
        }
    }

    private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        var i = from
        while (i < length) { if (predicate(this[i])) return i; i++ }
        return -1
    }

    private fun entity(name: String): String? {
        if (name.isEmpty()) return null
        if (name[0] == '#') {
            val code = if (name.length > 1 && (name[1] == 'x' || name[1] == 'X')) {
                name.substring(2).toIntOrNull(16)
            } else {
                name.substring(1).toIntOrNull()
            }
            return code?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) }
        }
        return ENTITIES[name.lowercase()]
    }
}
