package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.model.ChatMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 事件出口（T9.14 修 1）：丢要计数，关键事件一条都不许丢。 */
class EventSinkTest {

    /**
     * 有界缓冲 + 卡住的消费者：前 capacity 条入队，其余全部丢并计数。
     *
     * 注意必须先挂上 collector 再发：SharedFlow 在没有任何订阅者时 tryEmit 恒返回 true
     * （直接丢弃、不计数），只有存在未消费的订阅者时缓冲才会真的满。
     */
    @Test
    fun `bounded buffer drops overflow and counts it`() = runBlocking {
        val sink = EventSink<Int>(capacity = 4)
        val gate = CompletableDeferred<Unit>()
        val got = mutableListOf<Int>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            sink.events.collect {
                got += it
                gate.await()
            }
        }
        try {
            repeat(4) { assertTrue("第 " + (it + 1) + " 条应入队", sink.emit(it)) }
            repeat(6) { assertFalse("缓冲满后必须返回 false", sink.emit(100 + it)) }
            assertEquals(6L, sink.droppedEventCount)

            // 消费者放行后，挂起发送仍能送达（关键事件不会因为前面丢过而卡死）。
            gate.complete(Unit)
            withTimeout(5_000) { while (got.size < 4) delay(1) }
            assertEquals(listOf(0, 1, 2, 3), got)
            sink.emitOrAwait(99)
            withTimeout(5_000) { while (got.last() != 99) delay(1) }
            assertEquals(6L, sink.droppedEventCount)
        } finally {
            collector.cancelAndJoin()
        }
    }

    /** 零订阅者：SharedFlow 把值直接扔掉却返回 true，必须单独计进 lost（不混进 dropped）。 */
    @Test
    fun `events sent with no subscriber are counted as lost`() = runBlocking {
        val sink = EventSink<Int>(capacity = 8)
        repeat(3) { assertTrue(sink.emit(it)) }
        assertEquals(3L, sink.lostEventCount)
        assertEquals(0L, sink.droppedEventCount)

        // 关键事件在零订阅者下同样没人接：不许假装送达。
        sink.emitOrAwait(9)
        assertEquals(4L, sink.lostEventCount)
        assertEquals(0L, sink.droppedEventCount)
    }

    @Test
    fun `slow collector makes non-critical send drop and count`() = runBlocking {
        val sink = EventSink<Int>(capacity = 1)
        // collector 收到第一条就卡住：缓冲 1 格被占满，后面的非挂起发送必然溢出。
        val gate = CompletableDeferred<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            sink.events.collect { gate.await() }
        }
        try {
            assertTrue(sink.emit(1))
            assertFalse("缓冲已满时非挂起发送必须返回 false", sink.emit(2))
            assertFalse(sink.emit(3))
            assertEquals(2L, sink.droppedEventCount)
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun `critical send waits for the collector and never drops`() = runBlocking {
        val sink = EventSink<Int>(capacity = 1)
        val got = mutableListOf<Int>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            sink.events.collect {
                got += it
                delay(5)
            }
        }
        try {
            repeat(5) { sink.emitOrAwait(it) }
            withTimeout(5_000) { while (got.size < 5) delay(5) }
            assertEquals(listOf(0, 1, 2, 3, 4), got)
            assertEquals(0L, sink.droppedEventCount)
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun `local engine transport exposes a zero drop counter`() {
        val transport = LocalEngineTransport(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            configProvider = { LlmConfig("engine://m", "k", "m") },
            historyProvider = { emptyList<ChatMessage>() },
            shellExec = { "ok" },
        )
        assertEquals(0L, transport.droppedEventCount)
    }
}
