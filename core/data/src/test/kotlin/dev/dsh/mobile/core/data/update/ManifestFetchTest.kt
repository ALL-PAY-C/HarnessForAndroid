package dev.dsh.mobile.core.data.update

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import dev.dsh.mobile.core.model.update.ManifestOrigin
import dev.dsh.mobile.core.model.update.SourceFailure

/**
 * 清单并行 + 三级回退的 JVM 单测。
 *
 * 时序断言走虚拟时钟（StandardTestDispatcher）：真等 8 秒的测试没人愿意跑，
 * 而 currentTime 又足以证明「并行」「不串行」「预算截断」这三件事。
 */
class ManifestFetchTest {

    @Test
    fun fastestSourceWinsWithoutWaitingForTheSlowOnes() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val result = ManifestRace.race(
            sources = listOf("slow", "fast"),
            perSourceTimeoutMs = 5_000,
            totalBudgetMs = 10_000,
            dispatcher = dispatcher,
        ) { url ->
            if (url == "slow") {
                delay(4_000)
                "slow-body"
            } else {
                delay(50)
                "fast-body"
            }
        }
        assertTrue(result is ManifestAttempt.Ok)
        result as ManifestAttempt.Ok
        assertEquals("fast", result.url)
        assertEquals("fast-body", result.body)
        assertTrue("先到先用的耗时应该约 50ms，实测 " + testScheduler.currentTime, testScheduler.currentTime < 1_000)
    }

    @Test
    fun perSourceTimeoutRunsInParallelInsteadOfAddingUp() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val result = ManifestRace.race(
            sources = listOf("a", "b", "c"),
            perSourceTimeoutMs = 1_000,
            totalBudgetMs = 10_000,
            dispatcher = dispatcher,
        ) {
            delay(60_000)
            "never"
        }
        assertTrue(result is ManifestAttempt.Failed)
        val failures = (result as ManifestAttempt.Failed).failures
        assertEquals(3, failures.size)
        assertTrue(failures.all { it.reason.contains("超时") })
        assertTrue("三个源并行超时应该约 1000ms，实测 " + testScheduler.currentTime, testScheduler.currentTime <= 1_100)
    }

    @Test
    fun totalBudgetCutsTheRaceOffAndSaysSo() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val result = ManifestRace.race(
            sources = listOf("a", "b"),
            perSourceTimeoutMs = 30_000,
            totalBudgetMs = 2_000,
            dispatcher = dispatcher,
        ) {
            delay(60_000)
            "never"
        }
        assertTrue(result is ManifestAttempt.Failed)
        val failures = (result as ManifestAttempt.Failed).failures
        assertTrue("预算耗尽要单独记一条", failures.any { it.url.contains("总预算") })
        assertTrue("预算应该截断在 2000ms 附近，实测 " + testScheduler.currentTime, testScheduler.currentTime <= 2_100)
    }

    @Test
    fun describeFailuresMentionsEverySourceNotJustTheFirst() {
        val text = describeFailures(
            listOf(
                SourceFailure("https://a/update.json", "超时 8000ms"),
                SourceFailure("https://b/update.json", "HTTP 403"),
            ),
        )
        assertTrue(text.contains("https://a/update.json"))
        assertTrue(text.contains("超时 8000ms"))
        assertTrue(text.contains("https://b/update.json"))
        assertTrue(text.contains("HTTP 403"))
    }

    @Test
    fun storeStampsSavedAtAndExpiresOnDisk() {
        val dir = Files.createTempDirectory("manifest-store").toFile()
        dir.deleteOnExit()
        val file = File(dir, "update-manifest.json")
        file.deleteOnExit()
        var now = 1_000_000L
        val store = ManifestStore(file, ttlMs = 1_000, clock = { now })
        assertTrue(store.save("body-1"))
        assertTrue("落盘文件必须带时间戳", file.readText().startsWith("savedAt="))
        // 新实例模拟重启：内存为空，只剩磁盘那一份。
        assertEquals("body-1", ManifestStore(file, ttlMs = 1_000, clock = { now }).diskFresh())
        now += 1_500
        assertNull("过期后磁盘那份不该再当新鲜数据", ManifestStore(file, ttlMs = 1_000, clock = { now }).diskFresh())
    }

    @Test
    fun resolverWalksLiveThenMemoryThenDiskThenFails() = runTest {
        val dir = Files.createTempDirectory("manifest-resolve").toFile()
        dir.deleteOnExit()
        val file = File(dir, "update-manifest.json")
        file.deleteOnExit()
        var live: ManifestAttempt = ManifestAttempt.Ok("u", "live-body", emptyList())
        val resolver = ManifestResolver(ManifestStore(file, ttlMs = 60_000)) { live }

        val first = resolver.resolve(listOf("u")) as ManifestResult.Ok
        assertEquals(ManifestOrigin.Live, first.origin)
        assertEquals("live-body", first.body)

        live = ManifestAttempt.Failed(listOf(SourceFailure("u", "断网")))
        val second = resolver.resolve(listOf("u")) as ManifestResult.Ok
        assertEquals("内存优先于磁盘", ManifestOrigin.Memory, second.origin)
        assertEquals("live-body", second.body)
        assertEquals("回退时仍要带回每源原因", 1, second.failures.size)

        // 再起一个实例 = 新进程：内存空，磁盘新鲜。
        val third = ManifestResolver(ManifestStore(file, ttlMs = 60_000)) { live }
            .resolve(listOf("u")) as ManifestResult.Ok
        assertEquals(ManifestOrigin.Disk, third.origin)
        assertEquals("live-body", third.body)
    }

    @Test
    fun resolverFailsOnlyWhenEverythingIsGoneAndListsReasons() = runTest {
        val resolver = ManifestResolver(ManifestStore(null)) {
            ManifestAttempt.Failed(
                listOf(
                    SourceFailure("https://a/update.json", "dns 失败"),
                    SourceFailure("https://b/update.json", "超时 8000ms"),
                ),
            )
        }
        val result = resolver.resolve(listOf("https://a/update.json", "https://b/update.json"))
        assertTrue(result is ManifestResult.Failed)
        val message = describeFailures((result as ManifestResult.Failed).failures)
        assertNotNull(message)
        assertTrue(message.contains("dns 失败"))
        assertTrue(message.contains("超时 8000ms"))
    }
}
