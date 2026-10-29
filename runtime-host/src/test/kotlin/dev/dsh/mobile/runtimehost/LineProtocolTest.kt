package dev.dsh.mobile.runtimehost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LineProtocolTest {

    @Test
    fun encodeCarriesIdMethodAndParams() {
        val line = LineProtocol.encode("plugins.load", JSONObject().put("dir", "/tmp/p"), "7")
        val obj = JSONObject(line)
        assertEquals("7", obj.getString("id"))
        assertEquals("plugins.load", obj.getString("method"))
        assertEquals("/tmp/p", obj.getJSONObject("params").getString("dir"))
        assertTrue("encode 不能带换行", !line.contains("\n"))
    }

    @Test
    fun encodeOmitsEmptyParams() {
        val obj = JSONObject(LineProtocol.encode("ping", JSONObject(), "1"))
        assertEquals("ping", obj.getString("method"))
        assertTrue(!obj.has("params"))
    }

    @Test
    fun decodeOkResult() {
        val reply = LineProtocol.decode("""{"id":"3","ok":true,"result":{"entries":[1,2]}}""").getOrThrow()
        assertTrue(reply is LineProtocol.Reply.Ok)
        reply as LineProtocol.Reply.Ok
        assertEquals("3", reply.id)
        assertEquals(2, reply.result.getJSONArray("entries").length())
    }

    @Test
    fun decodeErrorKeepsStageAndMessage() {
        val reply = LineProtocol.decode(
            """{"id":"4","ok":false,"error":{"stage":"rpc","message":"未注册 RPC 路径"}}""",
        ).getOrThrow()
        assertTrue(reply is LineProtocol.Reply.Error)
        reply as LineProtocol.Reply.Error
        assertEquals("rpc", reply.stage)
        assertEquals("未注册 RPC 路径", reply.message)
    }

    @Test
    fun decodeErrorWithoutStageFallsBackToInternal() {
        val reply = LineProtocol.decode("""{"id":null,"ok":false,"error":{}}""").getOrThrow()
        reply as LineProtocol.Reply.Error
        assertNull(reply.id)
        assertEquals("internal", reply.stage)
        assertTrue(reply.message.isNotEmpty())
    }

    @Test
    fun decodeMalformedJsonFails() {
        val failure = LineProtocol.decode("{not json").exceptionOrNull()
        assertTrue(failure is NodeEngineException)
        assertEquals("parse", (failure as NodeEngineException).stage)
    }

    @Test
    fun decodeBlankLineFails() {
        assertTrue(LineProtocol.decode("   ").isFailure)
    }

    @Test
    fun errorReplyMapsToEngineException() {
        val reply = LineProtocol.decode("""{"id":"9","ok":false,"error":{"stage":"load","message":"boom"}}""")
            .getOrThrow() as LineProtocol.Reply.Error
        val ex = NodeEngineException(reply.stage, reply.message)
        assertEquals("load", ex.stage)
        assertEquals("boom", ex.message)
    }

    @Test
    fun readMatchingReplySkipsStaleLinesAndReturnsMatching() {
        val lines = listOf(
            """{"id":"1","ok":true,"result":{"n":1}}""",   // 迟到：上一轮
            """{"id":"999","ok":true,"result":{"n":99}}""", // 迟到：别的 call
            """{"id":"7","ok":true,"result":{"n":7}}""",   // 本轮
        ).iterator()
        val stale = mutableListOf<String?>()
        val reply = LineProtocol.readMatchingReply(
            readLine = { if (lines.hasNext()) lines.next() else null },
            expectedId = "7",
            onStale = { stale += it },
        )
        assertTrue(reply is LineProtocol.Reply.Ok)
        assertEquals("7", reply.id)
        assertEquals(7, (reply as LineProtocol.Reply.Ok).result.getInt("n"))
        assertEquals(listOf("1", "999"), stale)
    }

    @Test
    fun readMatchingReplyReturnsErrorWithMatchingId() {
        val reply = LineProtocol.readMatchingReply(
            readLine = { """{"id":"7","ok":false,"error":{"stage":"load","message":"boom"}}""" },
            expectedId = "7",
        )
        assertTrue(reply is LineProtocol.Reply.Error)
        assertEquals("load", (reply as LineProtocol.Reply.Error).stage)
    }

    @Test
    fun readMatchingReplySkipsNullIdLines() {
        val lines = listOf(
            """{"id":null,"ok":true,"result":{"event":true}}""",
            """{"id":"7","ok":true,"result":{"n":7}}""",
        ).iterator()
        val reply = LineProtocol.readMatchingReply(
            readLine = { if (lines.hasNext()) lines.next() else null },
            expectedId = "7",
        )
        assertEquals("7", reply.id)
    }

    @Test
    fun readMatchingReplyThrowsOnEof() {
        val failure = runCatching { LineProtocol.readMatchingReply({ null }, "7") }.exceptionOrNull()
        assertTrue(failure is IOException)
    }
}
