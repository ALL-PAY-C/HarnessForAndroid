package dev.dsh.mobile.core.data.repo

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** T3：一轮流式只有成功/失败/超时三个确定性终态，判不出来就是失败，不许默认成功。 */
class TurnOutcomeTest {

    private fun timeoutException(): Throwable = runBlocking {
        try {
            withTimeout(1L) { delay(50L) }
            error("withTimeout 没有抛，测试前提不成立")
        } catch (e: Throwable) {
            e
        }
    }

    @Test
    fun completedWithoutErrorIsSuccess() {
        assertEquals(TurnOutcome.Success, resolveTurnOutcome(completedNormally = true, error = null))
    }

    @Test
    fun streamInterruptedWithoutErrorIsFailure() {
        // 断流：prompt 子任务「正常」返回了，但终态 marker 没到 —— 不能记成功。
        assertEquals(TurnOutcome.Failure, resolveTurnOutcome(completedNormally = false, error = null))
    }

    @Test
    fun errorAlwaysFails() {
        assertEquals(TurnOutcome.Failure, resolveTurnOutcome(completedNormally = false, error = IOException("socket closed")))
        assertEquals(
            "即便流里已经收完，只要抛了异常就是失败终态",
            TurnOutcome.Failure,
            resolveTurnOutcome(completedNormally = true, error = IOException("socket closed")),
        )
    }

    @Test
    fun timeoutBeatsCompletedFlag() {
        assertEquals(TurnOutcome.Timeout, resolveTurnOutcome(completedNormally = false, error = timeoutException()))
        assertEquals(
            "刚好在超时边界收流也以超时为准，不记成功",
            TurnOutcome.Timeout,
            resolveTurnOutcome(completedNormally = true, error = timeoutException()),
        )
    }

    @Test
    fun barrierStaysUnconfirmedUntilBothConditions() {
        val r = TurnBarrierRegistry()
        r.seed("s", "t1")
        assertNull("刚 seed：终态未确认", r.outcome("s"))
        r.promptFinished("s", "t1")
        assertNull("只有 promptFinished：marker 未到，仍是未确认", r.outcome("s"))
        assertTrue("未确认就必须继续挡新 seed", r.isBlocked("s"))
        r.markerConsumed("s", "t1")
        assertEquals("两条件齐 → 成功", TurnOutcome.Success, r.outcome("s"))
        assertTrue("解封", !r.isBlocked("s"))
    }

    @Test
    fun explicitFailureIsNotOverwrittenByLateMarker() {
        val r = TurnBarrierRegistry()
        r.seed("s", "t1")
        r.settle("s", "t1", TurnOutcome.Failure)
        r.markerConsumed("s", "t1")
        r.promptFinished("s", "t1")
        assertEquals("失败首写优先，迟到 marker 不能洗成成功", TurnOutcome.Failure, r.outcome("s"))
    }

    @Test
    fun lateOutcomeForAnotherTurnIsIgnored() {
        val r = TurnBarrierRegistry()
        r.seed("s", "t2")
        r.settle("s", "t1", TurnOutcome.Failure)
        assertNull("上一轮的终态不能写到新轮", r.outcome("s"))
    }

    @Test
    fun newTurnDoesNotInheritOldOutcome() {
        val r = TurnBarrierRegistry()
        r.seed("s", "t1")
        r.settle("s", "t1", TurnOutcome.Timeout)
        r.seed("s", "t2")
        assertNull("新轮从「未确认」开始", r.outcome("s"))
    }
}
