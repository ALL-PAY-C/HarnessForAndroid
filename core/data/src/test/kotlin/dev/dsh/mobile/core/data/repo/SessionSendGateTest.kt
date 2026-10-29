package dev.dsh.mobile.core.data.repo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 锁定同会话发送串行化。[SessionSendGate] 是 H4 修复的核心：没有它，SendQueue 的排空、
 * 「立刻发」与 ChatViewModel 的直接发可以并发 seed 同一会话、互相 clobber 轮次
 * （TurnLedger.seed 无条件覆盖当前轮）。
 *
 * 用真实线程（Dispatchers.Default）而非虚拟时钟：虚拟时钟单线程调度会掩盖竞态，让
 * 「没有锁也照样过」。
 */
class SessionSendGateTest {

    @Test
    fun sameKeySerializesConcurrentBlocks() = runBlocking {
        val gate = SessionSendGate()
        var inSection = 0
        var maxConcurrent = 0
        var value = 0
        val jobs = (1..16).map {
            launch(Dispatchers.Default) {
                gate.withLock("s") {
                    inSection++
                    maxConcurrent = maxOf(maxConcurrent, inSection)
                    val v = value
                    yield() // 让出，放大竞态窗口
                    value = v + 1
                    inSection--
                }
            }
        }
        jobs.joinAll()
        assertEquals("读改写被并发破坏（丢更新）", 16, value)
        assertEquals("临界区被并发进入", 1, maxConcurrent)
    }

    @Test
    fun differentKeysDoNotBlockEachOther() = runBlocking {
        val gate = SessionSendGate()
        val aHolds = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val a = launch(Dispatchers.Default) {
            gate.withLock("a") {
                aHolds.complete(Unit)
                releaseA.await()
            }
        }
        aHolds.await() // a 已持锁并阻塞
        val bDone = CompletableDeferred<Unit>()
        val b = launch(Dispatchers.Default) {
            gate.withLock("b") { bDone.complete(Unit) }
        }
        val finished = withTimeoutOrNull(1_000) { bDone.await() }
        assertNotNull("不同 key 不应被 a 阻塞", finished)
        releaseA.complete(Unit)
        a.join()
        b.join()
    }

    /**
     * m00619：withLockOrNull 只给「等锁」加时限；拿到锁后 block 在超时外执行。
     * 正常整轮 prompt（最长 30min）绝不被 20s 的 gate 超时截断。
     * 反例：若把 block 一起包进 withTimeoutOrNull，500ms 会截断 1000ms 的 block → 返回 null。
     */
    @Test
    fun withLockOrNullDoesNotTruncateBlockLongerThanTimeout() = runBlocking {
        val gate = SessionSendGate()
        val aHolds = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val a = launch(Dispatchers.Default) {
            gate.withLock("s") {
                aHolds.complete(Unit)
                releaseA.await()  // a 持锁阻塞
            }
        }
        aHolds.await()

        val b = async(Dispatchers.Default) {
            gate.withLockOrNull("s", 500) {
                delay(1000)  // block 比 timeout 长
                "ok"
            }
        }
        delay(50)            // 让 b 进入等锁
        releaseA.complete(Unit)  // 放锁，b 应在 500ms 内拿到

        val r = withTimeoutOrNull(5_000) { b.await() }
        assertNotNull("b 应拿到锁并跑完整段 block，而非被 500ms 截断为 null", r)
        assertEquals("ok", r)
        a.join()
    }
}
