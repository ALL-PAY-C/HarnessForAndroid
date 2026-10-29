package dev.dsh.mobile.render

/**
 * Block-level Markdown model. Parsed once per "safe prefix" while streaming, so
 * every block is a value type and equality stays cheap.
 */
/**
 * 表格列对齐。GFM 分隔行里的 :--- / ---: / :---: 分别映射到 START / END / CENTER，
 * 没写标记的列沿用 START（左对齐）。
 */
enum class TableAlign { START, CENTER, END }

sealed class MdBlock {
    data class Paragraph(val spans: List<MdInline>) : MdBlock()
    data class Heading(val level: Int, val spans: List<MdInline>) : MdBlock()
    data class BulletList(val items: List<List<MdInline>>, val ordered: Boolean) : MdBlock()
    data class CodeBlock(val lang: String, val code: String) : MdBlock()
    data class Quote(val spans: List<MdInline>) : MdBlock()
    data object Divider : MdBlock()
    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
        /** 每列对齐，下标与 headers 对齐；空表示全部默认（START）。 */
        val aligns: List<TableAlign> = emptyList(),
    ) : MdBlock()
    data class MathBlock(val latex: String) : MdBlock()
    data class HtmlBlock(val raw: String) : MdBlock()
}

/** Inline model. Text leaves are literal; the rest nest. */
sealed class MdInline {
    data class Text(val text: String) : MdInline()
    data class Bold(val children: List<MdInline>) : MdInline()
    data class Italic(val children: List<MdInline>) : MdInline()
    data class Strike(val children: List<MdInline>) : MdInline()
    data class Code(val text: String) : MdInline()
    data class Link(val label: String, val href: String) : MdInline()
    data class Math(val latex: String) : MdInline()
}

/**
 * Line-oriented Markdown parser.
 *
 * Deliberately forgiving: an unclosed fence, an unclosed display-math block and a
 * half-typed trailing line never throw and never drop characters — the tail is
 * emitted as the block it most plausibly is, which is what streaming needs.
 */
object MarkdownParser {

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val UL = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val OL = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val DIVIDER = Regex("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$")
    private val HTML_OPEN = Regex("^</?([a-zA-Z][a-zA-Z0-9]*)(\\s[^>]*)?/?>")
    /**
     * 认作 HTML 块起点的标签。这里必须覆盖常规 HTML5 块级标签：
     * 漏一个（曾漏 figure / figcaption / script / style），那一行就会掉进段落、
     * 把标签原样铺给用户。script/style 进来以后由 HtmlRenderer 的 DROP_TAGS 整块丢弃。
     * ponytail: 只列已知标签，自定义标签（<my-widget>）仍会按正文显示；要全认就得做真正的标签名判定。
     */
    private val HTML_TAGS = setOf(
        "a", "abbr", "address", "article", "aside", "audio", "b", "blockquote", "body", "br",
        "button", "canvas", "caption", "code", "col", "colgroup", "dd", "del", "details", "div",
        "dl", "dt", "em", "embed", "fieldset", "figcaption", "figure", "footer", "form", "h1",
        "h2", "h3", "h4", "h5", "h6", "head", "header", "hr", "html", "i", "iframe", "img",
        "input", "ins", "kbd", "label", "legend", "li", "link", "main", "mark", "meta", "nav",
        "noscript", "object", "ol", "option", "p", "picture", "pre", "q", "s", "samp", "script",
        "section", "select", "small", "source", "span", "strong", "style", "sub", "summary",
        "sup", "svg", "table", "tbody", "td", "template", "textarea", "tfoot", "th", "thead",
        "title", "tr", "track", "u", "ul", "var", "video",
    )

    /**
     * 公式里才有的运算符/结构字符。一个 $…$ 里一个都没有、又由「裸数字 + 空格」开头，
     * 就是金额区间（"$5 到 10$"）而不是公式。
     */
    private const val MATH_CHARS = "+-*/=^_{}\\()[]|<>"

    /**
     * 模型经常把围栏贴在句子末尾（「……效果：```html」），或者把闭合围栏贴在一行 HTML 的尾巴上
     * （「</html>```」）。行式解析只认行首围栏，这两种写法会让整段 HTML 掉成正文纯文本——
     * 所以先按行把行内围栏拆开，再交给块解析。围栏里的内容不动，流式半截也不会丢字符。
     */
    internal fun normalizeInlineFences(raw: String): List<String> {
        val out = ArrayList<String>()
        var inFence = false
        for (line in raw.split('\n')) {
            var rest = line
            while (true) {
                val idx = rest.indexOf("```")
                if (idx < 0) {
                    out.add(rest)
                    break
                }
                if (!inFence) {
                    // 同一行里还有第二个围栏标记 → 这是行内代码（模型常写成「……：```bash ls```」），
                    // 当成块围栏会把后面整段正文吞进代码块，所以就地折成单反引号行内代码。
                    val second = rest.indexOf("```", idx + 3)
                    if (second > idx) {
                        val inner = rest.substring(idx + 3, second).trim()
                        val langOnly = inner.takeWhile { it.isLetterOrDigit() || it in "+#._-" }
                        val body = inner.substring(langOnly.length).trim()
                        val shown = if (body.isEmpty()) inner else body
                        out.add(
                            rest.substring(0, idx) + "`" + shown.replace("`", "'") + "`" +
                                rest.substring(second + 3),
                        )
                        break
                    }
                    if (idx > 0) out.add(rest.substring(0, idx))
                    val tail = rest.substring(idx + 3)
                    val lang = tail.takeWhile { it.isLetterOrDigit() || it in "+#._-" }
                    out.add("```" + lang)
                    inFence = true
                    rest = tail.substring(lang.length)
                    if (rest.isEmpty()) break
                    continue
                }
                if (idx > 0) out.add(rest.substring(0, idx))
                out.add("```")
                inFence = false
                rest = rest.substring(idx + 3)
                if (rest.isEmpty()) break
            }
        }
        return out
    }

    fun parseBlocks(text: String, streaming: Boolean = false): List<MdBlock> {
        if (text.isEmpty()) return emptyList()
        // 模型常把整份 HTML 文档直接贴进消息（以 <!DOCTYPE / <html 开头、外面没有围栏）。
        // 行式解析只认行首 <字母，<!DOCTYPE …> 会让整段掉成正文，把标签原样铺给用户。
        // ponytail: 只认文档级开头；行内片段仍走下面的 HTML_OPEN 分支。
        val doc = text.trimStart()
        if ((doc.startsWith("<!doctype", true) || doc.startsWith("<html", true)) && looksLikeHtml(doc)) {
            return listOf(MdBlock.HtmlBlock(doc.trimEnd()))
        }
        val lines = normalizeInlineFences(text.replace("\r\n", "\n").replace('\r', '\n'))
        val out = ArrayList<MdBlock>()
        val para = ArrayList<String>()
        var i = 0

        fun flushPara() {
            if (para.isEmpty()) return
            val joined = para.joinToString("\n").trim()
            para.clear()
            if (joined.isNotEmpty()) out.add(MdBlock.Paragraph(parseInline(joined)))
        }

        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()

            if (t.isEmpty()) {
                flushPara(); i++; continue
            }

            if (t.startsWith("```")) {
                flushPara()
                val lang = t.removePrefix("```").trim()
                val body = ArrayList<String>()
                i++
                while (i < lines.size) {
                    if (lines[i].trim().startsWith("```")) { i++; break }
                    body.add(lines[i]); i++
                }
                out.add(MdBlock.CodeBlock(lang, body.joinToString("\n")))
                continue
            }

            if (t == "$$") {
                flushPara(); i++
                val body = ArrayList<String>()
                while (i < lines.size) {
                    if (lines[i].trim() == "$$") { i++; break }
                    body.add(lines[i]); i++
                }
                out.add(MdBlock.MathBlock(body.joinToString("\n").trim()))
                continue
            }

            // 行内写块级公式：模型最常写成「一句话：$$\\tau = 0.75 R_0 C$$」。
            // 这种行不满足 t == "$$"，会掉进行内解析被拆成两个孤立的 $，公式退化成纯文本。
            val inlineMath = if (t.contains("$$")) splitInlineDisplayMath(t) else null
            if (inlineMath != null) {
                flushPara()
                if (inlineMath.first.isNotEmpty()) {
                    out.add(MdBlock.Paragraph(parseInline(inlineMath.first)))
                }
                out.add(MdBlock.MathBlock(inlineMath.second))
                if (inlineMath.third.isNotEmpty()) para.add(inlineMath.third)
                i++
                continue
            }

            // 另一种常见写法是开标记跟公式挤在同一行、闭合另起一行（"$$x = 1" … "$$"）。
            // 只认「整行就是 $$」会让它整段掉成正文，两个 $ 原样露出来。
            if (t.startsWith("$$")) {
                flushPara()
                val body = ArrayList<String>()
                val first = t.removePrefix("$$").trim()
                if (first.isNotEmpty()) body.add(first)
                i++
                while (i < lines.size) {
                    val raw = lines[i]
                    if (raw.trim().startsWith("$$")) {
                        val tail = raw.trim().removePrefix("$$").trim()
                        if (tail.isNotEmpty()) body.add(tail)
                        i++
                        break
                    }
                    body.add(raw); i++
                }
                out.add(MdBlock.MathBlock(body.joinToString("\n").trim()))
                continue
            }

            if (t.startsWith("\\[")) {
                flushPara()
                val rest = t.removePrefix("\\[")
                val body = ArrayList<String>()
                if (rest.contains("\\]")) {
                    body.add(rest.substringBefore("\\]"))
                    i++
                } else {
                    if (rest.isNotBlank()) body.add(rest)
                    i++
                    while (i < lines.size) {
                        val l = lines[i]
                        if (l.contains("\\]")) { body.add(l.substringBefore("\\]")); i++; break }
                        body.add(l); i++
                    }
                }
                out.add(MdBlock.MathBlock(body.joinToString("\n").trim()))
                continue
            }

            val h = HEADING.find(t)
            if (h != null) {
                flushPara()
                val level = h.groupValues[1].length.coerceIn(1, 6)
                out.add(MdBlock.Heading(level, parseInline(h.groupValues[2].trim())))
                i++
                continue
            }

            if (DIVIDER.matches(t)) {
                flushPara(); out.add(MdBlock.Divider); i++; continue
            }

            if (t.startsWith(">")) {
                flushPara()
                val body = ArrayList<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    body.add(lines[i].trim().removePrefix(">").removePrefix(" "))
                    i++
                }
                out.add(MdBlock.Quote(parseInline(body.joinToString("\n").trim())))
                continue
            }

            if (t.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
                flushPara()
                val headers = splitRow(t)
                // 分隔行同时决定每列对齐（:--- 左 / ---: 右 / :---: 居中）。
                val aligns = tableAligns(lines[i + 1], headers.size)
                i += 2
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                    rows.add(splitRow(lines[i])); i++
                }
                out.add(MdBlock.Table(headers, rows, aligns))
                continue
            }

            // 没有分隔行的「裸表格」：模型经常只写 | 列 | 列 | 再加几行数据，不给 |---|---|。
            // GFM 不认这种写法，但用户看到的就是一张表；不收进来就会露出一屏竖线源码。
            val bare = barePipeTable(lines, i)
            if (bare != null) {
                flushPara()
                val headers = bare.first()
                out.add(MdBlock.Table(headers, bare.drop(1), List(headers.size) { TableAlign.START }))
                i += bare.size
                continue
            }

            val olm = OL.find(line.replace("\t", "    "))
            val ulm = UL.find(line.replace("\t", "    "))
            if (olm != null || ulm != null) {
                flushPara()
                val items = ArrayList<MutableList<MdInline>>()
                var ordered = olm != null
                var first = true
                while (i < lines.size) {
                    val rawLine = lines[i]
                    if (rawLine.isBlank()) break
                    val expanded = rawLine.replace("\t", "    ")
                    val o = OL.find(expanded)
                    val u = UL.find(expanded)
                    if (o == null && u == null) {
                        if (items.isNotEmpty() && expanded.startsWith("  ")) {
                            val last = items.last()
                            last.add(MdInline.Text("\n"))
                            last.addAll(parseInline(rawLine.trim()))
                            i++
                            continue
                        }
                        break
                    }
                    val indent = (if (o != null) o.groupValues[1] else u!!.groupValues[1]).length
                    val content = if (o != null) o.groupValues[3] else u!!.groupValues[2]
                    if (first) { ordered = o != null; first = false }
                    val spans = parseInline(content.trim())
                    if (indent >= 2 && items.isNotEmpty()) {
                        val last = items.last()
                        last.add(MdInline.Text("\n  · "))
                        last.addAll(spans)
                    } else {
                        items.add(spans.toMutableList())
                    }
                    i++
                }
                out.add(MdBlock.BulletList(items.map { it.toList() }, ordered))
                continue
            }

            val html = HTML_OPEN.find(t)
            if (html != null && html.groupValues[1].lowercase() in HTML_TAGS) {
                flushPara()
                val body = ArrayList<String>()
                // 起始行本身就算块起点，必须先吃掉一行，否则这里会原地死循环（曾经 OOM）。
                body.add(lines[i]); i++
                while (i < lines.size && lines[i].isNotBlank() && !isBlockStartAt(lines, i)) {
                    body.add(lines[i]); i++
                }
                out.add(MdBlock.HtmlBlock(body.joinToString("\n").trim()))
                continue
            }

            while (i < lines.size && !isBlockStartAt(lines, i)) {
                para.add(lines[i]); i++
            }
            if (para.isEmpty()) { para.add(line); i++ }
        }
        flushPara()
        return out
    }

    private fun isBlockStartAt(lines: List<String>, i: Int): Boolean {
        val line = lines[i]
        val t = line.trim()
        if (t.isEmpty()) return true
        if (t.startsWith("```")) return true
        if (t.startsWith("$$") || t.startsWith("\\[")) return true
        if (HEADING.matches(t)) return true
        if (DIVIDER.matches(t)) return true
        if (t.startsWith(">")) return true
        val expanded = line.replace("\t", "    ")
        if (UL.matches(expanded) || OL.matches(expanded)) return true
        if (t.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1])) return true
        if (t.startsWith("|") && barePipeTable(lines, i) != null) return true
        val html = HTML_OPEN.find(t)
        if (html != null && html.groupValues[1].lowercase() in HTML_TAGS) return true
        return false
    }

    /**
     * 把「前文 $$公式$$ 后文」拆成三段。拆不出来返回 null。
     * 只认同一行内成对的 $$，跨行的情况由上面 t == "$$" 的分支处理。
     */
    private fun splitInlineDisplayMath(line: String): Triple<String, String, String>? {
        val open = line.indexOf("$$")
        if (open < 0) return null
        val close = line.indexOf("$$", open + 2)
        if (close < 0) return null
        val body = line.substring(open + 2, close).trim()
        if (body.isEmpty()) return null
        return Triple(
            line.substring(0, open).trim(),
            body,
            line.substring(close + 2).trim(),
        )
    }

    /**
     * 没有分隔行的表格：连续 pipe 行（>=2 行、每行列数一致、都以 | 开头）整块收成表。
     * 只认以 | 开头的行，正文里的 a | b 不会被误判。
     */
    private fun barePipeTable(lines: List<String>, start: Int): List<List<String>>? {
        if (start >= lines.size) return null
        val head = lines[start].trim()
        if (!head.startsWith("|")) return null
        val cols = splitRow(head).size
        if (cols < 2) return null
        val rows = ArrayList<List<String>>()
        var i = start
        while (i < lines.size) {
            val t = lines[i].trim()
            if (!t.startsWith("|")) break
            val cells = splitRow(t)
            if (cells.size != cols) break
            rows.add(cells)
            i++
        }
        return if (rows.size >= 2) rows else null
    }

    private fun isTableSeparator(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty() || !t.contains('-')) return false
        val cells = splitRow(t)
        if (cells.isEmpty()) return false
        return cells.all { cell -> cell.isNotEmpty() && cell.all { it == '-' || it == ':' } }
    }

    /**
     * 从分隔行读出每列对齐：两端都有冒号居中，只有结尾冒号右对齐，其余（含 --- 与只有开头冒号）
     * 左对齐。列数不足时补默认值，多余的对齐标记丢弃。
     */
    internal fun tableAligns(separator: String, columns: Int): List<TableAlign> {
        val cells = splitRow(separator)
        return List(columns) { c ->
            val cell = cells.getOrNull(c).orEmpty()
            when {
                cell.startsWith(":") && cell.endsWith(":") -> TableAlign.CENTER
                cell.endsWith(":") -> TableAlign.END
                else -> TableAlign.START
            }
        }
    }

    private fun splitRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|")) s = s.dropLast(1)
        return s.split('|').map { it.trim() }
    }

    // ---------------------------------------------------------------- inline

    fun parseInline(text: String): List<MdInline> = parseInline(text, 0)

    private fun parseInline(text: String, depth: Int): List<MdInline> {
        if (text.isEmpty()) return emptyList()
        if (depth > 8) return listOf(MdInline.Text(text))
        val out = ArrayList<MdInline>()
        val buf = StringBuilder()
        var i = 0
        val n = text.length

        fun flush() {
            if (buf.isNotEmpty()) { out.add(MdInline.Text(buf.toString())); buf.setLength(0) }
        }

        while (i < n) {
            val c = text[i]
            // \( … \) 与 \[ … \] 是 LaTeX 的行内定界写法，必须拦在通用反斜杠转义之前，
            // 否则 \( 会先被吃掉反斜杠、退化成普通圆括号文字。
            // ponytail: 行内 \[ … \] 也按行内公式画（不换行居中）；要区分展示态得给 MdInline.Math 加 display 标记。
            if (c == '\\' && i + 1 < n && (text[i + 1] == '(' || text[i + 1] == '[')) {
                val closer = if (text[i + 1] == '(') "\\)" else "\\]"
                val end = text.indexOf(closer, i + 2)
                if (end > i + 2) {
                    val body = text.substring(i + 2, end)
                    if (!body.contains('\n') && body.isNotBlank()) {
                        flush(); out.add(MdInline.Math(body.trim())); i = end + 2
                        continue
                    }
                }
            }
            if (c == '\\') {
                if (i + 1 < n) { buf.append(text[i + 1]); i += 2 } else { i++ }
                continue
            }
            if (c == '`') {
                val end = findClosing(text, i + 1, '`')
                if (end > i + 1) {
                    flush(); out.add(MdInline.Code(text.substring(i + 1, end))); i = end + 1
                } else { buf.append(c); i++ }
                continue
            }
            if (c == '\u0024') {
                val end = findClosing(text, i + 1, '\u0024')
                if (end > i + 1) {
                    val body = text.substring(i + 1, end)
                    val before = if (i == 0) ' ' else text[i - 1]
                    // money guard: "$100" / "$1,200" / "$5 到 10$" must stay literal, "$x^2$" must not.
                    val numeric = body.all { it.isDigit() || it == '.' || it == ',' }
                    val lead = body.takeWhile { it.isDigit() || it == '.' || it == ',' }
                    // 金额区间的另一种写法「$5 到 10$」：裸数字后紧跟空格，且整段没有任何数学符号。
                    // "$100 + 200$" 有运算符，不受这条影响。
                    val moneySpan = lead.isNotEmpty() && lead.length < body.length &&
                        body[lead.length].isWhitespace() && body.none { it in MATH_CHARS }
                    val ok = body.isNotEmpty() &&
                        !body.first().isWhitespace() && !body.last().isWhitespace() &&
                        !before.isDigit() && !numeric && !moneySpan
                    if (ok) { flush(); out.add(MdInline.Math(body)); i = end + 1; continue }
                }
                buf.append(c); i++
                continue
            }
            if (c == '*') {
                if (i + 1 < n && text[i + 1] == '*') {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        val body = text.substring(i + 2, end)
                        if (body.isNotEmpty() && !body.first().isWhitespace() && !body.last().isWhitespace()) {
                            flush(); out.add(MdInline.Bold(parseInline(body, depth + 1))); i = end + 2
                            continue
                        }
                    }
                    buf.append("**"); i += 2
                    continue
                }
                val end = findClosing(text, i + 1, '*')
                if (end > i + 1 && !text[i + 1].isWhitespace() && !text[end - 1].isWhitespace() &&
                    (i == 0 || !text[i - 1].isLetterOrDigit())
                ) {
                    flush(); out.add(MdInline.Italic(parseInline(text.substring(i + 1, end), depth + 1)))
                    i = end + 1
                    continue
                }
                buf.append(c); i++
                continue
            }
            if (c == '_') {
                val end = findClosing(text, i + 1, '_')
                if (end > i + 1 && !text[i + 1].isWhitespace() && !text[end - 1].isWhitespace() &&
                    (i == 0 || !text[i - 1].isLetterOrDigit()) &&
                    (end + 1 >= n || !text[end + 1].isLetterOrDigit())
                ) {
                    flush(); out.add(MdInline.Italic(parseInline(text.substring(i + 1, end), depth + 1)))
                    i = end + 1
                    continue
                }
                buf.append(c); i++
                continue
            }
            if (c == '~' && i + 1 < n && text[i + 1] == '~') {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 2) {
                    flush(); out.add(MdInline.Strike(parseInline(text.substring(i + 2, end), depth + 1)))
                    i = end + 2
                    continue
                }
                buf.append("~~"); i += 2
                continue
            }
            if (c == '[') {
                val close = text.indexOf(']', i + 1)
                if (close > 0 && close + 1 < n && text[close + 1] == '(') {
                    val paren = text.indexOf(')', close + 2)
                    if (paren > close + 2) {
                        flush()
                        out.add(
                            MdInline.Link(
                                text.substring(i + 1, close),
                                text.substring(close + 2, paren).trim(),
                            )
                        )
                        i = paren + 1
                        continue
                    }
                }
                buf.append(c); i++
                continue
            }
            buf.append(c); i++
        }
        flush()
        return out
    }

    private fun findClosing(text: String, from: Int, ch: Char): Int {
        var i = from
        while (i < text.length) {
            if (text[i] == ch) return i
            if (text[i] == '\n') return -1
            i++
        }
        return -1
    }
}