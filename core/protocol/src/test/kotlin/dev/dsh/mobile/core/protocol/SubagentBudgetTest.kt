package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.protocol.llm.SubagentBudget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T5.3：子代理的预算分摊闸门。
 *
 * 只测闸门本身（用量/预算由两个 lambda 注入，等价于复用 lastUsageTokens 与 contextBudget）：
 * 80% 告急一次、100% 拒绝、分摊按并发数下降、被拒不占并发名额。
 */
class SubagentBudgetTest {

    @Test
    fun belowWarningLinePassesSilently() = runBlocking {
        val gate = gate(used = 500L, budgetTokens = 1_000)
        val verdict = gate.begin("s1")
        assertTrue(verdict.allowed)
        assertEquals(50, verdict.percent)
        assertFalse(verdict.warn)
        gate.end("s1")
    }

    @Test
    fun atEightyPercentWarnsOnceButStillAllows() = runBlocking {
        val gate = gate(used = 850L, budgetTokens = 1_000)
        val first = gate.begin("s1")
        assertTrue(first.allowed)
        assertEquals(85, first.percent)
        assertTrue("80% 要落一条告急通知", first.warn)
        val second = gate.begin("s1")
        assertTrue(second.allowed)
        assertFalse("同一会话只报一次", second.warn)
        gate.end("s1")
        gate.end("s1")
    }

    @Test
    fun atHundredPercentRejectsWithReadableReason() = runBlocking {
        val gate = gate(used = 1_200L, budgetTokens = 1_000)
        val verdict = gate.begin("s1")
        assertFalse(verdict.allowed)
        assertEquals(120, verdict.percent)
        assertEquals(0, verdict.shareChars)
        assertTrue(verdict.reason, verdict.reason.contains("预算已用完"))
        assertTrue(verdict.reason, verdict.reason.contains("120%"))
    }

    @Test
    fun rejectionDoesNotLeakAConcurrencySlot() = runBlocking {
        var used = 8_500L
        val gate = SubagentBudget(usedTokensOf = { used }, budgetTokensOf = { 8_000 })
        assertFalse(gate.begin("s1").allowed)
        // 预算腾出来之后重新放行：分摊分母仍是 1，说明被拒的那次没占名额。
        used = 0L
        assertEquals(32_000, gate.begin("s1").shareChars)
        gate.end("s1")
    }

    @Test
    fun shareIsSplitByConcurrency() = runBlocking {
        val gate = gate(used = 0L, budgetTokens = 8_000)
        assertEquals("并发 1 时全给它", 32_000, gate.begin("s1").shareChars)
        assertEquals("并发 2 时对半", 16_000, gate.begin("s1").shareChars)
        gate.end("s1")
        assertEquals("一个跑完，分母回落", 16_000, gate.begin("s1").shareChars)
    }

    // ---- 夹具 ----

    private fun gate(used: Long, budgetTokens: Int) = SubagentBudget(
        usedTokensOf = { used },
        budgetTokensOf = { budgetTokens },
    )
}
