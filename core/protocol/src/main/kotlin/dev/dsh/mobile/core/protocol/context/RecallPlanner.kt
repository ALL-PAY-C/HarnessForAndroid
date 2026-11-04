package dev.dsh.mobile.core.protocol.context

/**
 * 一条冷层回捞命中：messages.rowid + 角色 + 正文。
 *
 * [rowId] 是 messages 表的隐式行号（实体里的主键是 TEXT uuid），回捞块靠它标来源，
 * 也靠它排序与去重 —— 与温层水位同一个句柄。
 */
data class RecallHit(val rowId: Long, val role: String, val text: String)

/**
 * 冷层回捞（T9.8 S4）：从当前输入抽检索词、判定该不该查库、把命中渲染成一块可注入的 system 文本。
 *
 * 纯函数、无 IO：查库在 LocalEngineTransport（recallSearch / recallRows 注入），
 * 于是提示词形态能逐字断言，不用起 Room。两条硬约束：
 * 1) [needsRecall] 为假时一条都不查（历史没被截断就没必要回捞）；
 * 2) 回捞块与温层共用预算，吃紧时先砍回捞（[capFor] 收到 800），温层不动。
 */
internal object RecallPlanner {
    /** 回捞块首行前缀（完整的首行是 [recall R<n>]）。 */
    const val OPEN_PREFIX = "[recall R"

    /** 回捞块末行。 */
    const val CLOSE = "[/recall]"

    /** 回捞块默认字符上限。 */
    const val CAP_CHARS = 2_000

    /** 预算吃紧时的上限：先砍回捞，温层 LOCKED 不动。 */
    const val TIGHT_CAP_CHARS = 800

    /** 装配时最多查几个词（每多一个词多一次 LIKE）。 */
    const val MAX_TERMS = 3

    /** 每词最多取几条（SQL LIMIT）。 */
    const val PER_TERM_LIMIT = 5

    /** 合并去重后最多几条进块。 */
    const val MAX_HITS = 6

    /** 单条正文最多这么多字：回捞是线索不是原文回放。 */
    const val BODY_LIMIT = 400

    /** R<n> 区间丢了时的兜底窗口：只在最近这么多条里匹配。 */
    const val FALLBACK_WINDOW = 200

    /** 中文 2 字词里「全是虚词」的判定用（真词不会全由这些字组成）。 */
    private val STOP_CHARS = "的了是我你他她它在有和就都也这把那被给对吗呢吧啊呀着过很再又还".toSet()

    /** 停用词（整词命中即丢）。 */
    private val STOP_WORDS = setOf(
        "的", "了", "是", "我", "你", "他", "她", "它", "在", "有", "和", "就", "都", "也", "这", "那",
        "怎么", "为什么", "什么", "现在", "一下", "可以", "帮我", "请问", "哪里", "如何", "我们",
        "please", "thanks", "the", "and", "for", "this", "that", "what", "how", "why", "is", "are",
        "to", "of", "in", "on", "it", "you", "me", "we", "ok", "okay", "yes", "no", "hi",
    )

    /** 反引号拆出来的标识符。 */
    private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]{1,}")

    /** 行内代码：两个反引号之间、不跨行。 */
    private val INLINE_CODE = Regex("`([^`\\n]+)`")

    /** 代码块（整段）。 */
    private val FENCE = Regex("```[\\s\\S]*?```")

    /** 带 / 的路径片段。 */
    private val SLASH_PATH = Regex("[A-Za-z0-9_@~.-]*/[A-Za-z0-9_@~./-]*[A-Za-z0-9_@~.-]")

    /** 带已知扩展名的文件名。 */
    private val EXT_FILE = Regex(
        "[A-Za-z0-9_-]+\\.(kt|kts|java|ts|tsx|js|jsx|json|md|py|xml|gradle|sql|sh|ya?ml|toml)\\b",
    )

    /** 连续中文串。 */
    private val CJK_RUN = Regex("[\\u4e00-\\u9fa5]{2,}")

    /** 英文单词。 */
    private val WORD = Regex("[A-Za-z][A-Za-z0-9_]{1,}")

    private val ROUND = Regex("^[Rr](\\d{1,3})$")

    /**
     * 从当前用户输入抽检索词，按优先级：带路径/扩展名的片段 > 反引号与代码块里的标识符 >
     * 中文 2-4 字词组 > 英文单词。去重（英文按小写）、去停用词、每个词长 ≥2。
     *
     * 中文长串按「中心词在后」切：取尾部 4 字窗，避免把「为什么我」这种虚词串当词查。
     */
    fun keywords(userText: String, limit: Int = 6): List<String> {
        if (userText.isBlank() || limit <= 0) return emptyList()
        val kept = LinkedHashMap<String, String>()
        fun put(raw: String) {
            val term = raw.trim()
            if (term.length < 2 || isStop(term)) return
            val key = term.lowercase()
            if (kept.containsKey(key)) return
            // 已经被更长的词覆盖（按输入顺序，长的先来）就跳过，免得同一段既查长词又查碎片。
            if (kept.keys.any { it.contains(key) }) return
            kept[key] = term
        }

        SLASH_PATH.findAll(userText).forEach { put(it.value) }
        EXT_FILE.findAll(userText).forEach { put(it.value) }
        FENCE.findAll(userText).forEach { fence ->
            IDENT.findAll(fence.value).forEach { put(it.value) }
        }
        INLINE_CODE.findAll(userText).forEach { inline -> put(inline.groupValues[1]) }
        CJK_RUN.findAll(userText).forEach { run -> cjkTerms(run.value).forEach { put(it) } }
        WORD.findAll(userText).forEach { put(it.value) }
        return kept.values.take(limit)
    }

    /**
     * 该不该真去查库：历史被截断（[historyChars] > [budgetChars]）**且**抽到了检索词。
     *
     * 两个条件缺一不可 —— 否则每轮都要打一次 messages 表，回捞变成纯开销。
     * [historyChars] 传全量字符数（装配时就是 ContextAssembly.fullChars，> 预算即裁过历史）。
     */
    fun needsRecall(userText: String, historyChars: Int, budgetChars: Int): Boolean {
        if (budgetChars <= 0 || historyChars <= budgetChars) return false
        return keywords(userText).isNotEmpty()
    }

    /**
     * 命中 → 可注入文本：首行 [recall R<n>]，每条 - [rowid N] 角色: 正文，末行 [/recall]。
     *
     * [hits] 由调用方按 rowid 倒序（新→旧）给；总长超 [capChars] 时从表尾砍，也就是
     * 「从旧到新丢弃」，最新的线索留到最后。砍空或本来没命中都返回 null = 不插这条消息。
     */
    fun format(hits: List<RecallHit>, capChars: Int = CAP_CHARS, round: Int = 1): String? {
        if (hits.isEmpty()) return null
        val open = OPEN_PREFIX + round + "]"
        val lines = hits.mapTo(ArrayList()) { hit ->
            "- [rowid " + hit.rowId + "] " + hit.role + ": " + oneLine(hit.text)
        }
        while (lines.isNotEmpty() && blockChars(open, lines) > capChars) lines.removeAt(lines.lastIndex)
        if (lines.isEmpty()) return null
        return (listOf(open) + lines + CLOSE).joinToString("\n")
    }

    /** 预算判定：回捞吃满 2000 就会挤掉温层时收到 800（温层 LOCKED 不动）。 */
    fun capFor(usedChars: Int, budgetChars: Int): Int =
        if (budgetChars > 0 && usedChars + CAP_CHARS > budgetChars) TIGHT_CAP_CHARS else CAP_CHARS

    /** /recall R3 → 3；不是 R<n> 形状返回 null。 */
    fun roundOf(text: String): Int? =
        ROUND.matchEntire(text.trim())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    /** LIKE 参数字面化：DAO 的 ESCAPE '\' 会把这三个字符还原成字面量（与 MessageDao.search 同口径）。 */
    fun escapeLike(term: String): String = term
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    /** 内存匹配：R<n> 的区间丢了时，用词在最近若干条里过一遍（不碰 SQL）。 */
    fun match(rows: List<RecallHit>, terms: List<String>, limit: Int = MAX_HITS): List<RecallHit> {
        if (terms.isEmpty()) return emptyList()
        return rows.filter { row ->
            row.text.isNotBlank() && terms.any { row.text.contains(it, ignoreCase = true) }
        }.sortedByDescending { it.rowId }.take(limit)
    }

    private fun isStop(term: String): Boolean {
        val lower = term.lowercase()
        if (lower in STOP_WORDS) return true
        if (!isCjk(lower)) return false
        // 中文才按字判：整串都是虚词字（的了是）或裹着长停用词（为什么）都不是检索词。
        if (lower.all { it in STOP_CHARS }) return true
        return STOP_WORDS.any { it.length >= 2 && lower.contains(it) }
    }

    /** 是否整串都是汉字：按字判停用词只对中文成立，英文走整词表。 */
    private fun isCjk(s: String): Boolean = s.all { it.code in 0x4e00..0x9fa5 }

    /** 中文串的切分点：虚词与疑问词，连着切出来才不会把「为什么返回空」当词查。 */
    private val CJK_SPLIT = STOP_CHARS + "怎什为能会要想说做样些里下先再吗嘛".toSet()

    /** 中文串先按虚词切段，再对每段取尾部对齐的 4 字窗：实词在后，碎虚词串直接消失。 */
    private fun cjkTerms(run: String): List<String> {
        val out = ArrayList<String>(run.length / 2 + 1)
        val segment = StringBuilder()
        fun flush() {
            val s = segment.toString()
            segment.setLength(0)
            if (s.length < 2) return
            if (s.length <= 4) {
                out += s
                return
            }
            // 长段取尾部 4 字窗，最多两窗（越靠前的越可能只是修饰）。
            var end = s.length
            val tail = ArrayList<String>(2)
            while (tail.size < 2 && end >= 2) {
                val start = maxOf(0, end - 4)
                tail += s.substring(start, end)
                if (start == 0) break
                end = start
            }
            out += tail.reversed()
        }
        for (ch in run) {
            if (ch in CJK_SPLIT) flush() else segment.append(ch)
        }
        flush()
        return out
    }

    private fun oneLine(text: String): String {
        val flat = text.replace('\r', ' ').replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= BODY_LIMIT) flat else flat.take(BODY_LIMIT)
    }

    private fun blockChars(open: String, lines: List<String>): Int =
        open.length + 1 + lines.sumOf { it.length + 1 } + CLOSE.length
}