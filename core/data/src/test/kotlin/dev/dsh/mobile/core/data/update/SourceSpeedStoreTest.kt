package dev.dsh.mobile.core.data.update

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源测速记忆 + 发车计划 + 「延迟源真的被取消」的 JVM 单测。
 *
 * 存根是内存 Map：本类不碰 android.*，SharedPreferences 只在 Updater 里注入。
 * 时序断言继续走虚拟时钟（StandardTestDispatcher），不给 CI 加真实的 1.5s。
 */
class SourceSpeedStoreTest {

    private fun store(): SourceSpeedStore {
        val map = HashMap<String, String>()
        return SourceSpeedStore(read = { map[it] }, write = { k, v -> map[k] = v })
    }

    @Test
    fun fasterSourceFromLastRoundGoesOutFirst() {
        val store = store()
        store.recordSuccess("slow", 3_000)
        store.recordSuccess("fast", 200)

        val plan = store.plan(listOf("slow", "fast", "never-tested"))

        assertEquals("上一轮最快的必须先发", listOf("fast"), plan.hot.take(1))
        assertTrue("上一轮慢的要被推迟，实测 hot=" + plan.hot, "slow" in plan.delayed)
        assertEquals(ManifestRace.STAGGER_DELAY_MS, plan.delayMs)

        // EMA 而不是「上次耗时」：一次 5s 抖动只把 200ms 的源拉高到 1/4 处，仍远快于 3s 的源。
        store.recordSuccess("fast", 5_000)
        assertEquals((200 * 3 + 5_000) / 4L, store.stat("fast")!!.emaMs)
    }

    @Test
    fun withoutHistoryEverySourceStartsAtOnce() = runTest {
        val store = store()
        val plan = store.plan(listOf("a", "b", "c"))
        assertEquals("没有历史就不许改发车顺序", listOf("a", "b", "c"), plan.hot)
        assertTrue("没有历史就不许有延迟源", plan.delayed.isEmpty())

        val dispatcher = StandardTestDispatcher(testScheduler)
        val starts = mutableListOf<String>()
        val result = ManifestRace.race(
            sources = listOf("a", "b", "c"),
            perSourceTimeoutMs = 5_000,
            totalBudgetMs = 10_000,
            dispatcher = dispatcher,
            plan = { store.plan(it) },
        ) { url ->
            starts.add(url + "@" + testScheduler.currentTime)
            delay(100)
            url + "-body"
        }

        // 三个源都在 t=0 发车；有任何延迟源就会看到 @1500。
        assertEquals(listOf("a@0", "b@0", "c@0"), starts)
        assertTrue(result is ManifestAttempt.Ok)
    }

    @Test
    fun failedSourceIsPushedBackOnTheNextRound() {
        val store = store()
        store.recordSuccess("a", 300)
        store.recordSuccess("b", 300)
        store.recordSuccess("c", 300)
        store.recordFailure("b")

        val plan = store.plan(listOf("b", "a", "c"))

        assertTrue("挂过的源不该先进 hot，实测 " + plan.hot, "b" !in plan.hot)
        assertEquals(listOf("b"), plan.delayed)

        // 恢复一次就把罚分还回去：罚分不是黑名单。
        store.recordSuccess("b", 100)
        assertEquals(0, store.stat("b")!!.penalty)
        assertEquals((300 * 3 + 100) / 4L, store.stat("b")!!.emaMs)
        assertTrue("恢复之后应重新参与排序", "b" in store.plan(listOf("b", "a", "c")).hot)
    }

    @Test
    fun winningHotSourceCancelsTheDelayedOne() = runTest {
        val store = store()
        store.recordSuccess("fast", 100)
        store.recordSuccess("mid", 100)
        store.recordSuccess("slow", 100)
        store.recordFailure("slow")

        val dispatcher = StandardTestDispatcher(testScheduler)
        val fetched = mutableListOf<String>()
        val result = ManifestRace.race(
            sources = listOf("slow", "mid", "fast"),
            perSourceTimeoutMs = 5_000,
            totalBudgetMs = 10_000,
            dispatcher = dispatcher,
            plan = { store.plan(it) },
        ) { url ->
            fetched.add(url)
            if (url == "fast") "body" else { delay(50_000); "late" }
        }

        assertTrue(result is ManifestAttempt.Ok)
        result as ManifestAttempt.Ok
        assertEquals("fast", result.url)
        assertTrue("被罚分的源根本不该发车，实测 " + fetched, fetched.none { it == "slow" })
        assertTrue("胜出源要带上耗时明细", result.stats.any { it.url == "fast" && it.failure == null })
    }
}
