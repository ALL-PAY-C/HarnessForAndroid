package dev.dsh.mobile.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import java.util.LinkedHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 纯 Compose 手写的 LaTeX 数学公式渲染器。零第三方依赖、零 WebView。
 *
 * 流程: tokenize -> parse(Node 树) -> 递归排版成 MathBox(宽/高/基线/绘制闭包) -> 一次 Canvas 绘制。
 * 上下标真的缩小并上下偏移, 分数真的画横线, 根号真的画钩。
 */

// ---------------------------------------------------------------------------------------------
// 公开 API
// ---------------------------------------------------------------------------------------------

/**
 * 渲染一段 LaTeX 数学公式。
 *
 * @param latex 公式源码, 例如 "\\tau = 0.75 R_0 C"
 * @param display true = 独立公式块(字号放大 1.15 倍, 在有界宽度内居中); false = 行内公式(基线与正文对齐)
 * @param color 文字与线条颜色
 * @param fontSize 基准字号; display 模式下实际排版字号为 fontSize * 1.15
 */
@Composable
fun MathFormula(
    latex: String,
    display: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 16.sp,
) {
    if (latex.isBlank()) return
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val fontPx = with(density) { fontSize.toPx() }
    if (fontPx <= 0f) return
    val basePx = fontPx * if (display) 1.15f else 1f

    val cacheKey = latex + '|' + basePx + '|' + color.value + '|' + display
    val box = remember(cacheKey) {
        MathBoxCache.get(cacheKey) {
            try {
                latexLayout(measurer, density, color, basePx, display).layout(latex)
            } catch (t: Throwable) {
                // 任何解析/测量异常都降级成纯文本, 绝不把异常抛给调用方
                latexLayout(measurer, density, color, basePx, display).plain(latex)
            }
        }
    }
    if (box.width <= 0f || box.height <= 0f) return

    // 行高硬上限: 基准字号 * 2.6, 防止撑破聊天气泡; 超限时整体纵向压缩(极端嵌套公式才会触发)
    val cap = basePx * 2.6f
    val vScale = if (box.height > cap) cap / box.height else 1f
    val contentW = ceil(box.width).toInt().coerceAtLeast(1)
    val contentH = ceil(box.height * vScale).toInt().coerceAtLeast(1)
    val baselinePx = (box.baseline * vScale).roundToInt().coerceIn(0, contentH)

    Layout(
        content = {},
        modifier = modifier.drawBehind {
            val ox = if (display) max(0f, (size.width - box.width) / 2f) else 0f
            if (vScale < 1f) {
                scale(scaleX = 1f, scaleY = vScale, pivot = Offset(0f, 0f)) { box.draw(this, ox, 0f) }
            } else {
                box.draw(this, ox, 0f)
            }
        },
    ) { _, constraints ->
        val w = if (display && constraints.hasBoundedWidth) max(contentW, constraints.maxWidth) else contentW
        layout(w, contentH, mapOf(FirstBaseline to baselinePx)) {}
    }
}

// ---------------------------------------------------------------------------------------------
// 排版模型
// ---------------------------------------------------------------------------------------------

/**
 * 一个已排版好的公式片段。坐标约定: draw(originX, originY) 的 originY 是盒子顶部, baseline 是相对顶部的基线偏移。
 */
internal data class MathBox(
    val width: Float,
    val height: Float,
    val baseline: Float,
    val draw: DrawScope.(originX: Float, originY: Float) -> Unit,
)

private val EMPTY_BOX = MathBox(0f, 0f, 0f) { _, _ -> }

// ---------------------------------------------------------------------------------------------
// Tokenizer
// ---------------------------------------------------------------------------------------------

private enum class TextKind { ITALIC, ROMAN }

private sealed class Tok {
    class Ch(val c: Char) : Tok()
    class Num(val s: String) : Tok()
    class Cmd(val name: String) : Tok()
    class Txt(val s: String, val kind: TextKind) : Tok()
    class Space(val em: Float) : Tok()
    class Delim(val open: Boolean, val text: String) : Tok()
    class Begin(val env: String) : Tok()
    object End : Tok()
    object Amp : Tok()
    object NewRow : Tok()
    object LB : Tok()
    object RB : Tok()
    object Sub : Tok()
    object Sup : Tok()
}

private fun tokenize(src: String): List<Tok> {
    val out = ArrayList<Tok>(src.length)
    var i = 0
    val n = src.length
    while (i < n) {
        val c = src[i]
        when {
            c == '\\' -> {
                i++
                if (i >= n) break
                val c2 = src[i]
                if (c2.isLetter()) {
                    val start = i
                    while (i < n && src[i].isLetter()) i++
                    val name = src.substring(start, i)
                    when (name) {
                        "text", "textrm", "mathrm", "mathbf", "mathit", "mbox", "operatorname", "textnormal" -> {
                            val kind = if (name == "mathit") TextKind.ITALIC else TextKind.ROMAN
                            var j = i
                            while (j < n && src[j].isWhitespace()) j++
                            if (j < n && src[j] == '{') {
                                val body = StringBuilder()
                                var depth = 0
                                var k = j
                                while (k < n) {
                                    val ch = src[k]
                                    when {
                                        ch == '{' -> {
                                            depth++
                                            if (depth > 1) body.append(ch)
                                            k++
                                        }
                                        ch == '}' -> {
                                            depth--
                                            k++
                                            if (depth <= 0) break else body.append('}')
                                        }
                                        else -> {
                                            body.append(ch)
                                            k++
                                        }
                                    }
                                }
                                i = k
                                out.add(Tok.Txt(body.toString(), kind))
                            } else {
                                out.add(Tok.Cmd(name))
                            }
                        }
                        "left", "right" -> {
                            var j = i
                            while (j < n && src[j].isWhitespace()) j++
                            if (j < n) {
                                var d = src[j].toString()
                                var k = j + 1
                                if (src[j] == '\\' && k < n) {
                                    d = src[k].toString()
                                    k++
                                }
                                i = k
                                out.add(Tok.Delim(name == "left", d))
                            } else {
                                out.add(Tok.Cmd(name))
                            }
                        }
                        // 环境: 已知矩阵族进语法树, 其余整段原文(含反斜杠)保留成文本
                        "begin" -> {
                            val env = readEnvName(src, i)
                            if (env == null) {
                                out.add(Tok.Cmd(name))
                            } else if (env.first in MATRIX_DELIMS) {
                                i = env.second
                                out.add(Tok.Begin(env.first))
                            } else {
                                val stop = findEnvEnd(src, env.second, env.first)
                                val end = if (stop < 0) n else stop
                                out.add(Tok.Txt(src.substring(start - 1, end), TextKind.ROMAN))
                                i = end
                            }
                        }
                        "end" -> {
                            val env = readEnvName(src, i)
                            if (env != null) i = env.second
                            out.add(Tok.End)
                        }
                        "quad" -> out.add(Tok.Space(1f))
                        "qquad" -> out.add(Tok.Space(2f))
                        "enspace" -> out.add(Tok.Space(0.5f))
                        "thinspace" -> out.add(Tok.Space(0.17f))
                        else -> out.add(Tok.Cmd(name))
                    }
                } else {
                    i++
                    when (c2) {
                        '\\' -> out.add(Tok.NewRow)
                        ',', ':', ';', ' ', '!' -> out.add(
                            Tok.Space(
                                when (c2) {
                                    ',' -> 0.17f
                                    ':' -> 0.22f
                                    ';' -> 0.28f
                                    '!' -> -0.1f
                                    else -> 0.3f
                                }
                            )
                        )
                        '{' -> out.add(Tok.Ch('{'))
                        '}' -> out.add(Tok.Ch('}'))
                        else -> out.add(Tok.Cmd(c2.toString()))
                    }
                }
            }
            c == '{' -> {
                out.add(Tok.LB)
                i++
            }
            c == '}' -> {
                out.add(Tok.RB)
                i++
            }
            c == '_' -> {
                out.add(Tok.Sub)
                i++
            }
            c == '^' -> {
                out.add(Tok.Sup)
                i++
            }
            c == '~' -> {
                out.add(Tok.Space(0.3f))
                i++
            }
            c == '&' -> {
                out.add(Tok.Amp)
                i++
            }
            c.isWhitespace() -> i++
            c.isDigit() || c == '.' -> {
                val start = i
                while (i < n && (src[i].isDigit() || src[i] == '.')) i++
                out.add(Tok.Num(src.substring(start, i)))
            }
            else -> {
                out.add(Tok.Ch(c))
                i++
            }
        }
    }
    return out
}

// ---------------------------------------------------------------------------------------------
// 环境 (egin{...} ... end{...})
// ---------------------------------------------------------------------------------------------

/** 支持的矩阵族环境 -> 括号对; 值为 null 表示不带括号。键集合就是"认识的环境"白名单。 */
private val MATRIX_DELIMS: Map<String, Pair<String, String>?> = mapOf(
    "matrix" to null,
    "pmatrix" to Pair("(", ")"),
    "bmatrix" to Pair("[", "]"),
    "vmatrix" to Pair("|", "|"),
    "Bmatrix" to Pair("{", "}"),
)

/** i 指向 \begin / \end 之后。读出 {env} 里的名字, 返回名字与右花括号之后的下标。 */
private fun readEnvName(src: String, from: Int): Pair<String, Int>? {
    var j = from
    val n = src.length
    while (j < n && src[j].isWhitespace()) j++
    if (j >= n || src[j] != '{') return null
    val close = src.indexOf('}', j + 1)
    if (close < 0) return null
    return Pair(src.substring(j + 1, close).trim(), close + 1)
}

/** 从 from 起找配对 \end{env} 的结束下标(右花括号之后); 找不到返回 -1。同名环境按层数配对。 */
private fun findEnvEnd(src: String, from: Int, env: String): Int {
    val beginMark = "\\begin{$env}"
    val endMark = "\\end{$env}"
    var depth = 1
    var k = from
    while (k < src.length) {
        val b = src.indexOf(beginMark, k)
        val e = src.indexOf(endMark, k)
        if (e < 0) return -1
        if (b in 0 until e) {
            depth++
            k = b + beginMark.length
        } else {
            depth--
            k = e + endMark.length
            if (depth == 0) return k
        }
    }
    return -1
}

// ---------------------------------------------------------------------------------------------
// 语法树
// ---------------------------------------------------------------------------------------------

private sealed class Node {
    class Text(val s: String, val kind: TextKind) : Node()
    class Space(val em: Float) : Node()
    class Group(val body: List<Node>) : Node()
    class Fraction(val num: List<Node>, val den: List<Node>) : Node()
    class Sqrt(val body: List<Node>) : Node()
    class Script(val base: Node, val sub: List<Node>?, val sup: List<Node>?) : Node()
    class Delim(val open: String, val close: String, val body: List<Node>) : Node()
    class BigOp(val sym: String) : Node()

    /** rows[行][列] -> 该单元格的节点列表。env 决定外层括号。 */
    class Matrix(val env: String, val rows: List<List<List<Node>>>) : Node()
}

private val GREEK: Map<String, String> = mapOf(
    "alpha" to "\u03b1", "beta" to "\u03b2", "gamma" to "\u03b3", "delta" to "\u03b4",
    "epsilon" to "\u03f5", "varepsilon" to "\u03b5", "zeta" to "\u03b6", "eta" to "\u03b7",
    "theta" to "\u03b8", "vartheta" to "\u03d1", "iota" to "\u03b9", "kappa" to "\u03ba",
    "lambda" to "\u03bb", "mu" to "\u03bc", "nu" to "\u03bd", "xi" to "\u03be",
    "omicron" to "\u03bf", "pi" to "\u03c0", "varpi" to "\u03d6", "rho" to "\u03c1",
    "sigma" to "\u03c3", "varsigma" to "\u03c2", "tau" to "\u03c4", "upsilon" to "\u03c5",
    "phi" to "\u03d5", "varphi" to "\u03c6", "chi" to "\u03c7", "psi" to "\u03c8",
    "omega" to "\u03c9",
    "Gamma" to "\u0393", "Delta" to "\u0394", "Theta" to "\u0398", "Lambda" to "\u039b",
    "Xi" to "\u039e", "Pi" to "\u03a0", "Sigma" to "\u03a3", "Upsilon" to "\u03a5",
    "Phi" to "\u03a6", "Psi" to "\u03a8", "Omega" to "\u03a9",
)

private val SYMBOLS: Map<String, String> = mapOf(
    "cdot" to "\u22c5", "times" to "\u00d7", "div" to "\u00f7", "pm" to "\u00b1", "mp" to "\u2213",
    "le" to "\u2264", "leq" to "\u2264", "ge" to "\u2265", "geq" to "\u2265",
    "ne" to "\u2260", "neq" to "\u2260", "approx" to "\u2248", "equiv" to "\u2261",
    "sim" to "\u223c", "simeq" to "\u2243", "propto" to "\u221d",
    "to" to "\u2192", "rightarrow" to "\u2192", "leftarrow" to "\u2190",
    "Rightarrow" to "\u21d2", "leftrightarrow" to "\u2194", "mapsto" to "\u21a6",
    "infty" to "\u221e", "partial" to "\u2202", "nabla" to "\u2207",
    "in" to "\u2208", "notin" to "\u2209", "subset" to "\u2282", "subseteq" to "\u2286",
    "cup" to "\u222a", "cap" to "\u2229", "forall" to "\u2200", "exists" to "\u2203",
    "emptyset" to "\u2205", "neg" to "\u00ac", "angle" to "\u2220", "degree" to "\u00b0",
    "prime" to "\u2032", "ldots" to "\u2026", "dots" to "\u2026", "cdots" to "\u22ef",
    "hbar" to "\u210f", "ell" to "\u2113", "perp" to "\u22a5", "parallel" to "\u2225",
    "star" to "\u22c6", "circ" to "\u2218", "bullet" to "\u2022",
    "langle" to "\u27e8", "rangle" to "\u27e9", "therefore" to "\u2234", "because" to "\u2235",
    "aleph" to "\u2135", "Re" to "\u211c", "Im" to "\u2111",
)

private enum class Stop { NONE, BRACE, RIGHT, CELL }

private class LatexParser(private val toks: List<Tok>) {
    private var i = 0

    /** 最近一次被 seq(Stop.RIGHT) 消费掉的 \right 定界符文本。 */
    private var lastRight: String = ""

    fun parse(): List<Node> = seq(Stop.NONE)

    private fun seq(stop: Stop): List<Node> {
        val out = ArrayList<Node>()
        while (i < toks.size) {
            val t = toks[i]
            // 单元格边界不消费: 交给 matrix() 判断换列还是换行
            if (stop == Stop.CELL && (t is Tok.Amp || t is Tok.NewRow || t is Tok.End)) break
            if (t is Tok.Amp) {
                i++
                continue
            }
            if (t is Tok.NewRow) {
                i++
                out.add(Node.Space(0.3f))
                continue
            }
            if (t is Tok.RB) {
                i++
                if (stop == Stop.BRACE) break else continue
            }
            if (t is Tok.Delim && !t.open) {
                i++
                if (stop == Stop.RIGHT) {
                    lastRight = t.text
                    break
                } else {
                    continue
                }
            }
            val atom = atom() ?: continue
            var sub: List<Node>? = null
            var sup: List<Node>? = null
            while (i < toks.size) {
                val s = toks[i]
                if (s is Tok.Sub) {
                    i++
                    sub = scriptArg()
                } else if (s is Tok.Sup) {
                    i++
                    sup = scriptArg()
                } else {
                    break
                }
            }
            out.add(if (sub != null || sup != null) Node.Script(atom, sub, sup) else atom)
        }
        return out
    }

    private fun scriptArg(): List<Node> {
        if (i >= toks.size) return emptyList()
        val t = toks[i]
        if (t is Tok.LB) {
            i++
            return seq(Stop.BRACE)
        }
        val a = atom() ?: return emptyList()
        return listOf(a)
    }

    private fun argGroup(): List<Node> {
        if (i >= toks.size) return emptyList()
        val t = toks[i]
        if (t is Tok.LB) {
            i++
            return seq(Stop.BRACE)
        }
        val a = atom() ?: return emptyList()
        return listOf(a)
    }

    private fun atom(): Node? {
        if (i >= toks.size) return null
        return when (val t = toks[i]) {
            is Tok.LB -> {
                i++
                Node.Group(seq(Stop.BRACE))
            }
            is Tok.Ch -> {
                i++
                charNode(t.c)
            }
            is Tok.Num -> {
                i++
                Node.Text(t.s, TextKind.ROMAN)
            }
            is Tok.Space -> {
                i++
                Node.Space(t.em)
            }
            is Tok.Txt -> {
                i++
                Node.Text(t.s, t.kind)
            }
            is Tok.Delim -> {
                i++
                if (t.open) {
                    lastRight = ""
                    val body = seq(Stop.RIGHT)
                    Node.Delim(t.text, lastRight, body)
                } else {
                    null
                }
            }
            is Tok.Cmd -> {
                i++
                command(t.name)
            }
            is Tok.Begin -> {
                i++
                matrix(t.env)
            }
            is Tok.Sub, is Tok.Sup, is Tok.End, is Tok.Amp, is Tok.NewRow -> {
                i++
                null
            }
            is Tok.RB -> null
        }
    }

    private fun command(name: String): Node = when (name) {
        "frac", "dfrac", "tfrac", "cfrac" -> Node.Fraction(argGroup(), argGroup())
        "sqrt" -> {
            if (i < toks.size && toks[i] is Tok.Ch && (toks[i] as Tok.Ch).c == '[') {
                i++
                while (i < toks.size && !(toks[i] is Tok.Ch && (toks[i] as Tok.Ch).c == ']')) i++
                if (i < toks.size) i++
            }
            Node.Sqrt(argGroup())
        }
        "sum" -> Node.BigOp("\u2211")
        "prod" -> Node.BigOp("\u220f")
        "int" -> Node.BigOp("\u222b")
        "iint" -> Node.BigOp("\u222c")
        "oint" -> Node.BigOp("\u222e")
        "lim" -> Node.BigOp("lim")
        "max" -> Node.BigOp("max")
        "min" -> Node.BigOp("min")
        else -> {
            val g = GREEK[name]
            if (g != null) {
                Node.Text(g, if (g[0].isLowerCase()) TextKind.ITALIC else TextKind.ROMAN)
            } else {
                val s = SYMBOLS[name]
                if (s != null) Node.Text(s, TextKind.ROMAN) else Node.Text("\\" + name, TextKind.ROMAN)
            }
        }
    }

    /** \begin{env} ... \end{env}: 列用 & 分隔, 行用 \\ 分隔。嵌套环境靠递归 atom() 处理。 */
    private fun matrix(env: String): Node {
        val rows = ArrayList<List<List<Node>>>()
        var cells = ArrayList<List<Node>>()
        var guard = 0
        while (i < toks.size && guard++ < 100_000) {
            cells.add(seq(Stop.CELL))
            if (i >= toks.size) break
            val t = toks[i]
            if (t is Tok.Amp) {
                i++
            } else if (t is Tok.NewRow) {
                i++
                rows.add(cells.toList())
                cells = ArrayList()
            } else {
                if (t is Tok.End) i++
                break
            }
        }
        // 收尾: \end 之前的最后一格; 已经因为换行结算过空尾行时不再补一行
        if (cells.isNotEmpty() && !(rows.isNotEmpty() && cells.size == 1 && cells[0].isEmpty())) {
            rows.add(cells.toList())
        }
        return Node.Matrix(env, rows)
    }

    private fun charNode(c: Char): Node = when {
        c.isLetter() -> Node.Text(c.toString(), TextKind.ITALIC)
        else -> Node.Text(c.toString(), TextKind.ROMAN)
    }
}

// ---------------------------------------------------------------------------------------------
// 排版
// ---------------------------------------------------------------------------------------------

/**
 * 排版器。文字测量由 [textBoxOf] 注入: 生产路径走 TextMeasurer 实测, JVM 单测换成假测量器,
 * 这样 parse/layout 在没有 Android 字体与 graphic 的环境里也能跑。
 */
internal class LatexLayout(
    private val color: Color,
    private val base: Float,
    private val display: Boolean,
    private val textBoxOf: (text: String, italic: Boolean, sizePx: Float) -> MathBox,
) {
    fun layout(latex: String): MathBox {
        val nodes = LatexParser(tokenize(latex)).parse()
        if (nodes.isEmpty()) return EMPTY_BOX
        return row(nodes.map { node(it, base) })
    }

    fun plain(latex: String): MathBox = textBox(latex, italic = false, sizePx = base)

    private fun textBox(s: String, italic: Boolean, sizePx: Float): MathBox = textBoxOf(s, italic, sizePx)

    private fun nodeSeq(nodes: List<Node>?, sizePx: Float): MathBox {
        if (nodes.isNullOrEmpty()) return EMPTY_BOX
        return row(nodes.map { node(it, sizePx) })
    }

    private fun node(n: Node, sizePx: Float): MathBox = when (n) {
        is Node.Text -> textBox(n.s, n.kind == TextKind.ITALIC, sizePx)
        is Node.Space -> MathBox(max(0f, n.em * sizePx), 0f, 0f) { _, _ -> }
        is Node.Group -> row(n.body.map { node(it, sizePx) })
        is Node.Fraction -> fraction(
            nodeSeq(n.num, sizePx * 0.95f),
            nodeSeq(n.den, sizePx * 0.95f),
            sizePx,
        )
        is Node.Sqrt -> sqrt(nodeSeq(n.body, sizePx), sizePx)
        is Node.Delim -> delimited(
            n.open,
            n.close,
            nodeSeq(n.body, sizePx),
            sizePx,
        )
        is Node.BigOp -> textBox(n.sym, italic = false, sizePx = sizePx * (if (display) 1.3f else 1.18f))
        is Node.Matrix -> {
            val body = matrixBox(n.rows, sizePx)
            val d = MATRIX_DELIMS[n.env]
            if (d == null) body else delimited(d.first, d.second, body, sizePx)
        }
        is Node.Script -> {
            val sub = nodeSeq(n.sub, sizePx * 0.7f)
            val sup = nodeSeq(n.sup, sizePx * 0.7f)
            if (n.base is Node.BigOp && display) {
                bigOpLimits(node(n.base, sizePx), sub, sup, sizePx)
            } else {
                script(node(n.base, sizePx), sub, sup, sizePx)
            }
        }
    }

    /** 水平拼接, 基线对齐。 */
    private fun row(items: List<MathBox>): MathBox {
        val vis = items.filter { it.width > 0f && it.height >= 0f && it !== EMPTY_BOX }
        if (vis.isEmpty()) return EMPTY_BOX
        var w = 0f
        var bl = 0f
        var below = 0f
        for (b in vis) {
            w += b.width
            bl = max(bl, b.baseline)
            below = max(below, b.height - b.baseline)
        }
        val h = bl + below
        if (h <= 0f) return EMPTY_BOX
        return MathBox(w, h, bl) { x, y ->
            var cx = x
            for (b in vis) {
                b.draw(this, cx, y + (bl - b.baseline))
                cx += b.width
            }
        }
    }

    private fun fraction(num: MathBox, den: MathBox, sizePx: Float): MathBox {
        val axis = sizePx * 0.24f
        val gap = sizePx * 0.08f
        val rule = max(1f, sizePx * 0.05f)
        val pad = sizePx * 0.18f
        val w = max(num.width, den.width) + pad * 2f
        val numBottomRel = -(axis + gap + rule / 2f)
        val denTopRel = -(axis - gap - rule / 2f)
        val topRel = numBottomRel - num.height
        val bottomRel = denTopRel + den.height
        val h = bottomRel - topRel
        val bl = -topRel
        return MathBox(w, h, bl) { ox, oy ->
            if (num.height > 0f) {
                num.draw(this, ox + (w - num.width) / 2f, oy + (numBottomRel - num.height - topRel))
            }
            if (den.height > 0f) {
                den.draw(this, ox + (w - den.width) / 2f, oy + (denTopRel - topRel))
            }
            val barY = oy + (-axis - topRel)
            drawLine(
                color = color,
                start = Offset(ox + pad * 0.4f, barY),
                end = Offset(ox + w - pad * 0.4f, barY),
                strokeWidth = rule,
            )
        }
    }

    private fun sqrt(body: MathBox, sizePx: Float): MathBox {
        if (body.height <= 0f) return EMPTY_BOX
        val stroke = max(1f, sizePx * 0.06f)
        val hookW = sizePx * 0.55f
        val pad = sizePx * 0.08f
        val barGap = sizePx * 0.1f
        val w = hookW + body.width + pad
        val barYRel = -body.baseline - barGap
        val topRel = barYRel - stroke
        val bottomRel = body.height - body.baseline
        val h = bottomRel - topRel
        val bl = -topRel
        return MathBox(w, h, bl) { ox, oy ->
            body.draw(this, ox + hookW, oy + (-body.baseline - topRel))
            val barY = oy + (barYRel - topRel)
            val baselineY = oy + bl
            val p = Path().apply {
                moveTo(ox, baselineY - sizePx * 0.2f)
                lineTo(ox + hookW * 0.42f, baselineY - sizePx * 0.02f)
                lineTo(ox + hookW * 0.86f, barY)
                lineTo(ox + w, barY)
            }
            drawPath(p, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
        }
    }

    private fun script(b: MathBox, sub: MathBox, sup: MathBox, sizePx: Float): MathBox {
        val subShift = sizePx * 0.22f
        val supRise = sizePx * 0.42f
        val hasSup = sup.height > 0f
        val hasSub = sub.height > 0f
        val supTopRel = if (hasSup) -(supRise + sup.baseline) else 0f
        val subTopRel = if (hasSub) subShift - sub.baseline else 0f
        var topRel = -b.baseline
        var bottomRel = b.height - b.baseline
        if (hasSup) topRel = min(topRel, supTopRel)
        if (hasSub) bottomRel = max(bottomRel, subTopRel + sub.height)
        val w = b.width + max(sup.width, sub.width)
        val h = bottomRel - topRel
        val bl = -topRel
        return MathBox(w, h, bl) { ox, oy ->
            b.draw(this, ox, oy + (-b.baseline - topRel))
            if (hasSup) sup.draw(this, ox + b.width, oy + (supTopRel - topRel))
            if (hasSub) sub.draw(this, ox + b.width, oy + (subTopRel - topRel))
        }
    }

    /** display 模式下 \sum / \int 的上下限放到符号正上/正下方并居中。 */
    private fun bigOpLimits(op: MathBox, sub: MathBox, sup: MathBox, sizePx: Float): MathBox {
        val gap = sizePx * 0.06f
        val hasSup = sup.height > 0f
        val hasSub = sub.height > 0f
        val supBottomRel = -op.baseline - gap
        val supTopRel = if (hasSup) supBottomRel - sup.height else 0f
        val subTopRel = op.height - op.baseline + gap
        var topRel = -op.baseline
        var bottomRel = op.height - op.baseline
        if (hasSup) topRel = min(topRel, supTopRel)
        if (hasSub) bottomRel = max(bottomRel, subTopRel + sub.height)
        val w = max(op.width, max(sup.width, sub.width))
        val h = bottomRel - topRel
        val bl = -topRel
        return MathBox(w, h, bl) { ox, oy ->
            op.draw(this, ox + (w - op.width) / 2f, oy + (-op.baseline - topRel))
            if (hasSup) sup.draw(this, ox + (w - sup.width) / 2f, oy + (supTopRel - topRel))
            if (hasSub) sub.draw(this, ox + (w - sub.width) / 2f, oy + (subTopRel - topRel))
        }
    }

    /**
     * 矩阵排版: 每列取该列最大宽度、格子在列内水平居中; 每行按基线对齐后自上而下留 rowGap;
     * 列间留 colGap。括号不在这一层画, 由 node(Node.Matrix) 用 delimited() 包一层。
     */
    private fun matrixBox(rows: List<List<List<Node>>>, sizePx: Float): MathBox {
        if (rows.isEmpty()) return EMPTY_BOX
        val colGap = sizePx * 0.55f
        val rowGap = sizePx * 0.32f
        val cols = rows.maxOf { it.size }
        if (cols == 0) return EMPTY_BOX
        val cells: List<List<MathBox>> = rows.map { r -> (0 until cols).map { j -> nodeSeq(r.getOrNull(j), sizePx) } }
        val colW = FloatArray(cols)
        for (r in cells) for (j in 0 until cols) colW[j] = max(colW[j], r[j].width)
        val rowH = FloatArray(cells.size)
        val rowBl = FloatArray(cells.size)
        for (k in cells.indices) {
            for (c in cells[k]) {
                rowH[k] = max(rowH[k], c.height)
                rowBl[k] = max(rowBl[k], c.baseline)
            }
        }
        var w = colGap * (cols - 1)
        for (j in 0 until cols) w += colW[j]
        var h = -rowGap
        for (k in cells.indices) h += rowH[k] + rowGap
        if (w <= 0f || h <= 0f) return EMPTY_BOX
        val rowTop = FloatArray(cells.size)
        var y = 0f
        for (k in cells.indices) {
            rowTop[k] = y
            y += rowH[k] + rowGap
        }
        return MathBox(w, h, rowBl[0]) { ox, oy ->
            var x = ox
            for (j in 0 until cols) {
                for (k in cells.indices) {
                    val c = cells[k][j]
                    if (c.width > 0f && c.height > 0f) {
                        c.draw(this, x + (colW[j] - c.width) / 2f, oy + rowTop[k] + (rowBl[k] - c.baseline))
                    }
                }
                x += colW[j] + colGap
            }
        }
    }

    /** \left( ... \right) : 括号按内容高度放大并垂直居中。 */
    private fun delimited(open: String, close: String, body: MathBox, sizePx: Float): MathBox {
        if (body.height <= 0f) {
            return row(listOf(textBox(open, false, sizePx), textBox(close, false, sizePx)))
        }
        val target = body.height * 0.95f
        val sizePx2 = min(max(sizePx, target), sizePx * 3f)
        val o = textBox(open, false, sizePx2)
        val c = textBox(close, false, sizePx2)
        val centerRel = body.height / 2f - body.baseline
        return row(listOf(centerOn(o, centerRel), body, centerOn(c, centerRel)))
    }

    /** 把盒子改成"基线 = 自身垂直中心 - 目标中心偏移", 这样在 row 的基线对齐里表现为垂直居中。 */
    private fun centerOn(b: MathBox, targetCenterRel: Float): MathBox {
        if (b.height <= 0f) return b
        return MathBox(b.width, b.height, b.height / 2f - targetCenterRel) { x, y -> b.draw(this, x, y) }
    }
}

/** 生产路径的文字测量: 用 Compose TextMeasurer 实测宽高基线, 绘制时 drawText。 */
private fun measuredTextBox(
    measurer: TextMeasurer,
    density: Density,
    color: Color,
    s: String,
    italic: Boolean,
    sizePx: Float,
): MathBox {
    if (s.isEmpty() || sizePx <= 0f) return EMPTY_BOX
    val style = TextStyle(
        color = color,
        fontSize = with(density) { sizePx.toSp() },
        fontFamily = FontFamily.Serif,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
    val lr = measurer.measure(AnnotatedString(s), style)
    val w = lr.size.width.toFloat()
    val h = lr.size.height.toFloat()
    if (w <= 0f || h <= 0f) return EMPTY_BOX
    val bl = lr.firstBaseline
    return MathBox(w, h, bl) { x, y -> drawText(lr, topLeft = Offset(x, y)) }
}

/** 生产用排版器: 绑定 TextMeasurer。 */
internal fun latexLayout(
    measurer: TextMeasurer,
    density: Density,
    color: Color,
    basePx: Float,
    display: Boolean,
): LatexLayout = LatexLayout(color, basePx, display) { s, italic, sizePx ->
    measuredTextBox(measurer, density, color, s, italic, sizePx)
}

// ---------------------------------------------------------------------------------------------
// 缓存 (LRU 32)
// ---------------------------------------------------------------------------------------------

/**
 * 行内公式的占位尺寸（px）。InlineTextContent 必须在排版前给出固定宽高，
 * 用字符数估算会让正文出现一大段空白，所以这里复用真正的排版结果。
 */
internal fun mathInlineSizePx(
    measurer: TextMeasurer,
    density: Density,
    latex: String,
    basePx: Float,
): Pair<Float, Float> {
    val key = latex + '|' + basePx
    return MathSizeCache.get(key) {
        try {
            val box = latexLayout(measurer, density, Color.Black, basePx, false).layout(latex)
            val cap = basePx * 2.6f
            val vScale = if (box.height > cap) cap / box.height else 1f
            Pair(
                ceil(box.width).coerceAtLeast(1f),
                ceil(box.height * vScale).coerceAtLeast(1f),
            )
        } catch (t: Throwable) {
            // 测量失败也不能让正文崩：退化成字符数估算。
            Pair(basePx * 0.62f * latex.length.coerceIn(1, 40), basePx * 1.5f)
        }
    }
}

private object MathSizeCache {
    private const val MAX = 64
    private val map = object : LinkedHashMap<String, Pair<Float, Float>>(MAX + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Float, Float>>?): Boolean =
            size > MAX
    }

    fun get(key: String, build: () -> Pair<Float, Float>): Pair<Float, Float> {
        synchronized(map) { map[key] }?.let { return it }
        val v = build()
        synchronized(map) { map[key] = v }
        return v
    }
}

private object MathBoxCache {
    private const val MAX = 32
    private val map = object : LinkedHashMap<String, MathBox>(MAX + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MathBox>?): Boolean = size > MAX
    }

    fun get(key: String, build: () -> MathBox): MathBox {
        synchronized(map) { map[key] }?.let { return it }
        val v = build()
        synchronized(map) { map[key] = v }
        return v
    }
}
