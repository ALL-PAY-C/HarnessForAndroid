package dev.dsh.mobile.core.data.repo

import dev.dsh.mobile.core.common.DshResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * [SendQueueEngine] 的真实行为回归（非仅互斥测试）：用真实线程 + CompletableDeferred 握手，
 * 确定性验证三个根因修复：
 *  1. 延迟 cancel 时 Steer 优先于排队项（M5 让路位）。
 *  2. clear 后已摘出的条目不会发送（N1 代际）。
 *  3. 不同会话的 drain 互不阻塞（N3 跨会话）。
 */
class SendQueueEngineTest {

    private fun sentList(): MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Test
    fun steerPriorityOverDrainWhenCancelIsSlow() = runBlocking {
        val sent = sentList()
        val firstSendStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val cancelStarted = CompletableDeferred<Unit>()
        val releaseCancel = CompletableDeferred<Unit>()

        val engine = SendQueueEngine(
            sendItem = { _, item ->
                sent.add(item.text)
                if (item.text == "first") {
                    firstSendStarted.complete(Unit)
                    releaseFirst.await()
                }
                DshResult.Ok(Unit)
            },
            cancel = { cancelStarted.complete(Unit); releaseCancel.await(); DshResult.Ok(Unit) },
        )

        engine.enqueue("s", "queued1", emptyList(), 10)
        engine.enqueue("s", "queued2", emptyList(), 10)

        // A：sendText 阻塞在发送锁里，模拟整轮生成。
        val a = launch(Dispatchers.Default) { engine.sendText("s", "first") }
        firstSendStarted.await()

        // B：Steer 先置让路位再 cancel（cancel 很慢）。
        val b = launch(Dispatchers.Default) { engine.cancelAndSend("s", "steer") }
        cancelStarted.await()

        // 让路位已置：此刻发起的 drain 必须在快路径退让，不摘队列、不发排队项。
        val d = launch(Dispatchers.Default) { engine.drain("s") }
        d.join() // d 不依赖任何信号，立刻 break 并清掉 draining 标记

        releaseCancel.complete(Unit)
        releaseFirst.complete(Unit)
        a.join(); b.join()

        assertEquals(listOf("first", "steer", "queued1", "queued2"), sent.toList())
    }

    @Test
    fun clearAfterTakeDoesNotSend() = runBlocking {
        val sent = sentList()
        val firstSendStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val taken = CompletableDeferred<Unit>()

        var engineRef: SendQueueEngine? = null
        val engine = SendQueueEngine(
            sendItem = { _, item ->
                sent.add(item.text)
                if (item.text == "first") {
                    firstSendStarted.complete(Unit)
                    releaseFirst.await()
                }
                DshResult.Ok(Unit)
            },
            cancel = { DshResult.Ok(Unit) },
            // sendNow 摘出条目后队列变空，onChange 同步触发 → 以此确定「已摘出」时刻。
            onChange = { if (engineRef?.of("s")?.isEmpty() == true && !taken.isCompleted) taken.complete(Unit) },
        )
        engineRef = engine

        engine.enqueue("s", "queued", emptyList(), 10)
        val queuedId = engine.of("s").first().id

        // A：阻塞在发送锁里。
        val a = launch(Dispatchers.Default) { engine.sendText("s", "first") }
        firstSendStarted.await()

        // B：sendNow 摘出 queued（触发 taken），随后在发送锁外等锁。
        val b = launch(Dispatchers.Default) { engine.sendNow("s", queuedId) }
        taken.await() // 已确定摘出，但还没发

        // 此刻 queued 已被摘出、尚未发送。清空会话 → 推进代际 → 摘出的条目失效。
        engine.clear("s")

        releaseFirst.complete(Unit)
        a.join(); b.join()

        assertEquals(listOf("first"), sent.toList())
    }

    @Test
    fun drainReportsSendErrorWithSessionId() = runBlocking {
        // 错误出口最小回归：drain 里 sendItem 失败时，onSendError 必须收到 (sessionId, message)，
        // 而不是旧的只传 message（8c 按 sid 收口 + dismiss 语义依赖它）。
        val errors = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val engine = SendQueueEngine(
            sendItem = { _, _ -> DshResult.Err("boom") },
            cancel = { DshResult.Ok(Unit) },
            onSendError = { sid, msg -> errors.add(sid to msg) },
        )

        engine.enqueue("s", "queued", emptyList(), 10)
        engine.drain("s")

        assertEquals(listOf("s" to "boom"), errors.toList())
    }

    @Test
    fun differentSessionsDrainIndependently() = runBlocking {
        val sent = sentList()
        val aStarted = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val bSent = CompletableDeferred<Unit>()

        val engine = SendQueueEngine(
            sendItem = { sid, item ->
                sent.add(item.text)
                if (sid == "a") {
                    aStarted.complete(Unit)
                    releaseA.await()
                } else {
                    bSent.complete(Unit)
                }
                DshResult.Ok(Unit)
            },
            cancel = { DshResult.Ok(Unit) },
        )

        engine.enqueue("a", "a1", emptyList(), 10)
        engine.enqueue("b", "b1", emptyList(), 10)

        // a 会话的 drain 阻塞在发送锁里（整轮生成）。
        val da = launch(Dispatchers.Default) { engine.drain("a") }
        aStarted.await()

        // b 会话的 drain 必须独立完成，不被 a 阻塞。
        val db = launch(Dispatchers.Default) { engine.drain("b") }
        val bDone = withTimeoutOrNull(1_000) { bSent.await() }
        assertNotNull("drain('b') should not be blocked by drain('a')", bDone)

        releaseA.complete(Unit)
        da.join(); db.join()

        assertEquals(listOf("a1", "b1"), sent.toList())
    }

    @Test
    fun cancelFailureReturnsErrAndSkipsSteerSend() = runBlocking {
        val sent = sentList()
        val engine = SendQueueEngine(
            sendItem = { _, item -> sent.add(item.text); DshResult.Ok(Unit) },
            cancel = { DshResult.Err("取消未确认，远端可能仍在生成") },
        )

        val r = engine.cancelAndSend("s", "steer")
        assertTrue("cancelAndSend 应返回 Err，实际 $r", r is DshResult.Err)
        assertEquals("取消未确认，远端可能仍在生成", (r as DshResult.Err).message)
        assertTrue("steer 不应发送，实际 $sent", "steer" !in sent)
    }

    @Test
    fun sendNowCancelFailureKeepsQueueAndReportsError() = runBlocking {
        val sent = sentList()
        val errors = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val engine = SendQueueEngine(
            sendItem = { _, item -> sent.add(item.text); DshResult.Ok(Unit) },
            cancel = { DshResult.Err("取消未确认") },
            onSendError = { sid, msg -> errors.add(sid to msg) },
        )

        engine.enqueue("s", "queued", emptyList(), 10)
        val queuedId = engine.of("s").first().id

        engine.sendNow("s", queuedId)

        assertEquals(listOf("s" to "取消未确认"), errors.toList())
        assertEquals(1, engine.of("s").size)
        assertTrue("queued 不应发送，实际 $sent", sent.isEmpty())
    }
}
