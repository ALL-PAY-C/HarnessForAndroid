package dev.dsh.mobile.core.protocol

/**
 * 流式正文里的思维链分流器。
 *
 * 上游有多种表达方式：DeepSeek 走 `reasoning_content` 字段，另一些模型把推理直接写进
 * 正文并用 `<｜begin▁of▁thinking｜>…<｜end▁of▁thinking｜>` 或 ` thinking…</thinking>` 包起来。
 * 字段那一路在 [reasoningOf] 里取；这里负责正文里混着的那一路，
 * 而且要能处理「标签被切在两个 chunk 之间」——所以必须留住末尾可能不完整的标签前缀。
 */
internal val THINK_OPEN: List<String> = listOf(" thinking", "<thinking>", "<｜begin▁of▁thinking｜>")

internal val THINK_CLOSE: List<String> = listOf("<｜end▁of▁thinking｜>", "</thinking>")

internal class ThinkSplitter(
    private val onThink: (String) -> Unit = {},
) {
    private val pending = StringBuilder()
    private val thought = StringBuilder()
    private val allThought = StringBuilder()
    private var inThink = false

    /** 目前已经剥出来的全部思维链文本。 */
    val thoughtText: String get() = allThought.toString()

    /** 喂一个 chunk，返回这一块里可安全展示的正文（可能有，也可能为空）。 */
    fun feed(chunk: String): String {
        pending.append(chunk)
        return drain(false)
    }

    /** 流结束：把留在 pending 里的尾巴一次性处理掉。 */
    fun finish(): String = drain(true)

    private fun drain(flush: Boolean): String {
        val out = StringBuilder()
        while (true) {
            val s = pending.toString()
            if (s.isEmpty()) return out.toString()
            if (!inThink) {
                val open = firstTag(s, THINK_OPEN)
                val close = firstTag(s, THINK_CLOSE)
                // 正文里先出现的是「结束」标记（历史里常有半截残留）：直接当思考结束处理。
                if (close != null && (open == null || close.first < open.first)) {
                    thought.append(s, 0, close.first)
                    pending.delete(0, close.first + close.second.length)
                    flushThought()
                    continue
                }
                if (open == null) {
                    val keep = if (flush) 0 else heldBack(s)
                    out.append(s, 0, s.length - keep)
                    pending.setLength(0)
                    if (keep > 0) pending.append(s, s.length - keep, s.length)
                    return out.toString()
                }
                out.append(s, 0, open.first)
                pending.delete(0, open.first + open.second.length)
                inThink = true
                continue
            }
            val close = firstTag(s, THINK_CLOSE)
            if (close == null) {
                if (!flush) return out.toString()
                thought.append(s)
                pending.setLength(0)
                flushThought()
                return out.toString()
            }
            thought.append(s, 0, close.first)
            pending.delete(0, close.first + close.second.length)
            inThink = false
            flushThought()
        }
    }

    private fun flushThought() {
        if (thought.isEmpty()) return
        val t = thought.toString()
        thought.setLength(0)
        allThought.append(t)
        onThink(t)
    }

    private fun firstTag(s: String, tags: List<String>): Pair<Int, String>? {
        var best: Pair<Int, String>? = null
        for (t in tags) {
            val i = s.indexOf(t)
            if (i < 0) continue
            if (best == null || i < best.first) best = i to t
        }
        return best
    }

    /** 末尾有多少字符可能是某个标签的前缀：这些先不发，等下一个 chunk 拼上再判断。 */
    private fun heldBack(s: String): Int {
        var best = 0
        outer@ for (t in THINK_OPEN + THINK_CLOSE) {
            val max = minOf(t.length - 1, s.length)
            var len = max
            while (len > best) {
                if (s.regionMatches(s.length - len, t, 0, len)) {
                    best = len
                    continue@outer
                }
                len--
            }
        }
        return best
    }
}
