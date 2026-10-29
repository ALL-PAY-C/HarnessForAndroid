package dev.dsh.mobile.core.common

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ResultTest {

    @Test
    fun runCatchingDshWrapsOrdinaryException() {
        val res = runCatchingDsh<Unit> { throw IllegalStateException("boom") }
        assertTrue(res is DshResult.Err)
        assertEquals("boom", res.errorOrNull())
    }

    @Test
    fun runCatchingDshRethrowsCancellationException() {
        val ex = CancellationException("cancelled")
        try {
            runCatchingDsh<Unit> { throw ex }
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            assertEquals(ex, e)
        }
    }
}
