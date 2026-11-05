package dev.dsh.mobile.pluginhost

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B1 守护循环：退避序列、成功归零、门控关闭即停。全部用假 sleep，不跑真实的一分钟。 */
class BackgroundSupervisorTest {

    @Test
    fun backoffSequenceIsStrictlyDoublingUntilCapped() = runBlocking {
        val delays = mutableListOf<Long>()
        var allowed = true
        BackgroundSupervisor(
            isAllowed = { allowed },
            start = { Result.failure<Unit>(IllegalStateException("gateway down")) },
            onFailure = {},
            sleep = { ms ->
                delays += ms
                if (delays.size == 8) allowed = false
            },
        ).run()

        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L),
            delays,
        )
    }

    @Test
    fun successResetsRetryCounterToFirstStep() = runBlocking {
        val delays = mutableListOf<Long>()
        var calls = 0
        BackgroundSupervisor(
            isAllowed = { true },
            start = {
                calls++
                if (calls == 3) Result.success(Unit) else Result.failure<Unit>(IllegalStateException("down"))
            },
            onFailure = {},
            sleep = { ms ->
                delays += ms
                if (delays.size == 4) throw CancellationException("test stop")
            },
        ).let { runCatching { it.run() } }

        // 失败两次 → 1s、2s；成功 → 退避归零 + 一次健康探测 60s；再失败 → 回到 1s。
        assertEquals(listOf(1_000L, 2_000L, 60_000L, 1_000L), delays)
    }

    @Test
    fun disabledPluginIsNeitherStartedNorRestarted() = runBlocking {
        var starts = 0
        val delays = mutableListOf<Long>()

        // 一开始就被禁用（安全模式 / 开关关着）：一次都不许拉。
        BackgroundSupervisor(
            isAllowed = { false },
            start = { starts++; Result.success(Unit) },
            onFailure = {},
            sleep = { delays += it },
        ).run()
        assertEquals(0, starts)
        assertTrue("没拉过就不该有任何退避等待", delays.isEmpty())

        // 拉过一次之后被禁用：不再重拉，且只留一次退避等待。
        var allowed = true
        BackgroundSupervisor(
            isAllowed = { allowed },
            start = { starts++; Result.failure<Unit>(IllegalStateException("down")) },
            onFailure = { allowed = false },
            sleep = { delays += it },
        ).run()
        assertEquals("禁用后不许再重拉", 1, starts)
        assertEquals(listOf(1_000L), delays)
    }

    @Test
    fun thrownExceptionIsTreatedAsFailure() = runBlocking {
        var attempts = 0
        val delays = mutableListOf<Long>()
        BackgroundSupervisor(
            isAllowed = { true },
            start = { attempts++; throw IllegalStateException("boom") },
            onFailure = {},
            sleep = { ms ->
                delays += ms
                if (delays.size == 2) throw CancellationException("test stop")
            },
        ).let { runCatching { it.run() } }

        assertEquals(2, attempts)
        assertEquals(listOf(1_000L, 2_000L), delays)
    }
}
