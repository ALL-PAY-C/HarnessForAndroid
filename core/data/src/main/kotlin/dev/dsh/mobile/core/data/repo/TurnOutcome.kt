package dev.dsh.mobile.core.data.repo

import kotlinx.coroutines.TimeoutCancellationException

/**
 * 一轮流式的确定性终态（T3）。
 *
 * 流中断（对端静默关流、ws 断连、prompt 子任务无异常返回却没有终态 marker）以前落到
 * 「finalize 了、状态回 Idle」——用户看起来像成功，实际上没有终态。这里只认三种终态，
 * 没有第四种「结束了但不知道算不算成功」：判不出来就是 [Failure]，而不是默认成功。
 */
internal enum class TurnOutcome { Success, Failure, Timeout }

/**
 * 由「prompt 子任务怎么结束」+「终态 marker 是否消费」判定终态（纯函数，可单测）。
 *
 * - [TimeoutCancellationException] 优先：30min 到点即便流看起来收完了也以超时为准
 *   （[completedNormally] 不再参与判定，避免「刚好在超时边界收流」被记成成功）。
 * - 任何别的异常（含 CancellationException 之外的一切）→ [Failure]。
 * - 无异常但 [completedNormally] 为假 → [Failure]：流是断的，不是收完的。
 *
 * 用户主动取消不参与判定：[ChatRepository.cancel] 已按 partial + Idle 显式结算，
 * 那不是故障，不该被这里记成 Failure。
 */
internal fun resolveTurnOutcome(completedNormally: Boolean, error: Throwable?): TurnOutcome = when {
    error is TimeoutCancellationException -> TurnOutcome.Timeout
    error != null -> TurnOutcome.Failure
    completedNormally -> TurnOutcome.Success
    else -> TurnOutcome.Failure
}
