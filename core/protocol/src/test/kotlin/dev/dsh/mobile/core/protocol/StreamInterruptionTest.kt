package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.core.model.ToolCall
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T96 回归：上游断流 / 未闭合 DSML 块 / 背压丢失。
 *
 * 诊断文档 D:\DSH\HFA-WORK\research\T96-diag.md 的四类病灶，每类在这里至少一条：
 *  - P0-1 未闭合块回吐成正文、静默 TurnEnd("stop")：T1 T2 T5 T9
 *  - P0-2 开标签超 48 字符不押：T7
 *  - P0-3 背压丢事件：T8
 *  - P1-1 参数半截 JSON 退化成空 map 硬跑：T11
 */
class StreamInterruptionTest {

    // ---------- T1 ----------
    @Test
    fun unclosedBlockWithoutDoneNeverReachesTheBody() = runBlocking {
        Fixture(
            listOf(
                rawSse(
                    content("开始"),
                    content("\n<|DSML|tool_calls>\n<|DSML|invoke name=\"shell\">"),
                ),
            ),
        ).use { f ->
            val events = f.run()
            val body = text(events)
            assertFalse("正文不能回吐 DSML 标记: " + body, body.contains("DSML"))
            assertTrue("正文保留已上屏部分: " + body, body.contains("开始"))
            assertEquals(0, f.toolEvents(events).size)
            assertEquals(0, f.shellRuns)
            // 断流 + 无 [DONE] 走的是「连接意外断开」分支，必须是 error 而不是静默 stop。
            assertEquals("error", end(events).reason)
        }
    }

    // ---------- T2 ----------
    @Test
    fun truncatedBlockWithDoneIsReportedAsTruncated() = runBlocking {
        Fixture(
            listOf(
                sse(
                    content("正在搜索"),
                    content("\n<tool_call>{\"name\":\"shell\",\"arguments\":{\"command\":\"ls\"}}"),
                ),
            ),
        ).use { f ->
            val events = f.run()
            val body = text(events)
            assertFalse("正文不能带未闭合标记: " + body, body.contains("tool_call"))
            assertTrue("正文保留可见部分: " + body, body.contains("正在搜索"))
            assertTrue("要能看见截断提示: " + body, body.contains("截断"))
            val turnEnd = end(events)
            assertEquals("truncated", turnEnd.reason)
            assertTrue(turnEnd.truncatedToolCall)
            assertEquals(0, f.shellRuns)
        }
    }

    // ---------- T3 ----------
    @Test
    fun completeBlockExecutesExactlyOneCall() = runBlocking {
        Fixture(
            listOf(
                sse(content(dsmlBlock("echo ok"))),
                sse(content("命令输出 ok")),
            ),
        ).use { f ->
            val events = f.run()
            assertEquals("stop", end(events).reason)
            assertEquals(1, f.toolEvents(events).map { it.call.id }.toSet().size)
            assertEquals(1, f.shellRuns)
            assertFalse(text(events).contains("DSML"))
        }
    }

    // ---------- T4 ----------
    @Test
    fun twoCallsInOneFrameKeepDistinctIds() = runBlocking {
        Fixture(
            listOf(
                sse(
                    buildJsonObject {
                        putJsonArray("choices") {
                            add(
                                buildJsonObject {
                                    putJsonObject("delta") {
                                        putJsonArray("tool_calls") {
                                            add(toolCallDelta(0, "call-a", "{\"command\":\"echo a\"}"))
                                            add(toolCallDelta(1, "call-b", "{\"command\":\"echo b\"}"))
                                        }
                                    }
                                },
                            )
                        }
                    }.toString(),
                ),
                sse(content("两个都跑完了")),
            ),
        ).use { f ->
            val events = f.run()
            // 每个调用会走 Pending/InProgress/Completed 多张卡，按 id 去重才是「几个调用」。
            val ids = f.toolEvents(events).map { it.call.id }.toSet()
            assertEquals(2, ids.size)
            assertEquals(2, f.shellRuns)
            assertEquals("stop", end(events).reason)
        }
    }

    // ---------- T5 ----------
    @Test
    fun unclosedToolCallJsonIsNotExecuted() = runBlocking {
        Fixture(listOf(sse(content("<tool_call>{\"name\":\"shell\"}")))).use { f ->
            val events = f.run()
            val body = text(events)
            assertFalse("正文不能带未闭合标记: " + body, body.contains("tool_call"))
            assertEquals(0, f.shellRuns)
            assertEquals(0, f.toolEvents(events).size)
            assertEquals("truncated", end(events).reason)
        }
    }

    // ---------- T6 ----------
    @Test
    fun hugeSingleDeltaKeepsTextAroundTheCall() = runBlocking {
        val head = "头" + "A".repeat(8_000)
        Fixture(
            listOf(
                sse(content(head), content(dsmlBlock("echo big"))),
                sse(content("收尾")),
            ),
        ).use { f ->
            val events = f.run()
            val body = text(events)
            assertTrue("8KB 正文要完整: " + body.length, body.contains(head))
            assertTrue("调用后的收尾要在: " + body, body.contains("收尾"))
            assertEquals(1, f.toolEvents(events).map { it.call.id }.toSet().size)
            assertEquals(1, f.shellRuns)
            assertFalse(body.contains("DSML"))
        }
    }

    // ---------- T7 ----------
    @Test
    fun longAttributeTagSplitAcrossTinyChunksNeverLeaks() = runBlocking {
        val longBlock = "<|DSML|tool_calls>\n" +
            "<|DSML|invoke name=\"shell\" note=\"" + "x".repeat(120) + "\">\n" +
            "<|DSML|parameter name=\"command\">echo long</|DSML|parameter>\n" +
            "</|DSML|invoke>\n</|DSML|tool_calls>"
        Fixture(
            listOf(
                chunkedBody("准备：\n" + longBlock, 8),
                sse(content("好了")),
            ),
        ).use { f ->
            val events = f.run()
            val body = text(events)
            assertFalse("任何位置都不能漏出 DSML: " + body, body.contains("DSML"))
            assertEquals(1, f.toolEvents(events).map { it.call.id }.toSet().size)
            assertEquals(1, f.shellRuns)
        }
    }

    // ---------- T8 ----------
    @Test
    fun slowCollectorLosesNoChunk() = runBlocking {
        val frames = 1_500
        Fixture(listOf(plainBody("x", frames))).use { f ->
            val events = f.run(slowCollector = true)
            val chunks = events.filterIsInstance<DshEvent.MessageChunk>()
            assertTrue("收到的 chunk 数 " + chunks.size, chunks.size >= frames)
            assertTrue(
                "顺序与内容不能错位",
                chunks.joinToString("") { it.text }.startsWith("x".repeat(frames)),
            )
        }
    }

    // ---------- T9 ----------
    @Test
    fun retryAfterTruncationDoesNotDuplicateTheHalfSentence() = runBlocking {
        Fixture(
            listOf(
                rawSse(content("半句")),
                sse(content("半句后半句")),
            ),
        ).use { f ->
            val events = f.run()
            assertEquals("半句后半句", text(events))
            assertEquals("stop", end(events).reason)
            assertEquals(2, f.requests.size)
        }
    }

    // ---------- T11 ----------
    @Test
    fun halfJsonArgumentsAreNotExecuted() = runBlocking {
        Fixture(
            listOf(
                sse(
                    buildJsonObject {
                        putJsonArray("choices") {
                            add(
                                buildJsonObject {
                                    putJsonObject("delta") {
                                        putJsonArray("tool_calls") {
                                            add(toolCallDelta(0, "call-broken", "{\"command\":\"ls -l"))
                                        }
                                    }
                                },
                            )
                        }
                    }.toString(),
                ),
            ),
        ).use { f ->
            val events = f.run()
            assertEquals("参数不完整不能硬跑", 0, f.shellRuns)
            val call = f.toolEvents(events).lastOrNull()
            assertTrue("要留下失败的工具卡", call != null && call.call.status == ToolCall.ToolStatus.Failed)
            assertEquals("error", end(events).reason)
        }
    }

    // ---------- 夹具 ----------

    private class Fixture(private val replies: List<String>) : AutoCloseable {
        val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
        var shellRuns = 0
        private val server = ServerSocket(0, 100, InetAddress.getByName("127.0.0.1"))
        private val worker = Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    it.soTimeout = 10_000
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
                        if (header.startsWith("Content-Length:", ignoreCase = true)) {
                            length = header.substringAfter(':').trim().toInt()
                        }
                    }
                    val bytes = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = input.read(bytes, offset, length - offset)
                        check(count > 0)
                        offset += count
                    }
                    requests += Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                    val reply = replies.getOrElse(requests.size - 1) { replies.last() }.toByteArray()
                    val header = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                        "Content-Length: ${reply.size}\r\nConnection: close\r\n\r\n"
                    it.getOutputStream().apply { write(header.toByteArray()); write(reply); flush() }
                }
            }
        }.apply { isDaemon = true; start() }

        fun toolEvents(events: List<DshEvent>) = events.filterIsInstance<DshEvent.ToolCallEvent>()

        suspend fun run(slowCollector: Boolean = false): List<DshEvent> {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val transport = LocalEngineTransport(
                scope = scope,
                configProvider = {
                    LlmConfig(
                        "http://127.0.0.1:${server.localPort}",
                        "test-key",
                        "test-model",
                        systemPrompt = "test system",
                        maxTokens = 512,
                    )
                },
                historyProvider = { emptyList<ChatMessage>() },
                shellExec = { shellRuns++; "ok" },
                autoApprove = { true },
            )
            val events = mutableListOf<DshEvent>()
            val done = CompletableDeferred<Unit>()
            val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var first = true
                transport.events.collect {
                    events += it
                    // 慢消费者：卡在第一条上 300ms，让生产端把整轮事件全部推进缓冲区。
                    // 旧实现（容量 512 + SUSPEND）会在这里丢掉中间的事件。
                    if (slowCollector && first) {
                        first = false
                        delay(300)
                    }
                    if (it is DshEvent.TurnEnd) done.complete(Unit)
                }
            }
            try {
                transport.prompt("session", "question", emptyList(), "turn")
                withTimeout(30_000) { done.await() }
                return events.toList()
            } finally {
                collector.cancelAndJoin()
                scope.cancel()
            }
        }

        override fun close() {
            server.close()
            worker.join(5_000)
        }
    }

    companion object {
        private fun content(text: String) = buildJsonObject {
            putJsonArray("choices") { add(buildJsonObject { putJsonObject("delta") { put("content", text) } }) }
        }.toString()

        private fun toolCallDelta(index: Int, id: String, arguments: String) = buildJsonObject {
            put("index", index)
            put("id", id)
            put("type", "function")
            putJsonObject("function") {
                put("name", "shell")
                put("arguments", arguments)
            }
        }

        private fun sse(vararg frames: String) =
            frames.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

        /** 不带 [DONE]：模拟上游在收尾前断流。 */
        private fun rawSse(vararg frames: String) = frames.joinToString("") { "data: $it\n\n" }

        /** 正文按固定大小切片，用来把开标签切碎在 chunk 边界上。 */
        private fun chunkedBody(text: String, size: Int) = text.chunked(size).joinToString("") { part ->
            "data: " + content(part) + "\n\n"
        } + "data: [DONE]\n\n"

        private fun plainBody(marker: String, frames: Int) =
            (1..frames).joinToString("") { "data: " + content(marker) + "\n\n" } + "data: [DONE]\n\n"

        private fun dsmlBlock(command: String) = "<|DSML|tool_calls>\n" +
            "<|DSML|invoke name=\"shell\">\n" +
            "<|DSML|parameter name=\"command\" string=\"true\">$command</|DSML|parameter>\n" +
            "</|DSML|invoke>\n</|DSML|tool_calls>"

        private fun text(events: List<DshEvent>) =
            events.filterIsInstance<DshEvent.MessageChunk>().joinToString("") { it.text }

        private fun end(events: List<DshEvent>) = events.filterIsInstance<DshEvent.TurnEnd>().last()
    }
}
