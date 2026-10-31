package dev.dsh.mobile.feature.chat

import dev.dsh.mobile.core.data.repo.AuthorPart
import dev.dsh.mobile.core.data.repo.TurnState
import dev.dsh.mobile.core.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionMeterTest {
    /** 与 ChatViewModel.streamedTokens 同口径：多人分段时按 parts 汇总，否则用单聊字段。 */
    private fun streamed(turn: TurnState): Long {
        val text = if (turn.parts.isEmpty()) turn.text else turn.parts.joinToString("") { it.text }
        val thought = if (turn.parts.isEmpty()) turn.thought else turn.parts.joinToString("") { it.thought }
        return (estimateTokens(text) + estimateTokens(thought)).toLong()
    }

    private fun meter() = CompletionMeter(::streamed)

    private fun reply(text: String = "", outputTokens: Long = 0L) = ChatMessage(
        id = "a1",
        sessionId = "s",
        role = ChatMessage.Role.ASSISTANT,
        text = text,
        outputTokens = outputTokens,
    )

    @Test fun turnEndKeepsPeakInsteadOfFallingBackToZero() {
        val m = meter()
        assertEquals(12_400L, m.current(TurnState(running = true, turnId = "t1", outputTokens = 12_400), emptyList()))
        // 轮次结束：TurnLedger 换成新对象（新 turnId、running=false），落库用量还没回来。
        assertEquals(12_400L, m.current(TurnState(running = false, turnId = "t2"), listOf(reply(text = "hello"))))
    }

    @Test fun settledUsageWinsOverPeak() {
        val m = meter()
        assertEquals(5_000L, m.current(TurnState(running = false, turnId = "t9"), listOf(reply(outputTokens = 5_000))))
    }

    @Test fun reopenSessionRecoversFromTextWhenOutputTokensMissing() {
        val m = meter()
        val text = "a".repeat(400)
        val value = m.current(TurnState(running = false, turnId = "t0"), listOf(reply(text = text)))
        assertEquals(estimateTokens(text).toLong(), value)
        assertTrue(value > 0L)
    }

    @Test fun newTurnResetsPeak() {
        val m = meter()
        m.current(TurnState(running = true, turnId = "t1", outputTokens = 12_400), emptyList())
        assertEquals(0L, m.current(TurnState(running = true, turnId = "t2"), emptyList()))
    }

    @Test fun streamingUsesLargerOfUsageAndEstimate() {
        val m = meter()
        val turn = TurnState(running = true, turnId = "t1", text = "b".repeat(800), outputTokens = 50)
        assertEquals(200L, m.current(turn, emptyList()))
    }

    @Test fun finishedPartKeepsPeakWhileTurnStillRunning() {
        val m = meter()
        val part = AuthorPart(id = "p1", name = "A", text = "c".repeat(400))
        val shown = estimateTokens(part.text).toLong()
        assertEquals(shown, m.current(TurnState(running = true, turnId = "t1", parts = listOf(part)), emptyList()))
        // 分段已落库、parts 被清空，本轮还在跑：顶栏不能掉回 0。
        assertEquals(shown, m.current(TurnState(running = true, turnId = "t1"), emptyList()))
    }
}
