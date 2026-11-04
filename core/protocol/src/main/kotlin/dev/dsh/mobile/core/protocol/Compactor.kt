package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.protocol.context.WarmRefreshReport

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

/**
 * 「立即刷新温层」的可选能力（T9.8-S2）。
 *
 * 与 [Compactor] 同一形态：不动 [DshTransport]，只有本地引擎能生成温层（要历史 rowid、
 * 要调模型、要写 warm_layers），调用方写 `(transport as? WarmRefresher)?.refreshWarm(sessionId)`。
 * 自动触发（≥40 条新消息）在 Compaction.maybeSummarize 里，这里只服务 /warm 手动命令。
 */
interface WarmRefresher {
    /** 手动刷新一次温层。报告里的 layer 为 null = 没刷（已无新消息 / 解析空 / 模型失败）。 */
    suspend fun refreshWarm(sessionId: String): WarmRefreshReport
}
/**
 * 一次手动回捞的结果（T9.8-S4）。
 *
 * [message] 是给人看的一句实话（≤120 字，UI 直接显示，不含原文）；
 * [text] 是下一轮装配要插的块本身，null = 没命中，UI 与提示词两边都不变。
 */
data class RecallReport(
    /** 命中条数。 */
    val hits: Int = 0,
    /** 命中里最旧的 rowid（0 = 没有）。 */
    val fromRowId: Long = 0,
    /** 命中里最新的 rowid（0 = 没有）。 */
    val toRowId: Long = 0,
    /** 命中清单（"rowid 120 · user"），给 UI 折叠面板用，不含正文。 */
    val rows: List<String> = emptyList(),
    /** 一句可展示的结论。 */
    val message: String = "",
    /** 可注入的回捞块；null = 没命中。 */
    val text: String? = null,
)

/**
 * 「从冷层回捞历史」的可选能力（T9.8-S4）。
 *
 * 与 [Compactor] / [WarmRefresher] 同一形态：不动 [DshTransport]，只有本地引擎能查 messages 表
 * （要 rowid、要按会话 LIKE），调用方写 (transport as? RecallLookup)?.recall(sessionId, q)。
 */
interface RecallLookup {
    /**
     * 手动回捞：[query] 是词（`/recall 温层`）或轮次标记（`/recall R2` = 第 2 次压缩的区间）。
     *
     * 命中块会留给下一轮装配（作为提示词的一部分），原文不写回 messages、也不倒进 UI。
     */
    suspend fun recall(sessionId: String, query: String): RecallReport
}
