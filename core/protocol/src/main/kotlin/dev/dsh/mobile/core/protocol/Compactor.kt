package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.model.compaction.CompactResult
import dev.dsh.mobile.core.model.compaction.RecallReport
import dev.dsh.mobile.core.model.context.WarmRefreshReport

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
