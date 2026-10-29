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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 子代理跑不起来时这一轮也不能静默：要有 Failed 工具卡（界面可见）、要有最终正文、要有 TurnEnd。
 *
 * 以前工具执行分支没有 try/catch，子代理侧一抛异常（引擎未就绪 / 子代理请求 500 / 超时）就逃出整个
 * runLoop：进度卡永远 InProgress、不发 TurnEnd，仓库 finalizeTurn 拿不到正文，DB 里只剩一行 USER。
 */
class SubagentFailureVisibilityTest {

    @Test
    fun subagentUpstreamErrorKeepsTurnAliveAndVisible() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
        val index = AtomicInteger(0)
        val stub = FakeUpstream { json, n ->
            requests += json
            when (n) {
                // 父轮第一轮：要子代理。
                0 -> 200 to toolCallResponse(
                    "subagent",
                    """{"prompt":"只回 PONG","name":"查PONG"}""",
                )
                // 子代理自己的那个请求：上游 500（这就是以前炸掉整轮的入口）。
                1 -> {
                    index.incrementAndGet()
                    500 to buildJsonObject { put("error", "boom") }
                }
                // 父轮第二轮：子代理失败回灌之后，模型照常收尾。
                else -> 200 to contentResponse("子代理失败了，我已经把原因写在工具卡里。")
            }
        }
        try {
            val events = runTurn(stub.baseUrl(), "把它交给子代理")

            // 1）异常没有逃出这一轮：拿到了正文（子代理失败后模型继续收尾）。
            val text = events.filterIsInstance<DshEvent.MessageChunk>().joinToString("") { it.text }
            assertTrue("子代理失败后没有最终正文：" + events, text.contains("子代理失败了"))
            assertTrue("子代理没被真的请求过：" + requests.size, requests.size >= 3)
            assertEquals("子代理请求没被拦住：" + index.get(), 1, index.get())

            // 2）Failed 工具卡：标题仍带「子代理 · 名字」，detail 里有可读原因（界面能看见）。
            val calls = events.filterIsInstance<DshEvent.ToolCallEvent>().map { it.call }
                .filter { it.kind == "subagent" }
            assertTrue("没有任何 subagent 工具事件：" + events, calls.isNotEmpty())
            assertTrue(
                "标题不带子代理名字：" + calls.map { it.title },
                calls.any { it.title == "子代理 · 查PONG" },
            )
            val failed = calls.firstOrNull { it.status == ToolCall.ToolStatus.Failed }
            assertTrue("子代理没有结算成 Failed，卡片会一直转圈：" + calls.map { it.status }, failed != null)
            assertTrue(
                "失败原因不可读：" + failed!!.detail,
                failed.detail.contains("工具执行失败"),
            )

            // 3）恰好一个 TurnEnd —— 仓库据此结算并落 ASSISTANT 行。
            val ends = events.filterIsInstance<DshEvent.TurnEnd>()
            assertEquals("TurnEnd 数量不对：" + ends, 1, ends.size)
            assertEquals("turn-1", ends[0].turnId)
            assertEquals("stop", ends[0].reason)
        } finally {
            stub.close()
        }
    }

    /**
     * engine:// 引擎插件没有 function calling：工具表不能发过去（subagent 不能凭空出现），
     * 模型硬从正文里把 subagent 写出来时要回可读错误，而不是卡住或静默。
     */
    @Test
    fun engineRouteOffersNoToolsAndRejectsSubagentReadably() = runBlocking {
        val bodies = Collections.synchronizedList(mutableListOf<JSONObject>())
        val calls = AtomicInteger(0)
        val events = coroutineScope {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val transport = LocalEngineTransport(
                scope = scope,
                configProvider = {
                    LlmConfig(
                        "engine://demo",
                        "test-key",
                        "test-model",
                        systemPrompt = "test system",
                        maxTokens = 256,
                        // 故意声明支持工具：即使这样，engine:// 也不能收到工具表。
                        supportsTools = true,
                    )
                },
                historyProvider = { emptyList<ChatMessage>() },
                shellExec = { "ok" },
                autoApprove = { true },
                engineChat = { payload ->
                    bodies += JSONObject(payload.toString())
                    val content = if (calls.getAndIncrement() == 0) {
                        "<|DSML|tool_calls>\n" +
                            "<|DSML|invoke name=\"subagent\">\n" +
                            "<|DSML|parameter name=\"prompt\" string=\"true\">只回 PONG</|DSML|parameter>\n" +
                            "</|DSML|invoke>\n" +
                            "</|DSML|tool_calls>"
                    } else {
                        "引擎侧收尾。"
                    }
                    JSONObject().put("ok", true).put("value", JSONObject().put("content", content))
                },
            )
            val sink = Collections.synchronizedList(mutableListOf<DshEvent>())
            val done = CompletableDeferred<Unit>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                transport.events.collect {
                    sink += it
                    if (it is DshEvent.TurnEnd) done.complete(Unit)
                }
            }
            transport.prompt("s1", "让子代理看看", emptyList(), "turn-1")
            withTimeout(20_000) { done.await() }
            collector.cancel()
            sink.toList()
        }

        // 1）engine:// 端点上不发工具表：subagent 不会凭空出现在模型眼里。
        assertTrue("引擎端点没被请求：" + bodies.size, bodies.isNotEmpty())
        assertFalse("engine:// 端点不该收到工具表：" + bodies.first().keys().asSequence().toList(), bodies.first().has("tools"))

        // 2）模型硬调 subagent：engine:// 没有执行通道，不执行工具，只在正文里说明白为什么没跑。
        assertEquals("工具调用不该被执行（引擎只该被问一次）：" + calls.get(), 1, calls.get())
        assertTrue(
            "不该出现 subagent 工具卡：" + events,
            events.filterIsInstance<DshEvent.ToolCallEvent>().none { it.call.kind == "subagent" },
        )
        val text = events.filterIsInstance<DshEvent.MessageChunk>().joinToString("") { it.text }
        assertTrue("没有说明为什么没执行：" + text, text.contains("不支持工具调用"))
        assertTrue("提示里没有工具名：" + text, text.contains("subagent"))
        assertFalse("markup 漏进正文了：" + text, text.contains("DSML"))

        // 3）这一轮照常结算：一个 TurnEnd。
        val ends = events.filterIsInstance<DshEvent.TurnEnd>()
        assertEquals("TurnEnd 数量不对：" + ends, 1, ends.size)
        assertEquals("turn-1", ends[0].turnId)
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
     * 回调返回 (状态码, JSON)，用状态码模拟子代理侧 500。
     */
    private class FakeUpstream(private val respond: (JsonObject, Int) -> Pair<Int, JsonObject>) {
        private val socket = ServerSocket(0)
        private val running = AtomicBoolean(true)
        private val seq = AtomicInteger(0)
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
                val (status, payload) = respond(json ?: JsonObject(emptyMap()), seq.getAndIncrement())
                val bytes = payload.toString().toByteArray()
                val reason = if (status == 200) "OK" else "ERR"
                val response = "HTTP/1.1 " + status + " " + reason + "\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: " + bytes.size + "\r\n" +
                    "Connection: close\r\n\r\n"
                s.getOutputStream().apply {
                    write(response.toByteArray())
                    write(bytes)
                    flush()
                }
            }
        }

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
