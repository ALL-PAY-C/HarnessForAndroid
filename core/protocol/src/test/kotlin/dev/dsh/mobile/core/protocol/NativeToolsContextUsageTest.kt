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

class NativeToolsContextUsageTest {
    @Test fun nativeSchemasAndDispatch() = runBlocking {
        val calls = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val transport = LocalEngineTransport(scope, { null }, historyProvider = { emptyList() },
                shellExec = { calls += "old:$it"; "old" },
                fsExec = { name, args -> calls += name; args.toString() },
                bgExec = { name, args -> calls += name; args.toString() },
                channelListExec = { calls += "channel_list"; "[]" })
            val functions = transport.toolsJson().map { it.jsonObject["function"]!!.jsonObject }
            val added = listOf("fs_read", "fs_write", "fs_list", "fs_op", "channel_list", "run_bg", "bg_status", "bg_kill")
            assertTrue(functions.map { it["name"]!!.jsonPrimitive.content }.containsAll(added))
            val field = LocalEngineTransport::class.java.getDeclaredField("toolRunner").apply { isAccessible = true }
            val executor = field.get(transport) as ToolExecutor
            for (name in added) assertTrue(executor.run(name, buildJsonObject { put("path", "/tmp/a") }).ok)
            assertEquals(added, calls)
            executor.run("shell", buildJsonObject { put("command", "pwd") })
            assertEquals("old:pwd", calls.last())
            val routed = LocalEngineTransport(scope, { null }, historyProvider = { emptyList() }, shellExec = { error("old route") },
                shellExecChannel = { cmd, channel -> calls += "$cmd:$channel"; "ok" })
            val routedExecutor = field.get(routed) as ToolExecutor
            assertTrue(routedExecutor.run("shell", buildJsonObject { put("command", "ls"); put("channel", "app") }).ok)
            assertEquals("ls:app", calls.last())
            val shell = functions.first { it["name"]!!.jsonPrimitive.content == "shell" }
            assertEquals(listOf("proot", "app", "shizuku", "root"), shell["parameters"]!!.jsonObject["properties"]!!.jsonObject["channel"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        } finally { scope.cancel() }
    }

    @Test fun singleChatSendsCompressedHistory() = runBlocking {
        Fixture().use { f ->
            val history = (1..40).map { ChatMessage("$it", "session", ChatMessage.Role.ASSISTANT, "history-$it " + "x".repeat(2000)) }
            f.run("test-model", history)
            val messages = f.requests.single()["messages"]!!.jsonArray
            val used = messages.sumOf { it.jsonObject["content"]!!.jsonPrimitive.content.length }
            assertTrue(used < history.sumOf { it.text.length })
            assertTrue(used <= 2400)
            assertTrue(messages.any { it.jsonObject["content"]!!.jsonPrimitive.content.contains("历史摘要") })
            assertEquals("question", messages.last().jsonObject["content"]!!.jsonPrimitive.content)
        }
    }

    @Test fun usageKeepsRequestPromptAndCache() = runBlocking {
        val first = """{"usage":{"prompt_tokens":100,"completion_tokens":12,"prompt_tokens_details":{"cached_tokens":40}},"choices":[{"delta":{"content":"answer"}}]}"""
        val second = """{"usage":{"prompt_tokens":50,"completion_tokens":7,"prompt_cache_hit_tokens":10},"choices":[{"delta":{"content":"next"}}]}"""
        Fixture(listOf(sse(first), sse(second))).use { f ->
            val a = f.run("test-model").filterIsInstance<DshEvent.Usage>().single().usage
            val b = f.run("test-model").filterIsInstance<DshEvent.Usage>().single().usage
            assertEquals(60L, a.input)
            assertEquals(40L, a.cached)
            assertEquals(100L, a.lastPrompt)
            assertEquals(50L, (a + b).lastPrompt)
            assertEquals(169L, (a + b).sessionTotal)
        }
    }

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
                contextBudgetProvider = { 2400 },
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
