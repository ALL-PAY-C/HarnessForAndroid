package dev.dsh.mobile.toolbridge

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * H5 回归：`ShellBridge.refresh()` 曾经同步执行，`available()/detail()` 里的阻塞
 * （root 通道 `su -c id` 等 4 秒、Shizuku binder、PackageManager IPC、busybox stat/chmod）
 * 全落在调用线程上 —— 而 `AgentsViewModel.init` 就是主线程。
 *
 * 这里用注入的通道 + 注入的调度器证明三件事：
 *  1. `refresh()` 立即返回，调用线程上一次通道探测都不会发生；
 *  2. 探测确实被派发到注入的 IO 调度器；
 *  3. `active()` 只读缓存、绝不再探测（它跑在 `Dispatchers.Main.immediate` 的 combine 里）。
 *
 * 全程不碰 Android Context：走 `ShellBridge(channels, probeDispatcher, scope)` 测试构造。
 */
class ShellBridgeDispatchTest {

    /** 记录「有工作被派发进来」，同时把执行交给测试调度器（不真起线程）。 */
    private class RecordingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        val dispatches = AtomicInteger()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches.incrementAndGet()
            delegate.dispatch(context, block)
        }
    }

    /**
     * 手动泵的调度器：把「探测正在飞」变成一个**可控**状态。
     * 有了它才能精确构造「探测在飞时又 register / 又 refresh」的并发场景，
     * 而不依赖真线程的时序运气。
     *
     * 关键：`withContext(probeDispatcher)` 当目标 dispatcher 与当前协程的 dispatcher 是
     * **同一个对象**时，会命中 fast path 直接内联执行（不派发），手动泵就控制不住「在飞」。
     * 所以 scope 与 probeDispatcher 必须用 [newDispatcher] 各取一个**不同对象**、共享
     * **同一条队列**，两个视图都能被 [runNext] / [runAll] 泵到。
     */
    private class ManualPump {
        private val queue = mutableListOf<Runnable>()

        /** 每次调用返回一个新 dispatcher 对象（同一共享队列），保证 withContext 走慢路径真正派发。 */
        fun newDispatcher(): CoroutineDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                queue += block
            }
        }

        /** 只跑一步：用来让探测走到 `withContext` 里挂起（锁已持有，探测体排队）。 */
        fun runNext() {
            if (queue.isNotEmpty()) queue.removeAt(0).run()
        }

        fun runAll() {
            while (queue.isNotEmpty()) queue.removeAt(0).run()
        }
    }

    private class FakeChannel(
        override val id: String,
        override val kind: ChannelKind = ChannelKind.App,
        var availableNow: Boolean = true,
        var detailText: String = "$id ok",
    ) : ShellChannel {
        override val label: String = id
        val availableCalls = AtomicInteger()
        val detailCalls = AtomicInteger()
        val execCalls = AtomicInteger()
        var lastCommand: String? = null
        var result: ShellResult = ShellResult(0, "$id-out", "")

        override fun available(): Boolean {
            availableCalls.incrementAndGet()
            return availableNow
        }

        override fun detail(): String {
            detailCalls.incrementAndGet()
            return detailText
        }

        override suspend fun exec(command: String, timeoutMs: Long): ShellResult {
            execCalls.incrementAndGet()
            lastCommand = command
            return result
        }
    }

    private class ThrowingChannel(
        override val id: String,
        override val kind: ChannelKind = ChannelKind.Root,
    ) : ShellChannel {
        override val label = id
        override fun available(): Boolean = error("su 探测炸了")
        override fun detail(): String = error("detail 也炸了")
        override suspend fun exec(command: String, timeoutMs: Long) = ShellResult.unavailable("boom")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun refreshReturnsBeforeTouchingAnyChannelAndProbesOnTheInjectedDispatcher() = runTest {
        val dispatcher = RecordingDispatcher(StandardTestDispatcher(testScheduler))
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val app = FakeChannel("app")
        val bridge = ShellBridge(listOf(app), dispatcher, scope)

        bridge.refresh()

        // 关键断言：refresh() 已经返回，而通道一次都没被探测过 —— 没有主线程 IO。
        assertEquals("refresh() 不能同步探测通道", 0, app.availableCalls.get())
        assertEquals("refresh() 不能同步取 detail", 0, app.detailCalls.get())

        advanceUntilIdle()

        assertEquals(1, app.availableCalls.get())
        assertTrue("探测必须被派发到注入的 IO 调度器", dispatcher.dispatches.get() > 0)
        assertEquals(listOf("app"), bridge.status.value.map { it.id })
        assertEquals(true, bridge.status.value.single().available)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun refreshBurstCollapsesToOneProbeRound() = runTest {
        val dispatcher = RecordingDispatcher(StandardTestDispatcher(testScheduler))
        val bridge = ShellBridge(
            listOf(FakeChannel("app")),
            dispatcher,
            CoroutineScope(SupervisorJob() + dispatcher),
        )
        val app = bridge.byId("app") as FakeChannel

        bridge.refresh()
        bridge.refresh()
        bridge.refresh()
        advanceUntilIdle()

        // 叠三次就叠三次 su / binder 探测，正是 ANR 面；只允许一轮。
        assertEquals(1, app.availableCalls.get())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun probeKeepsLegacyErrorSemantics() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val bridge = ShellBridge(
            listOf(ThrowingChannel("root"), FakeChannel("app", detailText = "内置 busybox")),
            dispatcher,
            CoroutineScope(SupervisorJob() + dispatcher),
        )

        val probed = bridge.refreshNow()

        assertEquals(listOf("root", "app"), probed.map { it.id })
        assertEquals("available() 抛异常必须降级为不可用", false, probed[0].available)
        assertEquals("detail() 抛异常必须降级为空串", "", probed[0].detail)
        assertEquals(true, probed[1].available)
        assertEquals("内置 busybox", probed[1].detail)
        assertEquals(probed, bridge.status.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun activeReadsTheCacheWithoutReprobing() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val shizuku = FakeChannel("shizuku", kind = ChannelKind.Shizuku, availableNow = false)
        val root = FakeChannel("root", kind = ChannelKind.Root, availableNow = false)
        val termux = FakeChannel("termux", kind = ChannelKind.Termux, availableNow = true)
        val app = FakeChannel("app", availableNow = true)
        val bridge = ShellBridge(
            listOf(shizuku, root, termux, app),
            dispatcher,
            CoroutineScope(SupervisorJob() + dispatcher),
        )

        assertNull("未探测前 active() 返回 null，而不是顺手同步探测", bridge.active())

        bridge.refreshNow()
        val callsAfterProbe = shizuku.availableCalls.get() + root.availableCalls.get() +
            termux.availableCalls.get() + app.availableCalls.get()

        // 优先级 shizuku > root > app > termux：前两个不可用，app 排在 termux 之前。
        assertEquals("app", bridge.active()?.id)
        val callsAfterActive = shizuku.availableCalls.get() + root.availableCalls.get() +
            termux.availableCalls.get() + app.availableCalls.get()
        assertEquals("active() 不能重新探测通道（它在主线程的 combine 里被调用）", callsAfterProbe, callsAfterActive)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun autoOrderPrefersProotOverEveryOtherChannel() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val proot = FakeChannel("proot", kind = ChannelKind.Proot, availableNow = true)
        val app = FakeChannel("app", availableNow = true)
        val termux = FakeChannel("termux", kind = ChannelKind.Termux, availableNow = true)
        val bridge = ShellBridge(
            listOf(app, termux, proot),
            dispatcher,
            CoroutineScope(SupervisorJob() + dispatcher),
        )

        bridge.refreshNow()
        // 装了 Linux 环境就该走 proot：它给的是 guest 内的完整用户态，比宿主机 shell 更强。
        assertEquals("proot", bridge.active()?.id)

        // 环境没了（rootfs 被删/未激活）就自动落回原有顺序，不能因为注册过就卡死。
        proot.availableNow = false
        bridge.refreshNow()
        assertEquals("app", bridge.active()?.id)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun activeHonoursPreferredChannelFromCache() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val app = FakeChannel("app")
        val termux = FakeChannel("termux", kind = ChannelKind.Termux)
        val bridge = ShellBridge(listOf(app, termux), dispatcher, CoroutineScope(SupervisorJob() + dispatcher))
        bridge.refreshNow()

        bridge.preferred = "termux"
        assertEquals("termux", bridge.active()?.id)

        bridge.preferred = "auto"
        assertEquals("app", bridge.active()?.id)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun execAwaitsTheFirstProbeThenDelegates() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val app = FakeChannel("app")
        val bridge = ShellBridge(listOf(app), dispatcher, CoroutineScope(SupervisorJob() + dispatcher))

        val r = bridge.exec("id")

        assertEquals(0, r.code)
        assertEquals("app-out", r.stdout)
        assertEquals("id", app.lastCommand)
        assertEquals(1, app.execCalls.get())
        // 1 次来自首次探测，1 次来自执行前的可用性复查（TOCTOU 兜底）。
        assertEquals(2, app.availableCalls.get())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun execReportsUnavailableChannelWithoutChangingTheMessage() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val app = FakeChannel("app", availableNow = false, detailText = "系统 sh，作用域限于应用沙箱")
        val bridge = ShellBridge(listOf(app), dispatcher, CoroutineScope(SupervisorJob() + dispatcher))

        val r = bridge.exec("id")

        assertEquals(-1, r.code)
        assertEquals("没有可用的执行通道", r.stderr)
        assertEquals(0, app.execCalls.get())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun execRechecksAvailabilitySoAChannelThatDiedAfterTheProbeIsReported() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val app = FakeChannel("app")
        val bridge = ShellBridge(listOf(app), dispatcher, CoroutineScope(SupervisorJob() + dispatcher))

        bridge.refreshNow() // 探测时可用
        app.availableNow = false // 探测之后掉线：Shizuku 被杀 / 授权被撤 / su 消失
        app.detailText = "Shizuku 服务未运行"

        val r = bridge.exec("id")

        assertEquals(-1, r.code)
        assertEquals("app 不可用：Shizuku 服务未运行", r.stderr)
        assertEquals("不可用就别真跑命令", 0, app.execCalls.get())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun ensureProbedProbesOnlyOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val app = FakeChannel("app")
        val bridge = ShellBridge(listOf(app), dispatcher, CoroutineScope(SupervisorJob() + dispatcher))

        bridge.ensureProbed()
        bridge.ensureProbed()

        assertEquals(1, app.availableCalls.get())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun concurrentEnsureProbedCallsShareOneProbe() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val app = FakeChannel("app")
        val bridge = ShellBridge(listOf(app), dispatcher, CoroutineScope(SupervisorJob() + dispatcher))

        // 两个并发 ensureProbed（真实场景：init 的探测在飞时 exec 也进来要结果）
        val a = async { bridge.ensureProbed() }
        val b = async { bridge.ensureProbed() }
        advanceUntilIdle()

        // 缓存检查在锁内 → 第二个等到第一个落地后直接读缓存，不会自己再探一轮。
        assertEquals(1, app.availableCalls.get())
        assertEquals(a.await(), b.await())
        assertEquals(listOf("app"), bridge.status.value.map { it.id })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun registerWhileProbeIsInFlightIsPickedUpByTheFollowUpRound() = runTest {
        val pump = ManualPump()
        val scope = CoroutineScope(SupervisorJob() + pump.newDispatcher())
        val app = FakeChannel("app")
        val bridge = ShellBridge(listOf(app), pump.newDispatcher(), scope)

        scope.launch { bridge.refreshNow() }
        pump.runNext() // 第一轮已拿锁并进入 withContext，探测体还在队列里
        assertTrue("探测还没落地", bridge.status.value.isEmpty())

        val extra = FakeChannel("extra")
        bridge.register(extra) // 主线程追加通道 + 请求一轮新探测

        pump.runAll()

        // 第一轮用 register 之前的快照（app 只探 1 次），第二轮才带上 extra。
        // 全程无 ConcurrentModificationException、无并发探测。
        assertEquals(2, app.availableCalls.get())
        assertEquals(1, extra.availableCalls.get())
        assertEquals(listOf("app", "extra"), bridge.status.value.map { it.id })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun refreshBurstWhileProbeInFlightCostsAtMostOneExtraRound() = runTest {
        val pump = ManualPump()
        val scope = CoroutineScope(SupervisorJob() + pump.newDispatcher())
        val app = FakeChannel("app")
        val bridge = ShellBridge(listOf(app), pump.newDispatcher(), scope)

        scope.launch { bridge.refreshNow() }
        pump.runNext() // 第一轮在飞

        // 用户连点三次「重新探测」：CAS 只放行一次，其余合并。
        bridge.refresh()
        bridge.refresh()
        bridge.refresh()

        pump.runAll()

        // 在飞的一轮 + 合并后的一轮 = 2，不是 4。
        assertEquals(2, app.availableCalls.get())
    }
}
