package dev.dsh.mobile.core.protocol

import java.net.ServerSocket
import java.net.InetAddress
import dev.dsh.mobile.core.model.ChatMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

class DeepSeekReasoningTransportTest {
    @Test fun ds4RequestExplicitlyEnablesThinking() = runBlocking {
        Fixture().use { f ->
            f.run("DS4.1-CN-Max")
            val request = f.requests.single()
            assertEquals("enabled", request["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
            assertEquals("high", request["reasoning_effort"]?.jsonPrimitive?.content)
        }
    }

    @Test fun deepseekModelAndEndpointAreRecognized() = runBlocking {
        for ((model, suffix) in listOf("DeepSeek-V4.1" to "", "custom-model" to "/deepseek")) {
            Fixture().use { f ->
                f.run(model, suffix = suffix)
                assertEquals("enabled", f.requests.single()["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
            }
        }
    }

    @Test fun otherModelsKeepTheirOriginalRequestBytes() = runBlocking {
        for (model in listOf("gpt-4o-mini", "claude-sonnet", "ollama/llama3")) {
            Fixture().use { f ->
                val history = listOf(assistant("prior answer", "private prior reasoning"))
                f.run(model, history)
                val request = f.requests.single()
                assertFalse(request.containsKey("thinking"))
                assertFalse(request.containsKey("reasoning_effort"))
                assertFalse(request.containsKey("enable_thinking"))
                assertTrue(request["messages"]!!.jsonArray.none { it.jsonObject.containsKey("reasoning_content") })
                val expected = buildJsonObject {
                    put("model", model)
                    put("messages", request["messages"]!!)
                    put("tools", request["tools"]!!)
                    put("max_tokens", 512)
                    put("stream", true)
                }
                assertEquals(expected.toString(), f.rawRequests.single())
            }
        }
    }

    @Test fun deltaReasoningIsThoughtAndNeverVisibleText() = runBlocking {
        Fixture(listOf(sse(
            """{"choices":[{"delta":{"reasoning_content":"private delta"}}]}""",
            """{"choices":[{"delta":{"content":"visible answer"}}]}""",
        ))).use { f ->
            val events = f.run("DS4.1")
            assertEquals("private delta", thoughts(events))
            assertEquals("visible answer", text(events))
        }
    }

    @Test fun messageReasoningWithoutDeltaIsDelivered() = runBlocking {
        Fixture(listOf(sse(
            """{"choices":[{"message":{"reasoning_content":"private message","content":"do not replay"}}]}""",
            """{"choices":[{"delta":{"content":"visible answer"},"message":{"content":"visible answer"}}]}""",
        ))).use { f ->
            val events = f.run("DS4.1")
            assertEquals("private message", thoughts(events))
            assertEquals("visible answer", text(events))
        }
    }

    @Test fun everyReasoningAliasAndShapeWorksAtEveryFrameLocation() = runBlocking {
        val keys = listOf("reasoning_content", "reasoning", "thinking", "reasoning_details", "analysis")
        for (location in listOf("delta", "message", "root")) {
            val frames = keys.mapIndexed { index, key ->
                val value: JsonElement = when (index % 3) {
                    0 -> JsonPrimitive("private-$index;")
                    1 -> buildJsonArray { add(buildJsonObject { put("text", "private-$index;") }) }
                    else -> buildJsonObject { put("content", "private-$index;") }
                }
                val fields = buildJsonObject { put(key, value) }
                if (location == "root") fields.toString() else buildJsonObject {
                    putJsonArray("choices") { add(buildJsonObject { put(location, fields) }) }
                }.toString()
            } + """{"choices":[{"delta":{"content":"visible answer"}}]}"""
            Fixture(listOf(sse(*frames.toTypedArray()))).use { f ->
                val events = f.run("DS4.1")
                assertEquals(location, keys.indices.joinToString("") { "private-$it;" }, thoughts(events))
                assertEquals(location, "visible answer", text(events))
            }
        }
    }

    @Test fun toolRoundReplaysHistoricalAndCurrentAssistantReasoning() = runBlocking {
        val first = sse(
            """{"choices":[{"delta":{"reasoning_content":"private tool reasoning"}}]}""",
            """{"choices":[{"delta":{"content":"checking"}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"shell","arguments":"{\"command\":\"echo ok\"}"}}]}}]}""",
        )
        Fixture(listOf(first, sse("""{"choices":[{"delta":{"content":"done"}}]}"""))).use { f ->
            val history = listOf(assistant("prior answer", "private history"), assistant("no thought", ""))
            val events = f.run("DS4.1", history)
            assertEquals(2, f.requests.size)
            val second = f.requests[1]
            assertNotNull(second["tools"])
            val assistants = second["messages"]!!.jsonArray.map { it.jsonObject }.filter { it["role"]?.jsonPrimitive?.content == "assistant" }
            assertEquals("private history", assistants[0]["reasoning_content"]?.jsonPrimitive?.content)
            assertFalse(assistants[1].containsKey("reasoning_content"))
            assertEquals("private tool reasoning", assistants[2]["reasoning_content"]?.jsonPrimitive?.content)
            assertEquals("checking", assistants[2]["content"]?.jsonPrimitive?.content)
            assertEquals("private tool reasoning", thoughts(events))
            assertFalse(text(events).contains("private"))
            assertTrue(text(events).contains("done"))
        }
    }

    @Test fun secondUserTurnReplaysAssistantThoughtSeparately() = runBlocking {
        Fixture().use { f ->
            f.run("DS4.1", listOf(assistant("previous visible", "previous private")))
            val assistant = f.requests.single()["messages"]!!.jsonArray.map { it.jsonObject }.single { it["role"]?.jsonPrimitive?.content == "assistant" }
            assertEquals("previous private", assistant["reasoning_content"]?.jsonPrimitive?.content)
            assertEquals("previous visible", assistant["content"]?.jsonPrimitive?.content)
        }
    }

    private fun assistant(text: String, thought: String) = ChatMessage("old", "session", ChatMessage.Role.ASSISTANT, text, thought)
    private fun thoughts(events: List<DshEvent>) = events.filterIsInstance<DshEvent.ThoughtChunk>().joinToString("") { it.text }
    private fun text(events: List<DshEvent>) = events.filterIsInstance<DshEvent.MessageChunk>().joinToString("") { it.text }

    private class Fixture(private val replies: List<String> = listOf(sse("""{"choices":[{"delta":{"content":"visible answer"}}]}"""))) : AutoCloseable {
        val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
        val rawRequests = Collections.synchronizedList(mutableListOf<String>())
        private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        private val worker = Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    it.soTimeout = 5_000
                    val input = it.getInputStream()
                    fun line(): String = buildString {
                        while (true) {
                            val ch = input.read()
                            if (ch == -1 || ch == 10) break
                            if (ch != 13) append(ch.toChar())
                        }
                    }
                    line()
                    var length = 0
                    while (true) {
                        val header = line()
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
                    }
                    val bytes = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = input.read(bytes, offset, length - offset)
                        check(count > 0)
                        offset += count
                    }
                    val body = bytes.toString(Charsets.UTF_8)
                    rawRequests += body
                    requests += Json.parseToJsonElement(body).jsonObject
                    val reply = replies.getOrElse(requests.size - 1) { replies.last() }.toByteArray()
                    val header = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${reply.size}\r\nConnection: close\r\n\r\n"
                    it.getOutputStream().apply { write(header.toByteArray()); write(reply); flush() }
                }
            }
        }.apply { isDaemon = true; start() }

        suspend fun run(model: String, history: List<ChatMessage> = emptyList(), suffix: String = ""): List<DshEvent> = coroutineScope {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val transport = LocalEngineTransport(
                scope = scope,
                configProvider = { LlmConfig("http://127.0.0.1:${server.localPort}$suffix", "test-key", model, systemPrompt = "test system", maxTokens = 512) },
                historyProvider = { history },
                shellExec = { "ok" },
                autoApprove = { true },
            )
            val events = mutableListOf<DshEvent>()
            val done = CompletableDeferred<Unit>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                transport.events.collect {
                    events += it
                    if (it is DshEvent.TurnEnd) done.complete(Unit)
                }
            }
            try {
                transport.prompt("session", "question", emptyList(), "turn")
                withTimeout(15_000) { done.await() }
                assertFalse(events.toString(), events.filterIsInstance<DshEvent.TurnEnd>().any { it.reason == "error" })
                events.toList()
            } finally {
                collector.cancelAndJoin()
                scope.cancel()
            }
        }

        override fun close() { server.close(); worker.join(5_000) }
    }

    companion object {
        private fun sse(vararg frames: String) = frames.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
    }
}
