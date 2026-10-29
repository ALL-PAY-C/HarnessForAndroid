package dev.dsh.mobile.core.data.repo

import java.util.concurrent.ConcurrentHashMap

/**
 * 终态屏障（N-1 / m00611①）：上一轮「终态尚未确认」时挡住新 seed，杜绝旧 chunk（turnId=null）
 * 越过屏障并入新 seed 的轮。
 *
 * 双条件 latch，两个条件顺序任意、皆真才解封：
 * - [markerConsumed]：collector 已消费该轮的合成 TurnEnd marker（客户端旧 chunk 已排空）。
 * - [promptJobFinished]：该轮 prompt 子任务已结束（成功 / 失败 / 取消任一）。
 *
 * 单纯 Set add/remove 会被「marker 先消费/remove、随后 prompt 失败再 add」的竞态造成永久误隔离，
 * 故必须两个完成条件独立记录。
 */
internal data class TurnBarrier(
    val turnId: String,
    @Volatile var markerConsumed: Boolean = false,
    @Volatile var promptJobFinished: Boolean = false,
    /**
     * 本轮终态（T3）：[TurnOutcome] 三态之一，或 null = 终态尚未确认。
     * 两个完成条件皆真即判成功（首写优先，已由 catch 记下的 Failure/Timeout 不会被覆盖）；
     * 断连到不了 complete 就永远留 null —— 这就是「未确认」，不是成功。
     */
    @Volatile var outcome: TurnOutcome? = null,
) {
    val complete: Boolean get() = markerConsumed && promptJobFinished
}

/**
 * 每会话当前终态屏障注册表（纯内存、并发安全）。
 *
 * 生命周期：
 * - [seed]：sendMessage 落库/seed 前登记新轮屏障（初始 blocking）。
 * - [markerConsumed]：collector 消费完合成 TurnEnd marker。
 * - [promptJobFinished]：prompt 子任务结束（含真 caller 取消 —— 远端仍活，须继续挡 seed）。
 * - 两者皆真 → [complete]，解封。
 *
 * 断连不自动放行：断连发生在 prompt response 前时无 marker 可消费，屏障保持阻塞 ——
 * 这是已知限制（ACP resume 无 quiesce 协议保证，无法证明旧活动已终态），不做自动恢复、
 * 不自动 newSession、不假称完整恢复（m00697）。
 */
internal class TurnBarrierRegistry {
    private val barriers = ConcurrentHashMap<String, TurnBarrier>()

    fun seed(sessionId: String, turnId: String) {
        barriers[sessionId] = TurnBarrier(turnId)
    }

    fun isBlocked(sessionId: String): Boolean = barriers[sessionId]?.let { !it.complete } ?: false

    fun markerConsumed(sessionId: String, turnId: String) {
        barriers[sessionId]?.takeIf { it.turnId == turnId }?.let {
            it.markerConsumed = true
            settleSuccessIfComplete(it)
        }
    }

    fun promptFinished(sessionId: String, turnId: String) {
        barriers[sessionId]?.takeIf { it.turnId == turnId }?.let {
            it.promptJobFinished = true
            settleSuccessIfComplete(it)
        }
    }

    /** 显式终态（失败/超时）：首写优先，迟到或重复的判定不覆盖已确认的终态。 */
    fun settle(sessionId: String, turnId: String, outcome: TurnOutcome) {
        barriers[sessionId]?.takeIf { it.turnId == turnId }?.let {
            if (it.outcome == null) it.outcome = outcome
        }
    }

    /** 当前轮终态；null = 未确认（断连/屏障未完整），调用方不得当成成功。 */
    fun outcome(sessionId: String): TurnOutcome? = barriers[sessionId]?.outcome

    private fun settleSuccessIfComplete(b: TurnBarrier) {
        if (b.complete && b.outcome == null) b.outcome = TurnOutcome.Success
    }

    fun clear(sessionId: String) {
        barriers.remove(sessionId)
    }
}
