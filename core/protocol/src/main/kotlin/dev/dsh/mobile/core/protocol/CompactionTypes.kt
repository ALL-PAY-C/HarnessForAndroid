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
