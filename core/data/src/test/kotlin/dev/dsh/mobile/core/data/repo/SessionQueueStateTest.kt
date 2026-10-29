package dev.dsh.mobile.core.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SessionQueueState] 的纯逻辑单测：代际（generation）是 N1「clear 后已取条目不发」的
 * 核心机制。没有它，drain/sendNow 摘出的条目在 clear 之后依然会被发出去。
 */
class SessionQueueStateTest {

    private fun item(id: String) = SendQueue.Item(id = id, text = "t-$id")

    @Test
    fun enqueueRespectsLimitAndOrder() {
        val q = SessionQueueState()
        assertTrue(q.enqueue(item("a"), 2))
        assertTrue(q.enqueue(item("b"), 2))
        assertFalse(q.enqueue(item("c"), 2))
        assertEquals(listOf("a", "b"), q.snapshot().map { it.id })
        assertEquals(2, q.size)
    }

    @Test
    fun popReturnsItemWithCurrentGeneration() {
        val q = SessionQueueState()
        q.enqueue(item("a"), 10)
        val (popped, gen) = q.pop()!!
        assertEquals("a", popped.id)
        assertTrue(q.isCurrent(gen))
        assertNull(q.pop())
    }

    @Test
    fun clearAfterPopInvalidatesGeneration() {
        val q = SessionQueueState()
        q.enqueue(item("a"), 10)
        val (_, gen) = q.pop()!!
        assertTrue(q.isCurrent(gen)) // 弹出时仍有效
        q.clear()                    // 用户清空会话
        assertFalse(q.isCurrent(gen)) // 代际推进 → 已摘出的条目失效
    }

    @Test
    fun clearAfterTakeInvalidatesGeneration() {
        val q = SessionQueueState()
        q.enqueue(item("a"), 10)
        val (_, gen) = q.take("a")!!
        assertTrue(q.isCurrent(gen))
        q.clear()
        assertFalse(q.isCurrent(gen))
    }

    @Test
    fun clearEmptiesQueueAndBumpsGeneration() {
        val q = SessionQueueState()
        q.enqueue(item("a"), 10)
        val (_, genBefore) = q.pop()!!
        q.clear()
        assertEquals(0, q.size)
        assertNull(q.pop())
        q.enqueue(item("b"), 10)
        val (_, genAfter) = q.pop()!!
        assertTrue("clear 后新条目的代际应与旧代际不同", genAfter != genBefore)
        assertTrue(q.isCurrent(genAfter))
    }

    @Test
    fun promoteMovesItemToFront() {
        val q = SessionQueueState()
        q.enqueue(item("a"), 10)
        q.enqueue(item("b"), 10)
        assertTrue(q.promote("b"))
        assertEquals(listOf("b", "a"), q.snapshot().map { it.id })
        assertFalse(q.promote("missing"))
    }

    @Test
    fun dropRemovesItem() {
        val q = SessionQueueState()
        q.enqueue(item("a"), 10)
        q.enqueue(item("b"), 10)
        q.drop("a")
        assertEquals(listOf("b"), q.snapshot().map { it.id })
    }

    @Test
    fun takeRemovesAndReturnsMatchingItem() {
        val q = SessionQueueState()
        q.enqueue(item("a"), 10)
        q.enqueue(item("b"), 10)
        val (taken, gen) = q.take("a")!!
        assertEquals("a", taken.id)
        assertTrue(q.isCurrent(gen))
        assertEquals(listOf("b"), q.snapshot().map { it.id })
        assertNull(q.take("a"))
    }
}
