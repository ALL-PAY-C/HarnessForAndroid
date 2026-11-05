package dev.dsh.mobile.core.protocol.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分帧是纯文本函数：跨 chunk 截断与 CRLF 都在这里锁死。 */
class SseFramerTest {

    @Test
    fun singleEvent() {
        val result = SseFramer.frame("event: message\ndata: hello\n\n")
        assertEquals(1, result.events.size)
        assertEquals("message", result.events[0].event)
        assertEquals("hello", result.events[0].data)
        assertEquals("", result.rest)
    }

    @Test
    fun multipleEventsInOneChunk() {
        val result = SseFramer.frame("data: a\n\ndata: b\n\n")
        assertEquals(listOf("a", "b"), result.events.map { it.data })
    }

    @Test
    fun truncatedAcrossChunks() {
        val first = SseFramer.frame("data: hel")
        assertTrue(first.events.isEmpty())
        assertEquals("data: hel", first.rest)

        val second = SseFramer.frame(first.rest + "lo\n\n")
        assertEquals(1, second.events.size)
        assertEquals("hello", second.events[0].data)
    }

    @Test
    fun commentLinesAreIgnored() {
        val result = SseFramer.frame(": keep-alive\n\ndata: x\n\n")
        assertEquals(1, result.events.size)
        assertEquals("x", result.events[0].data)
    }

    @Test
    fun crlfSeparators() {
        val result = SseFramer.frame("event: rpc\r\ndata: {\"a\":1}\r\n\r\n")
        assertEquals(1, result.events.size)
        assertEquals("rpc", result.events[0].event)
        assertEquals("{\"a\":1}", result.events[0].data)
    }

    @Test
    fun trailingCrIsKeptForNextChunk() {
        val first = SseFramer.frame("data: a\r")
        assertTrue(first.events.isEmpty())
        assertEquals("data: a\r", first.rest)

        val second = SseFramer.frame(first.rest + "\n\r\n")
        assertEquals(1, second.events.size)
        assertEquals("a", second.events[0].data)
    }

    @Test
    fun multiLineDataIsJoinedWithNewline() {
        val result = SseFramer.frame("data: line1\ndata: line2\n\n")
        assertEquals("line1\nline2", result.events[0].data)
    }
}
