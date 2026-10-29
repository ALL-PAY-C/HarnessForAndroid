package dev.dsh.mobile.core.common

/**
 * 一条模型回复里拆出来的一个可选动作。
 *
 * id 用字母（A/B/C）当点击态；label 是气泡/通知按钮上的角标；value 是真正发回去的文本。
 * 放在 core 而不是聊天页，是因为通知栏也要能把同一批选项原样挂成按钮——
 * 两处必须用同一个解析器，否则「界面里有卡片、通知里没按钮」。
 */
data class DshOption(
    val id: String,
    val label: String,
    val value: String,
)

/**
 * 模型给的显式选项块（系统提示里约定的格式）。
 *
 * 模型常把括号写成半截：`[[OPTIONS]` / `[[/OPTIONS]` / `[OPTIONS]`。
 * 只认严丝合缝的 `[[OPTIONS]]…[[/OPTIONS]]` 会整块漏掉，选项掉进正文，
 * 残留的 `]` 就成了气泡后面那条孤零零的竖线。
 */
private val OPTION_BLOCK = Regex(
    """\[\[?\s*OPTIONS\s*]\]{1,2}([\s\S]*?)\[\[?\s*/\s*OPTIONS\s*]\]{1,2}""",
    RegexOption.IGNORE_CASE,
)

/** 选项块被吃掉后留下的纯括号残行（`]`、`[[`、`【`…）。 */
private val RESIDUE_LINE = Regex("""^[\s\[\]()（）【】{}<>]+$""")

/** 选项标记本身的各种残写。 */
private val RESIDUE_MARKS = listOf(
    "[[/OPTIONS]]", "[[/OPTIONS]", "[/OPTIONS]]", "[/OPTIONS]",
    "[[OPTIONS]]", "[[OPTIONS]", "[[OPTIONS", "[[/OPTIONS",
)

/**
 * 清掉选项块留下的括号残渣。
 *
 * 只删「整行都是括号」的行：正文里正常出现的 `]`（数组下标、引用记号）和别的
 * 字在同一行，不会被误删。
 */
fun stripOptionResidue(text: String): String {
    if (text.isEmpty()) return text
    var out = text
    for (mark in RESIDUE_MARKS) out = out.replace(mark, "")
    val kept = out.lines().filterNot { RESIDUE_LINE.matches(it.trim()) }
    return kept.joinToString("\n").trimEnd()
}

/** 行首的选项标记：A. / A、/ A) / A: / 1. / （A）/ [A] */
private val OPTION_MARK = Regex("""^\s*[\[\(（]?\s*([A-Za-z0-9]{1,2})\s*[\]\)）]?\s*[\.．、:：\)]\s*(.*)$""")

/** 一句话里塞了整排选项的分隔点。 */
private val INLINE_MARK = Regex("""[\s；;]\s*(?=[A-Ha-h][\.．、:：\)]\s*\S)""")

/** 句子尾巴上的引导语：「请选择下一步：」这类，拆出选项后不该留在正文里。 */
private val LEAD_IN = listOf(
    "请选择", "选择下一步", "选一个", "你想要", "要不要", "是否需要", "下一步做", "告诉我要",
    "你希望", "确认一下", "可以直接说", "选项",
)

private fun isJunkLine(line: String): Boolean {
    val t = line.trim()
    if (t.isEmpty()) return true
    if (t == "]" || t == "[" || t == "]]" || t == "[[" || t == "】" || t == "【") return true
    if (t == "`@" || t.startsWith("```")) return false
    if (t.all { it == '-' } || t.all { it == '=' }) return true
    return false
}

/** 去掉行里常见的噪声前缀/后缀（模型偶尔把 ] 或者反引号带进来）。 */
private fun normalizeOptionLine(line: String): String {
    var t = line.trim()
    t = t.trimStart(']', '[', '）', ')', ' ')
    t = t.trimEnd(']', '[', '（', '(', ' ')
    return t
}

private fun parseOptionLine(line: String): DshOption? {
    val t = normalizeOptionLine(line)
    if (t.isEmpty()) return null
    val m = OPTION_MARK.find(t) ?: return null
    val label = m.groupValues[1].trim()
    var value = m.groupValues[2].trim()
    value = value.trimEnd(']', ' ')
    if (label.isEmpty() || value.isEmpty()) return null
    // 「A:」后面只有标点的不算选项。
    if (value.all { it in "./。、,，;；:：）) " }) return null
    return DshOption(id = label.uppercase(), label = label.uppercase(), value = value)
}

/** 一行里塞了整排选项时展开成多行（真机常见）。 */
private fun explodeInlineOptions(line: String): List<String> {
    val t = normalizeOptionLine(line)
    if (t.isEmpty()) return emptyList()
    val parts = t.split(INLINE_MARK).filter { it.isNotBlank() }
    if (parts.size < 2) return emptyList()
    // 只有每一段都长得像选项才展开，避免把普通句子切碎。
    if (parts.any { parseOptionLine(it) == null }) return emptyList()
    return parts
}

/**
 * 把模型回复里的选项拆出来。
 *
 * 两种来源：显式 [[OPTIONS]] 块（系统提示里约定的），以及正文尾巴上的 A/B/C 行。
 * 返回 (正文, 选项)；选项为空时正文原样返回（仅清括号残渣），一个字都不动。
 */
fun splitOptions(text: String): Pair<String, List<DshOption>> {
    if (text.isBlank()) return text to emptyList()

    // 1) 显式选项块优先。
    val block = OPTION_BLOCK.find(text)
    if (block != null) {
        val options = block.groupValues.getOrNull(1).orEmpty()
            .lines()
            .mapNotNull { parseOptionLine(it) }
            .take(8)
        val clean = stripOptionResidue(text.replace(block.value, ""))
        if (options.isNotEmpty()) return clean to options
    }

    // 2) 尾巴上的 A/B/C 行：从下往上扫，遇到正文就停。
    val lines = text.lines()
    val tail = ArrayList<DshOption>()
    var cut = lines.size
    var i = lines.size - 1
    while (i >= 0) {
        val raw = lines[i]
        // 空行/括号残行也在选项块里面：cut 必须跟着一起上移，
        // 否则这些行会被留在正文尾部（以前那条孤立 `]` 就是这么来的）。
        if (isJunkLine(raw)) {
            if (tail.isNotEmpty()) cut = i
            i--
            continue
        }
        val expanded = explodeInlineOptions(raw)
        if (expanded.isNotEmpty()) {
            val parsed = expanded.mapNotNull { parseOptionLine(it) }
            if (parsed.isEmpty()) break
            tail.addAll(0, parsed)
            cut = i
            i--
            continue
        }
        val one = parseOptionLine(raw)
        if (one != null) {
            tail.add(0, one)
            cut = i
            i--
            continue
        }
        break
    }
    // 只有一个「A:」行时不当作选项列表：正文里出现单行冒号太常见了。
    // 但括号残渣仍要清：`[[OPTIONS]]` 半残块解析不出选项时，正文照样不该留 `]`。
    if (tail.size < 2 || tail.size > 8 || cut <= 0) return stripOptionResidue(text) to emptyList()

    // 3) 去掉尾巴上的引导语行（「请选择下一步：」），它已经由卡片本身表达了。
    var end = cut
    while (end > 0) {
        val prev = lines[end - 1].trim()
        if (prev.isEmpty()) {
            end--
            continue
        }
        val leadIn = prev.endsWith(":") || prev.endsWith("：") ||
            LEAD_IN.any { prev.contains(it) }
        if (!leadIn) break
        end--
    }
    val head = stripOptionResidue(lines.subList(0, end).joinToString("\n"))
    return head to tail
}
