package dev.dsh.mobile.pluginhost

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 插件市场单源超时/网络受限的体验回归：缓存优先、全挂回落内置、部分成功合并。
 * 全部用注入的 fake fetcher，一次网络都不发。
 */
class MarketCacheTest {

    private fun entry(id: String, version: String = "1.0.0") =
        MarketEntry(id = id, name = id, version = version)

    private val srcA = MarketSource(id = "a", name = "A源", url = "https://a.invalid/registry.json")
    private val srcB = MarketSource(id = "b", name = "B源", url = "https://b.invalid/registry.json")

    @Test
    fun cacheHitRendersBeforeAnyNetwork() {
        val cache = MemoryMarketCache(listOf(entry("cached.one"), entry("cached.two")))
        var calls = 0
        val loader = MarketLoader(
            bundled = { listOf(entry("builtin")) },
            cache = cache,
            sources = listOf(srcA, srcB),
            fetch = { calls++; error("首帧不该联网") },
        )

        val first = loader.initial()

        assertEquals("首帧必须零请求", 0, calls)
        assertTrue(first.fromCache)
        assertFalse(first.usedBundled)
        assertEquals(listOf("cached.one", "cached.two", "builtin"), first.snapshot.plugins.map { it.id })
        // 还没开始拉，不报失败、不显示状态行。
        assertFalse(first.allFailed)
        assertNull(first.notice())
    }

    @Test
    fun allSourcesFailFallsBackToBundled() {
        val loader = MarketLoader(
            bundled = { listOf(entry("builtin")) },
            cache = MemoryMarketCache(),
            sources = listOf(srcA, srcB),
            fetch = { error("connect timed out") },
        )

        val result = runBlocking { loader.refresh() }

        assertEquals(listOf("builtin"), result.snapshot.plugins.map { it.id })
        assertTrue(result.allFailed)
        assertTrue(result.usedBundled)
        assertFalse(result.fromCache)
        assertEquals(2, result.snapshot.sources.count { !it.ok })
        val notice = result.notice()
        assertNotNull(notice)
        assertTrue(notice!!, notice.contains("已回退内置索引"))
    }

    @Test
    fun allSourcesFailWithCacheKeepsCachedListAndDoesNotOverwriteIt() {
        val cache = MemoryMarketCache(listOf(entry("cached.one")))
        val loader = MarketLoader(
            bundled = { listOf(entry("builtin")) },
            cache = cache,
            sources = listOf(srcA),
            fetch = { error("Read timed out") },
        )

        val result = runBlocking { loader.refresh() }

        assertTrue(result.fromCache)
        assertFalse(result.usedBundled)
        assertEquals(listOf("cached.one", "builtin"), result.snapshot.plugins.map { it.id })
        assertTrue(result.notice()!!.contains("正在用缓存"))
        // 全挂那次不能把「只有内置」的列表写回缓存，否则下次启动缓存就没了。
        assertEquals(listOf("cached.one"), cache.read().map { it.id })
    }

    @Test
    fun partialSourceSuccessMergesAndWritesCache() {
        val cache = MemoryMarketCache()
        val loader = MarketLoader(
            bundled = { listOf(entry("builtin")) },
            cache = cache,
            sources = listOf(srcA, srcB),
            fetch = { source ->
                if (source.id == "a") {
                    listOf(
                        entry("live.a"),
                        MarketEntry(id = "shared", name = "shared", version = "2.0.0", sources = listOf("a")),
                    )
                } else {
                    error("HTTP 502: busy")
                }
            },
        )
        val progress = mutableListOf<MarketLoad>()

        val result = runBlocking { loader.refresh { progress += it } }

        assertFalse(result.allFailed)
        assertEquals(1, result.okSources)
        assertNull("部分成功不该出失败行", result.notice())
        assertEquals(listOf("live.a", "shared", "builtin"), result.snapshot.plugins.map { it.id })
        // 先回来的源立刻上屏一次。
        assertTrue(progress.isNotEmpty())
        // 有源成功才回写缓存。
        assertEquals(listOf("live.a", "shared", "builtin"), cache.read().map { it.id })
    }

    @Test
    fun fileCacheRoundTripsAndSilentlyDropsGarbage() {
        val file = File.createTempFile("market-cache", ".json")
        val store = FileMarketCache(file)
        val entries = listOf(
            MarketEntry(
                id = "x",
                name = "X",
                version = "1.2.3",
                description = "描述",
                kind = MarketEntry.Kind.Package,
                installHint = "dsh plugin add x",
                homepage = "https://example.invalid/x",
                packageUrl = "https://example.invalid/x.zip",
                repo = "https://github.com/x/x",
                npm = "x",
                downloads = 5,
                stars = 7,
                author = "x",
                tags = listOf("标签"),
                sources = listOf("a"),
            ),
        )

        assertTrue(store.write(entries))
        assertEquals(entries, store.read())

        file.writeText("{ this is not json")
        assertEquals(emptyList<MarketEntry>(), store.read())
        file.delete()
    }

    @Test
    fun oversizedCacheIsDroppedInsteadOfPersisted() {
        val file = File.createTempFile("market-cache-big", ".json")
        val store = FileMarketCache(file, maxBytes = 16)

        assertFalse(store.write(listOf(entry("x"))))
        assertFalse(file.exists())
    }
}
