package dev.dsh.mobile.toolbridge

import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可选 `channel` 参数与 `channel_list()`。
 *
 * 两条不能破的底线：
 * 1. 不传 channel 时行为与旧的 exec() **逐字节一致**（默认必须还是"自动顺序"，不能悄悄变成只走 proot）；
 * 2. channel_list 在没有 Shizuku / 没有 root / 没有 proot 时，给的是「false + 一句人话」，不是异常。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellChannelParamTest {

    /** 包一层真调度器，好数「这条命令到底有没有被派到后台执行」。 */
    private class CountingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        val dispatches = AtomicInteger()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches.incrementAndGet()
            delegate.dispatch(context, block)
        }
    }

    private class FakeChannel(
        override val id: String,
        var availableNow: Boolean = true,
        var detailText: String = id + " ok",
    ) : ShellChannel {
        override val label: String = id
        override val kind: ChannelKind = ChannelKind.App
        val execCalls = AtomicInteger()
        var lastCommand: String? = null
        var ranOn: CoroutineDispatcher? = null
        var result: ShellResult = ShellResult(0, id + "-out", "")

        override fun available(): Boolean = availableNow
        override fun detail(): String = detailText
        override suspend fun exec(command: String, timeoutMs: Long): ShellResult {
            execCalls.incrementAndGet()
            lastCommand = command
            ranOn = currentCoroutineContext()[ContinuationInterceptor] as? CoroutineDispatcher
            return result
        }
    }

    // —— channel 参数映射 ——

    @Test
    fun channelNameMappingKeepsUnknownNamesOnTheOldPath() = runTest {
        val bridge = ShellBridge(listOf(FakeChannel("app")), StandardTestDispatcher(testScheduler), backgroundScope)

        assertNull(bridge.channelIdFor(null))
        assertNull(bridge.channelIdFor(""))
        assertNull(bridge.channelIdFor("  "))
        assertNull(bridge.channelIdFor("auto"))
        assertNull(bridge.channelIdFor("写错了的名字"))
        assertEquals("proot", bridge.channelIdFor("proot"))
        assertEquals("app", bridge.channelIdFor("APP"))
        assertEquals("shizuku", bridge.channelIdFor(" Shizuku "))
        assertEquals("root", bridge.channelIdFor("root"))
    }

    @Test
    fun defaultChannelIsByteIdenticalToOldExec() = runTest {
        val app = FakeChannel("app")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val bridge = ShellBridge(listOf(app), dispatcher, CoroutineScope(SupervisorJob() + dispatcher))

        val legacy = bridge.exec("ls /sdcard")
        val withNull = bridge.execChannel("ls /sdcard", channel = null)

        assertEquals(legacy, withNull)
        assertEquals(2, app.execCalls.get())
        assertEquals("ls /sdcard", app.lastCommand)
        // 旧路径不该被新参数偷偷套上 probeDispatcher：它仍在调用方上下文里执行。
        assertNotSame("exec() 的执行体不应被搬到 probeDispatcher 上", dispatcher, app.ranOn)
    }

    // —— 显式通道分派 ——

    @Test
    fun explicitAppChannelRunsAppChannelEvenWhenProotIsAvailable() = runTest {
        val proot = FakeChannel("proot", availableNow = true)
        val app = FakeChannel("app")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val bridge = ShellBridge(listOf(proot, app), dispatcher, CoroutineScope(SupervisorJob() + dispatcher))

        val result = bridge.execChannel("ls /sdcard", channel = "app")

        assertEquals("app-out", result.stdout)
        assertEquals(1, app.execCalls.get())
        assertEquals(0, proot.execCalls.get())
    }

    @Test
    fun namedChannelThatIsUnavailableFallsBackToTheAutoOrder() = runTest {
        val proot = FakeChannel("proot", availableNow = false, detailText = "Linux 环境未激活")
        val app = FakeChannel("app")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val bridge = ShellBridge(listOf(proot, app), dispatcher, CoroutineScope(SupervisorJob() + dispatcher))

        val result = bridge.execChannel("ls /sdcard", channel = "proot")

        assertEquals("app-out", result.stdout)
        assertEquals(0, proot.execCalls.get())
        assertEquals(1, app.execCalls.get())
    }

    @Test
    fun commandRunsOnTheProbeDispatcherNotTheCaller() = runTest {
        val app = FakeChannel("app")
        val caller = StandardTestDispatcher(testScheduler)
        val probe = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val bridge = ShellBridge(listOf(app), probe, CoroutineScope(SupervisorJob() + caller))

        val before = probe.dispatches.get()
        bridge.execChannel("ls /sdcard", channel = "app")

        assertTrue("命令应被派到 probeDispatcher 上执行", probe.dispatches.get() > before)
        assertSame("执行体必须落在 probeDispatcher 上，而不是调用方线程", probe, app.ranOn)
    }

    // —— channel_list ——

    @Test
    fun channelListReportsThreeUnusableChannelsWhenNothingIsInstalled() = runTest {
        val app = FakeChannel("app", detailText = "内置 busybox")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val bridge = ShellBridge(listOf(app), dispatcher, CoroutineScope(SupervisorJob() + dispatcher))

        val list = bridge.channelList()

        assertEquals(listOf("proot", "app", "shizuku", "root"), list.map { it.name })
        val unusable = list.filter { !it.available }
        assertEquals("Shizuku / root / proot 都没装时应正好三项不可用", 3, unusable.size)
        assertEquals(listOf("proot", "shizuku", "root"), unusable.map { it.name })
        for (entry in unusable) {
            assertTrue("不可用必须带可读 reason：" + entry.name, entry.reason.isNotBlank())
        }
        val appEntry = list.first { it.name == "app" }
        assertTrue(appEntry.available)
        assertEquals("内置 busybox", appEntry.reason)
    }

    @Test
    fun channelListPassesChannelOwnReasonThrough() = runTest {
        val shizuku = FakeChannel("shizuku", availableNow = false, detailText = "Shizuku 服务未运行")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val bridge = ShellBridge(listOf(FakeChannel("app"), shizuku), dispatcher, CoroutineScope(SupervisorJob() + dispatcher))

        val entry = bridge.channelList().first { it.name == "shizuku" }

        assertFalse(entry.available)
        assertEquals("Shizuku 服务未运行", entry.reason)
    }

    @Test
    fun channelListJsonIsDirectlyUsableAsToolOutput() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val bridge = ShellBridge(listOf(FakeChannel("app")), dispatcher, CoroutineScope(SupervisorJob() + dispatcher))

        val json = bridge.channelListJson()

        assertTrue(json, json.startsWith("[{\"name\":\"proot\",\"available\":false,\"reason\":\""))
        assertTrue(json, json.contains("\"app\",\"available\":true"))
        assertTrue(json, json.endsWith("}]"))
    }
}
