package dev.dsh.mobile.pluginhost

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * 市场源的重试/并发/token 策略 + 缓存 TTL。
 *
 * plugin-host 的测试类路径里没有 kotlinx-coroutines-test，这里用 runBlocking 跑真时间：
 * 用到的延迟都是几十毫秒级，断言的是「次数」与「上限」而不是精确耗时。
 */
class MarketFetchPolicyTest {

    @Test
    fun retryingTakesThreeAttemptsWithExponentialBackoff() = runBlocking {
        val sleeps = ArrayList<Long>()
        var attempts = 0
        val error = runCatching {
            retrying(sleep = { sleeps.add(it) }) {
                attempts++
                error("boom")
            }
        }.exceptionOrNull()
        assertEquals("首次 + 2 次重试", 3, attempts)
        assertEquals(listOf(400L, 800L), sleeps)
        assertNotNull(error)
    }

    @Test
    fun retryingStopsAsSoonAsItSucceeds() = runBlocking {
        val sleeps = ArrayList<Long>()
        var attempts = 0
        val value = retrying(sleep = { sleeps.add(it) }) {
            attempts++
            if (attempts < 2) error("first fails") else "ok"
        }
        assertEquals("ok", value)
        assertEquals(2, attempts)
        assertEquals(listOf(400L), sleeps)
    }

    @Test
    fun fetchOneRetriesInsideTheSingleSourceBudget() = runBlocking {
        var attempts = 0
        val entries = PluginMarket.fetchOne(
            MarketSource(id = "s", name = "S", url = "https://example.com/x"),
            5_000,
            null,
        ) {
            attempts++
            if (attempts < 3) error("flake") else listOf(MarketEntry(id = "e", name = "E"))
        }
        assertEquals(3, attempts)
        assertEquals(1, entries.size)
    }

    @Test
    fun fetchOneNeverExceedsTheGate() = runBlocking {
        val gate = Semaphore(2)
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val sources = (1..6).map { MarketSource(id = "s" + it, name = "S" + it, url = "https://example.com/" + it) }
        val results = sources.map { source ->
            async(Dispatchers.IO) {
                runCatching {
                    PluginMarket.fetchOne(source, 5_000, gate) {
                        val now = active.incrementAndGet()
                        peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                        try {
                            delay(40)
                            listOf(MarketEntry(id = source.id, name = source.name))
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                }
            }
        }.awaitAll()
        assertEquals("六个源都应成功", 6, results.count { it.isSuccess })
        assertTrue("同时在飞不应超过 2，实测 " + peak.get(), peak.get() <= 2)
    }

    @Test
    fun tokenHeaderOnlyGoesToGithubApi() {
        assertEquals("Bearer abc", authHeader("https://api.github.com/search/repositories", "abc"))
        assertNull("反代不该拿到 token", authHeader("https://gh-proxy.com/https://github.com/x/y", "abc"))
        assertNull("jsDelivr 不该拿到 token", authHeader("https://cdn.jsdelivr.net/gh/x/y@main/z.json", "abc"))
        assertNull("没配 token 就不发头", authHeader("https://api.github.com/search/repositories", null))
        assertNull("空白 token 等于没配", authHeader("https://api.github.com/search/repositories", "   "))
        assertNull(
            "别把 token 发给长得像 api.github.com 的域名",
            authHeader("https://api.github.com.evil.test/x", "abc"),
        )
    }

    @Test
    fun fileCacheIsFreshWithinTtlAndStillReadableAsFallbackAfterwards() {
        val dir = Files.createTempDirectory("market-cache").toFile()
        dir.deleteOnExit()
        val file = File(dir, "market-cache.json")
        file.deleteOnExit()
        var now = 5_000_000L
        val cache = FileMarketCache(file, ttlMs = 60_000, clock = { now })
        val entries = listOf(MarketEntry(id = "a", name = "A"))
        assertTrue(cache.write(entries))
        assertEquals("刚写完必须命中", 1, cache.read().size)
        assertEquals(0L, cache.ageMs())

        now += 120_000
        assertTrue("过期后不该再参与正常合并", cache.read().isEmpty())
        assertEquals("但断网兜底还得能读到", 1, cache.readAny().size)
        assertEquals(120_000L, cache.ageMs())
    }

    @Test
    fun loaderKeepsStaleCacheWhenEverySourceFails() = runBlocking {
        val dir = Files.createTempDirectory("market-loader").toFile()
        dir.deleteOnExit()
        val file = File(dir, "market-cache.json")
        file.deleteOnExit()
        var now = 9_000_000L
        val cache = FileMarketCache(file, ttlMs = 1_000, clock = { now })
        assertTrue(cache.write(listOf(MarketEntry(id = "cached", name = "Cached"))))
        now += 10_000

        val loader = MarketLoader(
            bundled = { listOf(MarketEntry(id = "bundled", name = "Bundled")) },
            cache = cache,
            sources = listOf(MarketSource(id = "s", name = "S", url = "https://example.com/x")),
            timeoutMs = 1_000,
            fetch = { error("断网") },
        )
        val load = loader.refresh()
        assertTrue("全源失败仍要有列表", load.snapshot.plugins.any { it.id == "cached" })
        assertTrue(load.fromCache)
        assertFalse(load.usedBundled)
        assertEquals(1, load.snapshot.sources.size)
        assertFalse(load.snapshot.sources.first().ok)
        assertNotNull("每源失败原因要带出来", load.snapshot.sources.first().error)
    }
}
