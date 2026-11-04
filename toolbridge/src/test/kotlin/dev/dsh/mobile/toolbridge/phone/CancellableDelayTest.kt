package dev.dsh.mobile.toolbridge.phone

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/** T9.14 修 3：可取消等待必须真的能被取消，并且取消以 CancellationException 现形。 */
class CancellableDelayTest {

    @Test
    fun `cancel interrupts a ten second delay well before its deadline`() = runBlocking {
        val started = System.currentTimeMillis()
        var thrown: Throwable? = null
        val job = launch {
            try {
                cancellableDelay(10_000)
            } catch (t: Throwable) {
                thrown = t
                throw t
            }
        }
        delay(100)
        job.cancel()
        job.join()
        val elapsed = System.currentTimeMillis() - started
        assertTrue("取消后应立即返回，实际耗时 " + elapsed + "ms", elapsed < 1_000)
        assertTrue("取消必须以 CancellationException 现形，实际 " + thrown, thrown is CancellationException)
    }

    @Test
    fun `non positive delay returns without suspending`() = runBlocking {
        val started = System.currentTimeMillis()
        cancellableDelay(0)
        cancellableDelay(-1)
        val elapsed = System.currentTimeMillis() - started
        assertTrue("非正数应立即返回，实际耗时 " + elapsed + "ms", elapsed < 100)
    }
}
