package dev.dsh.mobile.core.protocol.identity

import dev.dsh.mobile.core.model.TransportKind
import dev.dsh.mobile.core.protocol.AcpTransport
import dev.dsh.mobile.core.protocol.DshJson
import dev.dsh.mobile.core.protocol.LineChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远端链路的注入次数：session/prompt 没有 system 字段，身份只能搭 user 文本过去，
 * 所以「有没有注入」= 抓发出去的那一行里带不带身份包装头。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IdentityInjectionTransportTest {

    private class FakeChannel : LineChannel {
        private val inbox = Channel<String>(Channel.UNLIMITED)
        override val incoming: Flow<String> = inbox.receiveAsFlow()
        override val description: String = "fake"

        val sent = mutableListOf<String>()

        override suspend fun open() = Unit
        override suspend fun close() = Unit

        override suspend fun send(line: String) {
            sent += line
            val req = DshJson.parseToJsonElement(line).jsonObject
            val id = req["id"]?.jsonPrimitive?.content
            val method = req["method"]?.jsonPrimitive?.content
            val result: JsonObject = when (method) {
                "initialize" -> buildJsonObject {
                    put("protocolVersion", 1)
                    put("agentInfo", buildJsonObject {
                        put("name", "deepseek-harness-acp")
                        put("version", "0.0.1")
                    })
                }
                "session/list" -> buildJsonObject { put("sessions", JsonArray(listOf())) }
                "session/prompt" -> buildJsonObject { put("stopReason", "end_turn") }
                else -> buildJsonObject { }
            }
            if (id != null) inbox.trySend("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }

        /** 发出去的 session/prompt 里有几次带了身份包装。 */
        fun injectedPrompts(): Int = sent.count { line ->
            line.contains("\"session/prompt\"") && line.contains(IdentityInjector.HEADER.trim())
        }
    }

    private val card = "## 身份卡：审稿人\n你是：技术审稿人\n禁区：\n- 不改测试"

    private suspend fun transport(
        channel: FakeChannel,
        scope: kotlinx.coroutines.CoroutineScope,
        every: Int = IdentityInjector.DEFAULT_EVERY_TURNS,
        identity: String = card,
    ): AcpTransport {
        val t = AcpTransport(
            channelProvider = { channel },
            kind = TransportKind.InProcess,
            scope = scope,
            defaultCwd = { "/tmp" },
            personaProvider = { identity },
            identityEveryTurns = every,
        )
        t.connect()
        return t
    }

    @Test
    fun fortyRemoteTurnsInjectSevenTimes() = runTest {
        val channel = FakeChannel()
        val t = transport(channel, backgroundScope)
        repeat(40) { i -> t.prompt("s1", "第 " + (i + 1) + " 轮", emptyList(), null) }
        assertEquals(7, channel.injectedPrompts())
        t.disconnect()
    }

    @Test
    fun compactedForcesAnExtraInjection() = runTest {
        val channel = FakeChannel()
        val t = transport(channel, backgroundScope)
        t.prompt("s1", "第一轮", emptyList(), null)
        t.prompt("s1", "第二轮", emptyList(), null)
        assertEquals(1, channel.injectedPrompts())
        // 远端压缩后历史里已经没有身份：下一轮必须重发。
        t.onCompacted("s1")
        t.prompt("s1", "第三轮", emptyList(), null)
        assertEquals(2, channel.injectedPrompts())
        t.disconnect()
    }

    @Test
    fun resumeSessionForcesAnExtraInjection() = runTest {
        val channel = FakeChannel()
        val t = transport(channel, backgroundScope)
        t.prompt("s1", "第一轮", emptyList(), null)
        t.prompt("s1", "第二轮", emptyList(), null)
        assertEquals(1, channel.injectedPrompts())
        runCatching { t.resumeSession("s1") }
        t.prompt("s1", "第三轮", emptyList(), null)
        assertEquals(2, channel.injectedPrompts())
        t.disconnect()
    }

    @Test
    fun withoutAnyIdentityPromptsAreSentVerbatim() = runTest {
        val channel = FakeChannel()
        val t = transport(channel, backgroundScope, identity = "")
        t.prompt("s1", "原话", emptyList(), null)
        assertEquals(0, channel.injectedPrompts())
        assertTrue(channel.sent.any { it.contains("原话") && !it.contains("请始终遵守") })
        t.disconnect()
    }

    @Test
    fun everyTurnModeInjectsOnEveryPrompt() = runTest {
        val channel = FakeChannel()
        val t = transport(channel, backgroundScope, every = 1)
        repeat(3) { i -> t.prompt("s1", "第 " + i + " 轮", emptyList(), null) }
        assertEquals(3, channel.injectedPrompts())
        t.disconnect()
    }

    @Test
    fun cardContentReachesTheRemoteSide() = runTest {
        val channel = FakeChannel()
        val t = transport(channel, backgroundScope)
        t.prompt("s1", "原话", emptyList(), null)
        val line = channel.sent.last { it.contains("session/prompt") }
        assertFalse(line.contains("原话") && !line.contains("## 身份卡"))
        assertTrue(line.contains("技术审稿人"))
        assertTrue(line.contains("不改测试"))
        t.disconnect()
    }
}
