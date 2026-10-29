package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.core.model.ToolCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 父轮走过 subagent 之后，界面必须拿到「子代理 · xxx」进度事件 + 本轮最终正文 + TurnEnd。
 *
 * 用真 HTTP 假上游跑完整循环：父轮第一轮要 subagent，子代理直接回 PONG，
 * 父轮第二轮把 PONG 汇总成正文收尾 —— 就是线上那条链路。
 */
class SubagentTurnEventTest {

    @Test
    fun subagentRoundStillEmitsProgressAndFinalBody() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
        val stub = FakeUpstream { json ->
            requests += json
            val toolNames = (json["tools"] as? JsonArray)?.mapNotNull { nameOf(it) }.orEmpty()
            val lastRole = (json["messages"] as? JsonArray)?.lastOrNull()
                ?.jsonObject?.get("role")?.jsonPrimitive?.contentOrNull
            when {
                "subagent" in toolNames && lastRole != "tool" ->
                    toolCallResponse("subagent", """{"prompt":"只回 PONG","name":"查PONG"}""")
                "subagent" in toolNames -> contentResponse("子代理已经返回了：PONG。汇总完毕。")
                else -> contentResponse("PONG")
            }
        }
        try {
            val events = runTurn(stub.baseUrl(), "把它交给子代理")

            // 1）进度：ToolCallEvent 的标题带「子代理 · 名字」。
            val subagentCalls = events.filterIsInstance<DshEvent.ToolCallEvent>()
                .map { it.call }.filter { it.kind == "subagent" }
            assertTrue("没有任何 subagent 工具事件：" + events, subagentCalls.isNotEmpty())
            assertTrue(
                "标题不带子代理名字：" + subagentCalls.map { it.title },
                subagentCalls.any { it.title == "子代理 · 查PONG" },
            )
            assertTrue(
                "子代理没有结算成 Completed：" + subagentCalls.map { it.status },
                subagentCalls.any { it.status == ToolCall.ToolStatus.Completed },
            )

            // 2）本轮最终正文必须发出来（仓库靠它落 ASSISTANT 行）。
            val text = events.filterIsInstance<DshEvent.MessageChunk>().joinToString("") { it.text }
            assertTrue("最终正文没发出来：" + events, text.contains("子代理已经返回了：PONG"))

            // 3）带 turnId 的 TurnEnd，仓库据此结算整轮。
            val ends = events.filterIsInstance<DshEvent.TurnEnd>()
            assertEquals("TurnEnd 数量不对：" + ends, 1, ends.size)
            assertEquals("stop", ends[0].reason)
            assertEquals("turn-1", ends[0].turnId)

            assertTrue("父轮没带 subagent：" + requests.size, requests.size >= 3)
        } finally {
            stub.close()
        }
    }

    // ---- 夹具 ----

    private fun nameOf(element: JsonElement): String? =
        ((element as? JsonObject)?.get("function") as? JsonObject)
            ?.get("name")?.jsonPrimitive?.contentOrNull

    private suspend fun runTurn(baseUrl: String, userText: String): List<DshEvent> = coroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = LocalEngineTransport(
            scope = scope,
            configProvider = {
                LlmConfig(baseUrl, "test-key", "test-model", systemPrompt = "test system", maxTokens = 256)
            },
            historyProvider = { emptyList<ChatMessage>() },
            shellExec = { "ok" },
            autoApprove = { true },
        )
        val events = Collections.synchronizedList(mutableListOf<DshEvent>())
        val done = CompletableDeferred<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            transport.events.collect {
                events += it
                if (it is DshEvent.TurnEnd) done.complete(Unit)
            }
        }
        transport.prompt("s1", userText, emptyList(), "turn-1")
        withTimeout(20_000) { done.await() }
        collector.cancel()
        events.toList()
    }

    /**
     * 最小假上游：ServerSocket 上跑 HTTP/1.1，一请求一连接（Connection: close）。
     * 不用 com.sun.net.httpserver —— Android 单测的编译类路径里没有它。
     */
    private class FakeUpstream(private val respond: (JsonObject) -> JsonObject) {
        private val socket = ServerSocket(0)
        private val running = AtomicBoolean(true)
        private val worker = thread(isDaemon = true, name = "fake-upstream") { serve() }

        fun baseUrl(): String = "http://127.0.0.1:" + socket.localPort

        fun close() {
            running.set(false)
            runCatching { socket.close() }
            worker.interrupt()
        }

        private fun serve() {
            while (running.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: return
                runCatching { handle(client) }
            }
        }

        private fun handle(client: Socket) {
            client.use { s ->
                val input = s.getInputStream()
                val head = readHead(input)
                val length = head.lineSequence()
                    .firstOrNull { it.lowercase().startsWith("content-length:") }
                    ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0
                val body = readExactly(input, length)
                val json = runCatching { DshJson.parseToJsonElement(body).jsonObject }.getOrNull()
                val payload = respond(json ?: JsonObject(emptyMap())).toString().toByteArray()
                val response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: " + payload.size + "\r\n" +
                    "Connection: close\r\n\r\n"
                s.getOutputStream().apply {
                    write(response.toByteArray())
                    write(payload)
                    flush()
                }
            }
        }

        /** 读到头结束（空行）。 */
        private fun readHead(input: InputStream): String {
            val out = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) break
                out.append(b.toChar())
                if (out.endsWith("\r\n\r\n")) break
            }
            return out.toString()
        }

        private fun readExactly(input: InputStream, length: Int): String {
            val buf = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buf, read, length - read)
                if (n < 0) break
                read += n
            }
            return String(buf, 0, read)
        }
    }

    private fun contentResponse(text: String): JsonObject = buildJsonObject {
        putJsonArray("choices") {
            addJsonObject {
                putJsonObject("message") {
                    put("role", "assistant")
                    put("content", text)
                }
            }
        }
    }

    private fun toolCallResponse(name: String, args: String): JsonObject = buildJsonObject {
        putJsonArray("choices") {
            addJsonObject {
                putJsonObject("message") {
                    put("role", "assistant")
                    put("content", "")
                    putJsonArray("tool_calls") {
                        addJsonObject {
                            put("id", "call_" + name)
                            putJsonObject("function") {
                                put("name", name)
                                put("arguments", args)
                            }
                        }
                    }
                }
            }
        }
    }
}
