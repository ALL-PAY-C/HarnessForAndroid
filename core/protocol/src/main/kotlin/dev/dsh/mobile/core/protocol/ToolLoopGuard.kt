package dev.dsh.mobile.core.protocol

/** 工具名 + 规范化参数：trim、合并空白。 */
data class ToolSignature(val name: String, val args: String) {
    val key: String get() = name + "\u0000" + args

    companion object {
        private val WHITESPACE = Regex("\\s+")

        fun of(name: String, rawArgs: String): ToolSignature =
            ToolSignature(name.trim().ifBlank { "shell" }, normalize(rawArgs))

        /** 空白差异不算新调用：同一条命令多打两个空格仍然命中同一个签名。 */
        fun normalize(raw: String): String = raw.trim().replace(WHITESPACE, " ")
    }
}

/** [ToolLoopGuard] 对一次工具调用的裁决。 */
sealed interface ToolVerdict {
    /** 首次出现，正常执行。 */
    data class Execute(val occurrence: Int) : ToolVerdict

    /** 本轮内重复：不执行，复用第 [occurrence] 次的结果。 */
    data class Reuse(val occurrence: Int, val cachedOutput: String, val detail: String) : ToolVerdict

    /** 死循环：该次工具不再执行，本轮应当结束。 */
    data class Loop(val signature: ToolSignature, val count: Int, val detail: String) : ToolVerdict
}

/**
 * 工具调用防抖 + 死循环检测。
 *
 * - 60 秒滑动窗口内同一签名出现次数 > 3 判死循环；
 * - 同一轮内完全相同的签名去抖：不重复执行，复用上一次结果。
 *
 * 窗口跨轮保留（跨轮死循环也要拦），本轮去抖状态在 [beginTurn] 清空。
 */
class ToolLoopGuard(
    private val windowMs: Long = 60_000L,
    private val maxRepeats: Int = 3,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val window = ArrayDeque<Pair<String, Long>>()
    private val turnHits = HashMap<String, Int>()
    private val turnResults = HashMap<String, String>()

    /** 新一轮开始：清空本轮去抖状态，滑动窗口保留。 */
    fun beginTurn() {
        turnHits.clear()
        turnResults.clear()
    }

    fun check(signature: ToolSignature): ToolVerdict {
        val now = clock()
        prune(now)
        val key = signature.key
        val count = window.count { it.first == key } + 1
        if (count > maxRepeats) {
            return ToolVerdict.Loop(
                signature = signature,
                count = count,
                detail = "检测到重复调用：${signature.name} ${signature.args.take(160)} 在 ${windowMs / 1000} 秒内出现 $count 次，已结束本轮",
            )
        }
        window.addLast(key to now)
        val occurrence = (turnHits[key] ?: 0) + 1
        turnHits[key] = occurrence
        val cached = turnResults[key]
        if (cached != null && occurrence > 1) {
            return ToolVerdict.Reuse(
                occurrence = occurrence,
                cachedOutput = cached,
                detail = "复用第 $occurrence 次结果：${signature.name} ${signature.args.take(120)}",
            )
        }
        return ToolVerdict.Execute(occurrence)
    }

    /** 记录本轮该签名的执行结果，供后续重复调用复用。 */
    fun recordResult(signature: ToolSignature, output: String) {
        turnResults[signature.key] = output
    }

    private fun prune(now: Long) {
        while (window.isNotEmpty() && now - window.first().second > windowMs) window.removeFirst()
    }
}
