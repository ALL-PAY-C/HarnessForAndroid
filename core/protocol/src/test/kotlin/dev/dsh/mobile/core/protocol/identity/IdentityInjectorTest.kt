package dev.dsh.mobile.core.protocol.identity

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 注入节奏：首轮必注入、每 6 轮补一次、卡变/续会话/压缩后强制重来。 */
class IdentityInjectorTest {

    private val card = "## 身份卡：审稿人\n你是：技术审稿人"

    private fun injector(text: String = card, every: Int = IdentityInjector.DEFAULT_EVERY_TURNS) =
        IdentityInjector(identityOf = { text }, everyTurns = every)

    @Test
    fun firstTurnIsAlwaysInjected() = runTest {
        val out = injector().wrap("s1", "第一句")
        assertEquals(IdentityInjector.HEADER + card + "\n\n第一句", out)
    }

    @Test
    fun fortyTurnsInjectsSevenTimes() = runTest {
        val inj = injector()
        var injected = 0
        repeat(40) { i ->
            if (inj.wrap("s1", "第 " + (i + 1) + " 轮").contains(card)) injected++
        }
        // 第 1 轮 + 之后每 6 轮一次：1,7,13,19,25,31,37。
        assertEquals(7, injected)
    }

    @Test
    fun cardChangeForcesImmediateReinjection() = runTest {
        var text = card
        val inj = IdentityInjector(identityOf = { text }, everyTurns = 6)
        assertTrue(inj.wrap("s1", "a").contains(card))
        assertFalse(inj.wrap("s1", "b").contains(card))
        text = card + "\n口吻：更严格"
        assertTrue("卡变了必须重发", inj.wrap("s1", "c").contains("更严格"))
    }

    @Test
    fun invalidateForcesReinjection() = runTest {
        val inj = injector()
        assertTrue(inj.wrap("s1", "a").contains(card))
        assertFalse(inj.wrap("s1", "b").contains(card))
        // closeSession / resumeSession / Compacted 都走 invalidate。
        inj.invalidate("s1")
        assertTrue(inj.wrap("s1", "c").contains(card))
    }

    @Test
    fun forgetResetsTurnCounting() = runTest {
        val inj = injector()
        repeat(5) { i -> inj.wrap("s1", "turn " + i) }
        inj.forget("s1")
        // 同名会话重建：轮次从 1 开始数，第 2 轮不该注入。
        assertTrue(inj.wrap("s1", "重建后第一轮").contains(card))
        assertFalse(inj.wrap("s1", "重建后第二轮").contains(card))
    }

    @Test
    fun blankIdentityLeavesTextUntouched() = runTest {
        val inj = injector(text = "   ")
        repeat(10) { i ->
            assertEquals("原文 " + i, inj.wrap("s1", "原文 " + i))
        }
    }

    @Test
    fun everyTurnModeInjectsEveryTurn() = runTest {
        val inj = injector(every = 1)
        repeat(5) { i -> assertTrue(inj.wrap("s1", "第 " + i + " 轮").contains(card)) }
    }

    @Test
    fun sessionsAreCountedSeparately() = runTest {
        val inj = injector()
        assertTrue(inj.wrap("s1", "a").contains(card))
        // s2 是另一个会话：它自己的首轮，必须注入。
        assertTrue(inj.wrap("s2", "a").contains(card))
        assertFalse(inj.wrap("s1", "b").contains(card))
    }
}
