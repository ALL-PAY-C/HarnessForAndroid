package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.common.PendingImageQueue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T4.4 回图像：截图登记队列的限额/过期语义，以及「队列为空时 user content 逐字节不变」。
 * 队列走真实现（core:common），parts 断言走 request 装配用的同一个 buildUserContent。
 */
class QueuedScreenshotTest {

    /** 固定基准时间：过期判定全靠传给队列的 nowMs，不碰系统时钟。 */
    private val base = 1_700_000_000_000L

    @Before
    fun reset() {
        PendingImageQueue.clear()
    }

    @Test
    fun capacityKeepsNewestTwo() {
        // 「3 张只出 2 张」：满了顶掉最老的，留下最新的两张。
        listOf("/tmp/a.png", "/tmp/b.png", "/tmp/c.png").forEachIndexed { i, p ->
            assertTrue("第 $i 张应入队", PendingImageQueue.enqueue(p, "image/png", 1024L, base + i).queued)
        }
        val got = PendingImageQueue.drainForNextTurn(2, base + 10)
        assertEquals(listOf("/tmp/b.png", "/tmp/c.png"), got.map { it.path })
        // 取走即出队：同一批不会串到下一轮。
        assertTrue(PendingImageQueue.drainForNextTurn(2, base + 10).isEmpty())
    }

    @Test
    fun expiredIsDropped() {
        PendingImageQueue.enqueue("/tmp/a.png", "image/png", 1024L, base)
        // 超过 30 秒：丢，且不注入。
        assertTrue(PendingImageQueue.drainForNextTurn(2, base + PendingImageQueue.TTL_MS + 1).isEmpty())
        // 未过期：照常注入。
        PendingImageQueue.enqueue("/tmp/b.png", "image/png", 1024L, base)
        assertEquals(1, PendingImageQueue.drainForNextTurn(2, base + PendingImageQueue.TTL_MS - 1).size)
    }

    @Test
    fun oversizedOrEmptyRejectedWithReason() {
        val big = PendingImageQueue.enqueue("/tmp/big.png", "image/png", PendingImageQueue.MAX_BYTES + 1, base)
        assertFalse(big.queued)
        assertNotNull("未入队必须给原因", big.reason)
        val zero = PendingImageQueue.enqueue("/tmp/zero.png", "image/png", 0L, base)
        assertFalse(zero.queued)
        assertNotNull(zero.reason)
        assertTrue(PendingImageQueue.drainForNextTurn(2, base).isEmpty())
    }

    @Test
    fun emptyQueueKeepsUserContentIdentical() {
        // 队列为空时 content 与改造前逐字节一致：纯文本仍是字符串，不是 parts 数组。
        assertEquals(JsonPrimitive("你好"), buildUserContent("你好", emptyList()))
        assertEquals(JsonPrimitive("你好"), buildUserContent("你好", emptyList<String>() + emptyList<String>()))
        // 带图但读不到文件时：仍是 1 段 text，正文前缀与丢图说明保持原样。
        val parts = buildUserContent("看这张图", listOf("/nonexistent/a.png") + emptyList<String>()) as JsonArray
        assertEquals(1, parts.size)
        val text = parts[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.startsWith("看这张图"))
        assertTrue(text.contains("附件（本地绝对路径"))
        assertTrue(text.contains("这几张图没能随请求发出"))
    }
}
