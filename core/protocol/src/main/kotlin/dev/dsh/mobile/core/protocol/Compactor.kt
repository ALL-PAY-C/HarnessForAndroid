package dev.dsh.mobile.core.protocol

/**
 * 一次压缩的结果：是否真的压了、摘要多长、前后 token 粗估（字符数 / 4）。
 *
 * [usedTokens] / [budgetTokens] / [windowTokens] 与顶栏同一口径：窗口来自模型的 contextWindow，
 * 预算 = 窗口 * [ContextBudget.COMPACT_RATIO]，已用优先取上游上报的 prompt+completion（与 UI 一致）。
 */
data class CompactResult(
    val compacted: Boolean,
    val summaryChars: Int = 0,
    val beforeTokens: Int = 0,
    val afterTokens: Int = 0,
    val droppedMessages: Int = 0,
    /** 给人看的一句话（UI 直接显示，不再自己拼文案）。 */
    val message: String = "",
    /** 压缩前的上下文占用（tokens）——与顶栏「已用」同一个来源。 */
    val usedTokens: Int = 0,
    /** 压缩预算（tokens）= 窗口 * 0.8。 */
    val budgetTokens: Int = 0,
    /** 模型上下文窗口（tokens），与顶栏分母同一个数。 */
    val windowTokens: Int = 0,
)

/**
 * 「立即压缩上下文」的可选能力。
 *
 * 不动 [DshTransport] 接口：只有本地引擎自己装配上下文、自己调模型，才能真压缩；
 * 调用方写 `(transport as? Compactor)?.compact(sessionId)` —— 不支持的传输层自然没有入口，
 * 也不用给 AcpTransport 之类塞一个永远抛异常的空实现。
 */
interface Compactor {
    /**
     * 立即压缩该会话的早期历史。
     *
     * [budgetTokens] 调用方（UI）已经知道预算时传进来，保证回执与顶栏同一口径；
     * 传 0 表示由传输层按模型窗口自己算（默认路径，见 [ContextBudget]）。
     */
    suspend fun compact(sessionId: String, budgetTokens: Int = 0): CompactResult
}
