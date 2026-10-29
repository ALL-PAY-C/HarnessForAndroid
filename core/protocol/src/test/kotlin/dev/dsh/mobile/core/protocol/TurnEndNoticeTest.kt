package dev.dsh.mobile.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回合收尾留痕：异常收尾必须留下一条用户看得见的说明。 */
class TurnEndNoticeTest {

    @Test
    fun `tools remain available until the final round`() {
        assertTrue(shouldOfferTools(0, 64))
        assertTrue(shouldOfferTools(62, 64))
        assertEquals(false, shouldOfferTools(63, 64))
        assertEquals(false, shouldOfferTools(11, 12))
    }

    @Test
    fun `exhaustion notice uses the supplied limit`() {
        for (maxRounds in listOf(12, 64)) {
            val notice = turnEndNotice("stop", 0, null, true, maxRounds)
            assertTrue(notice!!.contains("上限 $maxRounds"))
        }
    }

    @Test
    fun `tool round limit supports long tasks`() {
        val maxRounds = LocalEngineTransport::class.java.getDeclaredField("MAX_ROUNDS").apply {
            isAccessible = true
        }.getInt(null)
        assertTrue("MAX_ROUNDS must be at least 64, was $maxRounds", maxRounds >= 64)
    }

    @Test
    fun `turn with visible body gets no notice`() {
        assertNull(
            turnEndNotice(
                reason = "stop",
                visibleChars = 42,
                lastToolError = toolFailureTrace("ls", "timeout"),
                roundsExhausted = true,
                maxRounds = 64,
            ),
        )
    }

    @Test
    fun `rounds exhausted without body explains the limit`() {
        val notice = turnEndNotice(
            reason = "stop",
            visibleChars = 0,
            lastToolError = null,
            roundsExhausted = true,
            maxRounds = 64,
        )
        assertTrue(notice!!.startsWith("[本轮未完成]"))
        assertTrue(notice.contains("上限 64"))
    }

    @Test
    fun `tool loop without body names the repeated call`() {
        val notice = turnEndNotice(
            reason = "tool_loop",
            visibleChars = 0,
            lastToolError = null,
            roundsExhausted = false,
            maxRounds = 64,
            loopDetail = "同一命令连续执行 3 次",
        )
        assertTrue(notice!!.startsWith("[已中止]"))
        assertTrue(notice.contains("同一命令连续执行 3 次"))
    }

    @Test
    fun `tool failure without body surfaces command and error`() {
        val notice = turnEndNotice(
            reason = "stop",
            visibleChars = 0,
            lastToolError = toolFailureTrace("gradle assembleDebug", "TimeoutException: 执行超时（30000ms）"),
            roundsExhausted = false,
            maxRounds = 64,
        )
        assertTrue(notice!!.startsWith("[工具失败]"))
        assertTrue(notice.contains("gradle assembleDebug"))
        assertTrue(notice.contains("执行超时"))
    }

    @Test
    fun `empty stop still leaves a notice`() {
        val notice = turnEndNotice(
            reason = "stop",
            visibleChars = 0,
            lastToolError = null,
            roundsExhausted = false,
            maxRounds = 64,
        )
        assertTrue(notice!!.contains("上游返回了空内容"))
    }

    @Test
    fun `failure trace flattens newlines and truncates`() {
        val trace = toolFailureTrace("a".repeat(80) + "\nsecond", "boom\nboom")
        assertEquals(60 + 1 + "boom boom".length, trace.length)
        assertTrue(trace.startsWith("a".repeat(60)))
        assertTrue(trace.endsWith("：boom boom"))
        assertEquals("（空命令）：未知错误", toolFailureTrace("   ", null))
    }
}
