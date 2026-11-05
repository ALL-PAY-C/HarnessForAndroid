package dev.dsh.mobile.core.protocol.llm

import dev.dsh.mobile.core.protocol.ContextBudget
import java.util.concurrent.ConcurrentHashMap

/** 告急线：到这里落一条通知，但还允许继续派。 */
internal const val SUBAGENT_WARN_PERCENT = 80

/** 拒绝线：到这里不再开新子任务。 */
internal const val SUBAGENT_REJECT_PERCENT = 100

/** 一次「能不能派」的判定结果。 */
internal data class BudgetVerdict(
    /** false = 拒绝派发，原因在 [reason]。 */
    val allowed: Boolean,
    /** 父会话预算已用百分比（可能 >100）。 */
    val percent: Int,
    val usedTokens: Int,
    val budgetTokens: Int,
    /** 这次子任务能分到的上下文字符数（父预算按并发数摊）。 */
    val shareChars: Int,
    /** 刚跨过 [SUBAGENT_WARN_PERCENT]：调用方负责落一条 [预算告急] 通知（同一会话只报一次）。 */
    val warn: Boolean,
    val reason: String = "",
)

/**
 * 子代理的预算闸门（T5.3）。
 *
 * 用量不另起一份计数器：两个 lambda 直接读既有记账（传输层的 lastUsageTokens / projections），
 * 预算取父会话（群聊取房间）的 contextBudget。这里只管三件事：百分比、并发分摊、告急去重。
 */
internal class SubagentBudget(
    /** 该会话已用 token（复用 lastUsageTokens）。 */
    private val usedTokensOf: suspend (String) -> Long,
    /** 该会话的预算 token（复用 contextBudget / 房间预算）。 */
    private val budgetTokensOf: suspend (String) -> Int,
) {
    /** 会话 -> 正在跑的子代理数（分摊分母）。 */
    private val inFlight = ConcurrentHashMap<String, Int>()

    /** 已经报过告急的会话，避免每个子任务都刷一条。 */
    private val warned = ConcurrentHashMap.newKeySet<String>()

    /** 派发前判定：允许时已经把这次算进并发数，调用方跑完必须调 [end]。 */
    suspend fun begin(sessionId: String): BudgetVerdict {
        val budget = budgetTokensOf(sessionId).coerceAtLeast(1)
        val used = usedTokensOf(sessionId).coerceAtLeast(0L)
        val percent = ((used * 100) / budget).toInt()
        if (percent < SUBAGENT_WARN_PERCENT) warned.remove(sessionId)
        if (percent >= SUBAGENT_REJECT_PERCENT) {
            return BudgetVerdict(
                allowed = false,
                percent = percent,
                usedTokens = used.toInt(),
                budgetTokens = budget,
                shareChars = 0,
                warn = false,
                reason = "子任务预算已用完（已用 " + percent + "%，预算 " + budget + " tokens），这次派发被拒；" +
                    "等上下文压缩、开新会话，或把活并进当前这一轮做。",
            )
        }
        val concurrent = inFlight.merge(sessionId, 1, Int::plus) ?: 1
        return BudgetVerdict(
            allowed = true,
            percent = percent,
            usedTokens = used.toInt(),
            budgetTokens = budget,
            shareChars = (ContextBudget.charsOf(budget) / concurrent.coerceAtLeast(1)).coerceAtLeast(2_000),
            warn = percent >= SUBAGENT_WARN_PERCENT && warned.add(sessionId),
        )
    }

    /** 子代理跑完（成功、失败、被拒后回滚都要走到）。 */
    fun end(sessionId: String) {
        inFlight.computeIfPresent(sessionId) { _, n -> (n - 1).takeIf { it > 0 } }
    }

    /** 会话取消/关闭时清干净。 */
    fun clear(sessionId: String) {
        inFlight.remove(sessionId)
        warned.remove(sessionId)
    }
}
