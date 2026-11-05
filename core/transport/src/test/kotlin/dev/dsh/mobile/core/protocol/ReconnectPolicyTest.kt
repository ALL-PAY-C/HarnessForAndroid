package dev.dsh.mobile.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun `backoff doubles from base`() {
        val p = ReconnectPolicy(baseDelayMs = 500, maxDelayMs = 20_000)
        assertEquals(500L, p.delayFor(1))
        assertEquals(1_000L, p.delayFor(2))
        assertEquals(2_000L, p.delayFor(3))
        assertEquals(4_000L, p.delayFor(4))
    }

    @Test
    fun `backoff is capped`() {
        val p = ReconnectPolicy(baseDelayMs = 500, maxDelayMs = 3_000)
        assertEquals(3_000L, p.delayFor(6))
        assertEquals(3_000L, p.delayFor(30))
    }

    @Test
    fun `non positive attempt uses base delay`() {
        val p = ReconnectPolicy(baseDelayMs = 250)
        assertEquals(250L, p.delayFor(0))
        assertEquals(250L, p.delayFor(-3))
    }
}
