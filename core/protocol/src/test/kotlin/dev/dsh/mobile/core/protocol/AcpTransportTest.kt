package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.model.TransportKind
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [AcpTransport] over a fake stdio channel: proves the handshake, request
 * correlation and session parsing without any socket or process.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AcpTransportTest {

    private class FakeChannel : LineChannel {
        private val inbox = Channel<String>(Channel.UNLIMITED)
        override val incoming: Flow<String> = inbox.receiveAsFlow()
        override val description: String = "fake"

        var sent = mutableListOf<String>()
        /** 命中这些 method 的请求不回包，用于模拟「在途请求 + 断线」场景。 */
        var dropMethods: Set<String> = emptySet()
        /** session/prompt 响应前先注入的 chunk（模拟 quiescence：旧 chunk 先于终态）。 */
        var promptChunksBeforeResponse: List<String> = emptyList()

        override suspend fun open() = Unit
        override suspend fun close() = Unit

        override suspend fun send(line: String) {
            sent += line
            val req = DshJson.parseToJsonElement(line).jsonObject
            val id = req["id"]?.jsonPrimitive?.content
            val method = req["method"]?.jsonPrimitive?.content
            if (method in dropMethods) return
            val result: JsonObject = when (method) {
                "initialize" -> buildJsonObject {
                    put("protocolVersion", 1)
                    put("agentInfo", buildJsonObject {
                        put("name", "deepseek-harness-acp")
                        put("version", "0.0.1")
                    })
                }
                "session/list" -> buildJsonObject {
                    put("sessions", kotlinx.serialization.json.JsonArray(listOf()))
                }
                "session/prompt" -> {
                    promptChunksBeforeResponse.forEach { inbox.trySend(it) }
                    buildJsonObject { put("stopReason", "end_turn") }
                }
                else -> buildJsonObject { }
            }
            if (id != null) {
                inbox.trySend(
                    """{"jsonrpc":"2.0","id":$id,"result":$result}""",
                )
            }
        }

        /** 模拟底层通道关闭（socket 断开），让 readLoop 的 incoming flow 完成。 */
        fun closeIncoming() = inbox.close()
    }

    @Test
    fun `connect performs initialize and reports ready`() = runTest {
        val channel = FakeChannel()
        val transport = AcpTransport(
            channelProvider = { channel },
            kind = TransportKind.InProcess,
            scope = backgroundScope,
            defaultCwd = { "/tmp" },
        )

        transport.connect()

        val state = transport.state.value
        assertTrue("expected Ready but was $state", state is RuntimeState.Ready)
        assertEquals(TransportKind.InProcess, (state as RuntimeState.Ready).transport)
        assertTrue(channel.sent.first().contains("\"initialize\""))
        transport.disconnect()
    }

    @Test
    fun `listSessions returns empty list from agent`() = runTest {
        val channel = FakeChannel()
        val transport = AcpTransport(
            channelProvider = { channel },
            kind = TransportKind.Loopback,
            scope = backgroundScope,
        )
        transport.connect()
        val sessions = transport.listSessions("/tmp")
        assertEquals(0, sessions.size)
        transport.disconnect()
    }

    @Test
    fun `pending prompt fails promptly when channel closes`() = runTest {
        val channel = FakeChannel().apply { dropMethods = setOf("session/prompt") }
        val transport = AcpTransport(
            channelProvider = { channel },
            kind = TransportKind.InProcess,
            scope = backgroundScope,
            reconnectPolicy = ReconnectPolicy(enabled = false),
            defaultCwd = { "/tmp" },
        )

        transport.connect()

        val prompt = async {
            try {
                transport.prompt("s1", "hi")
                null
            } catch (e: IllegalStateException) {
                e
            }
        }
        // 让 prompt 先登记进 pending 并停在 await()，再断开，避免 failAllPending 跑在登记之前
        testScheduler.runCurrent()
        channel.closeIncoming()
        testScheduler.advanceUntilIdle()

        val err = prompt.await()
        assertTrue("expected IllegalStateException but got $err", err is IllegalStateException)
        assertTrue("message 应含断线原因", err?.message?.contains("连接已关闭") == true)
        transport.disconnect()
    }

    @Test
    fun `replyPermission throws on unknown requestId`() = runTest {
        val channel = FakeChannel()
        val transport = AcpTransport(
            channelProvider = { channel },
            kind = TransportKind.InProcess,
            scope = backgroundScope,
            reconnectPolicy = ReconnectPolicy(enabled = false),
        )

        transport.connect()

        val thrown = try {
            transport.replyPermission("s1", "no-such-request", "allow")
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertTrue("expected IllegalStateException but replyPermission returned", thrown != null)
        assertTrue("message 应含未知 requestId", thrown?.message?.contains("no-such-request") == true)
        transport.disconnect()
    }

    // ------------------------------------------------------------------- P5 barrier

    private fun chunkLine(sessionId: String, text: String) =
        """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"$sessionId","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"$text"}}}}"""

    @Test
    fun `prompt turnId waits for collector to consume chunk before returning`() = runTest {
        val channel = FakeChannel().apply {
            promptChunksBeforeResponse = listOf(chunkLine("s1", "chunk-A"))
        }
        val transport = AcpTransport(
            channelProvider = { channel },
            kind = TransportKind.InProcess,
            scope = backgroundScope,
            reconnectPolicy = ReconnectPolicy(enabled = false),
            defaultCwd = { "/tmp" },
        )
        transport.connect()

        val received = mutableListOf<DshEvent>()
        backgroundScope.launch {
            transport.events.collect { e ->
                received += e
                if (e is DshEvent.TurnEnd) e.turnId?.let { transport.ackTurn(it) }
            }
        }
        testScheduler.runCurrent()

        val result = transport.prompt("s1", "hi", emptyList(), turnId = "t1")
        assertEquals("end_turn", result)

        val chunkIdx = received.indexOfFirst { it is DshEvent.MessageChunk }
        val turnIdx = received.indexOfFirst { it is DshEvent.TurnEnd }
        assertTrue("chunk 应先于 TurnEnd 被消费，got $received", chunkIdx >= 0 && turnIdx > chunkIdx)
        transport.disconnect()
    }

    @Test
    fun `prompt turnId without collector fails explicitly instead of hanging`() = runTest {
        val channel = FakeChannel()
        val transport = AcpTransport(
            channelProvider = { channel },
            kind = TransportKind.InProcess,
            scope = backgroundScope,
            reconnectPolicy = ReconnectPolicy(enabled = false),
            defaultCwd = { "/tmp" },
        )
        transport.connect()

        val err = async {
            try {
                transport.prompt("s1", "hi", emptyList(), turnId = "t1")
                null
            } catch (e: IllegalStateException) {
                e
            }
        }
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(31_000)
        testScheduler.runCurrent()

        val thrown = err.await()
        assertTrue("expected IllegalStateException but got $thrown", thrown is IllegalStateException)
        assertTrue("message 应含终态消费超时", thrown?.message?.contains("终态消费超时") == true)
        transport.disconnect()
    }

    @Test
    fun `cancel sends notification without id and does not block`() = runTest {
        val channel = FakeChannel()
        val transport = AcpTransport(
            channelProvider = { channel },
            kind = TransportKind.InProcess,
            scope = backgroundScope,
            reconnectPolicy = ReconnectPolicy(enabled = false),
            defaultCwd = { "/tmp" },
        )
        transport.connect()

        transport.cancel("s1")

        val cancelLine = channel.sent.first { it.contains("session/cancel") }
        assertTrue("cancel 应是通知（无 id）：$cancelLine", !cancelLine.contains("\"id\""))
        transport.disconnect()
    }

    @Test
    fun `cancel notification then new prompt turnId still gets barrier`() = runTest {
        val channel = FakeChannel()
        val transport = AcpTransport(
            channelProvider = { channel },
            kind = TransportKind.InProcess,
            scope = backgroundScope,
            reconnectPolicy = ReconnectPolicy(enabled = false),
            defaultCwd = { "/tmp" },
        )
        transport.connect()

        transport.cancel("s1")

        val received = mutableListOf<DshEvent>()
        backgroundScope.launch {
            transport.events.collect { e ->
                received += e
                if (e is DshEvent.TurnEnd) e.turnId?.let { transport.ackTurn(it) }
            }
        }
        testScheduler.runCurrent()

        val result = transport.prompt("s1", "hi", emptyList(), turnId = "t2")
        assertEquals("end_turn", result)
        assertTrue("新轮应产生带 turnId 的 TurnEnd marker", received.any { it is DshEvent.TurnEnd && it.turnId == "t2" })
        transport.disconnect()
    }
}
