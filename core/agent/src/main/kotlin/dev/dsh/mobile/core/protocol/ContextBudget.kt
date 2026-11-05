package dev.dsh.mobile.core.protocol

/**
 * 上下文预算的唯一口径：模型窗口 → 压缩预算 → 装配字符数。
 *
 * 以前比这乱：压缩用 `AgentThreads` 的 budgetChars=8000，顶栏用 [contextWindowOf]（默认 32k），
 * 同一会话两个数——回执写「预算 6000 tokens」，顶栏写「/ 32k」，于是压缩永远判不触发。
 * 现在一律从窗口推：预算 = 窗口 * 0.8（留 20% 给回答），字符 = token * 4。
 */
object ContextBudget {
    /** 估算口径：4 字符 ≈ 1 token（与:node 侧 tool-state.js 的 CHARS_PER_TOKEN 一致）。 */
    const val CHARS_PER_TOKEN = 4

    /** 模型窗口未知时的兜底：与 ModelStore.contextWindowOf 的「不认识就 32k」保持一致。 */
    const val DEFAULT_WINDOW_TOKENS = 32_000

    /** 压缩预算占窗口的比例；剩下的 20% 留给这一轮的回答。 */
    const val COMPACT_RATIO = 0.8f

    /** 窗口为 0/负数时退回默认窗口，避免预算算成 0 导致「永远超预算」。 */
    fun windowOr(windowTokens: Int): Int = if (windowTokens > 0) windowTokens else DEFAULT_WINDOW_TOKENS

    /** 压缩预算（tokens）：默认 32k → 25600。 */
    fun budgetTokens(windowTokens: Int = DEFAULT_WINDOW_TOKENS): Int =
        (windowOr(windowTokens) * COMPACT_RATIO).toInt()

    /** 压缩预算换算成 [AgentThreads] 装配用的字符数：25600 tokens → 102400 字符。 */
    fun budgetChars(windowTokens: Int = DEFAULT_WINDOW_TOKENS): Int = budgetTokens(windowTokens) * CHARS_PER_TOKEN

    /** 字符 → token（向上取整，宁可早压也不让请求被上游截断）。 */
    fun tokensOf(chars: Int): Int = if (chars <= 0) 0 else (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    /** token → 字符。与 [tokensOf] 同一常数，两个方向不会互相打架。 */
    fun charsOf(tokens: Int): Int = if (tokens <= 0) 0 else tokens * CHARS_PER_TOKEN
}
