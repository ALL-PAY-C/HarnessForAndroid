package dev.dsh.mobile.core.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnBarrierTest {

    @Test
    fun seedBlocksUntilBothConditionsMet() {
        val r = TurnBarrierRegistry()
        r.seed("s", "t1")
        assertTrue("seed 后应阻塞", r.isBlocked("s"))
    }

    @Test
    fun promptFinishedAloneStillBlocks() {
        val r = TurnBarrierRegistry()
        r.seed("s", "t1")
        r.promptFinished("s", "t1")
        assertTrue("只有 promptFinished 仍应阻塞（marker 未消费）", r.isBlocked("s"))
    }

    @Test
    fun markerConsumedAloneStillBlocks() {
        val r = TurnBarrierRegistry()
        r.seed("s", "t1")
        r.markerConsumed("s", "t1")
        assertTrue("只有 markerConsumed 仍应阻塞（prompt 未结束）", r.isBlocked("s"))
    }

    @Test
    fun markerThenPromptReleases() {
        val r = TurnBarrierRegistry()
        r.seed("s", "t1")
        r.markerConsumed("s", "t1")
        r.promptFinished("s", "t1")
        assertFalse("marker→prompt 顺序应解封", r.isBlocked("s"))
    }

    @Test
    fun promptThenMarkerReleases() {
        val r = TurnBarrierRegistry()
        r.seed("s", "t1")
        r.promptFinished("s", "t1")
        r.markerConsumed("s", "t1")
        assertFalse("prompt→marker 顺序应解封（顺序任意）", r.isBlocked("s"))
    }

    @Test
    fun lateMarkerForOldTurnDoesNotReleaseNewTurnBarrier() {
        val r = TurnBarrierRegistry()
        r.seed("s", "t1")
        r.seed("s", "t2")            // 新轮替换旧屏障
        r.markerConsumed("s", "t1")  // t1 迟到 marker
        r.promptFinished("s", "t2")
        assertTrue("t1 迟到 marker 不得解封 t2 屏障", r.isBlocked("s"))
        r.markerConsumed("s", "t2")
        assertFalse("t2 的 marker 才解封", r.isBlocked("s"))
    }

    @Test
    fun reseedReplacesBarrier() {
        val r = TurnBarrierRegistry()
        r.seed("s", "t1")
        r.markerConsumed("s", "t1")
        r.promptFinished("s", "t1")
        assertFalse("t1 完成应解封", r.isBlocked("s"))
        r.seed("s", "t2")
        assertTrue("新轮 seed 应重新阻塞", r.isBlocked("s"))
    }

    // ------------------------------------------------------------------ T3 终态/屏障一致性
    // 真实顺序见 ChatRepository.kt:449-455（finally 里 promptFinished）与 :475/:487（catch 里 settle）：
    // settle 一定发生在 promptFinished 之后，此时 markerConsumed 仍为 false。

    @Test
    fun settledFailureStillBlocksNewSeed() {
        val r = TurnBarrierRegistry()
        r.seed("s", "t1")
        r.promptFinished("s", "t1")
        r.settle("s", "t1", TurnOutcome.Failure)
        assertTrue("失败只记终态，marker 未消费 → 仍须挡新 seed", r.isBlocked("s"))
        assertEquals(TurnOutcome.Failure, r.outcome("s"))
    }

    @Test
    fun settledTimeoutStillBlocksNewSeed() {
        val r = TurnBarrierRegistry()
        r.seed("s", "t1")
        r.promptFinished("s", "t1")
        r.settle("s", "t1", TurnOutcome.Timeout)
        assertTrue("超时是我们放弃等待，远端仍可能活 → 仍须挡新 seed", r.isBlocked("s"))
        assertEquals(TurnOutcome.Timeout, r.outcome("s"))
    }

    @Test
    fun disconnectWithoutMarkerStaysBlockedAndUnconfirmed() {
        val r = TurnBarrierRegistry()
        r.seed("s", "t1")
        // 断连各变体（AcpTransport.kt:145/148/154）都会让 prompt 抛异常，repo 的 finally 照常记 promptFinished，
        // 但没有 marker 可消费，也没有 settle。
        r.promptFinished("s", "t1")
        assertTrue("断连无 marker 必须保守阻塞", r.isBlocked("s"))
        assertNull("断连不得被当成成功", r.outcome("s"))
    }

    @Test
    fun lateMarkerAfterSettledFailureDrainsAndReleases() {
        val r = TurnBarrierRegistry()
        r.seed("s", "t1")
        r.promptFinished("s", "t1")
        r.settle("s", "t1", TurnOutcome.Failure)
        r.markerConsumed("s", "t1")
        assertFalse("marker 到达 = 旧 chunk 已排空，可解封", r.isBlocked("s"))
        assertEquals("解封不得把失败洗成成功", TurnOutcome.Failure, r.outcome("s"))
    }

    @Test
    fun transportLossAndServerErrorShareTheSameFailureBucket() {
        // 断连（AcpTransport.kt:154 IllegalStateException("已断开")）与 RPC 错误（:190）在
        // resolveTurnOutcome 里同属 Failure → 屏障层没有任何字段能区分「远端已终态」与「断连未知」。
        assertEquals(
            "断连与 RPC 错误同桶，无判别器",
            resolveTurnOutcome(completedNormally = true, error = IllegalStateException("已断开")),
            resolveTurnOutcome(completedNormally = true, error = IllegalStateException("rpc error")),
        )
    }
}
