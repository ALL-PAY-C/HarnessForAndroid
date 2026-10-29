package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.model.ChatMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

/**
 * engine:// 路由的结构化工具调用：插件 provider 回 {content:'', toolCalls:[…]} 时
 * 主循环要真的执行工具、把结果喂回去，而不是当成「空回合」报错。
 */
class EngineToolCallsTest {

    @Test fun structuredToolCallExecutesDespiteEmptyContent() = runBlocking {
        val run = harness(
            Reply.toolCall("shell", "{\"command\":\"echo hi\"}"),
            Reply.text("跑完了。"),
        )
        assertEquals(run.commands.toString(), listOf("echo hi"), run.commands)
        assertEquals(run.tools().toString(), 1, run.tools().count { it.call.kind == "shell" })
        val text = run.text()
        assertFalse("空正文配工具调用不该被当成空回合：" + text, text.contains("本轮没有任何输出"))
        assertTrue(text, text.contains("跑完了。"))
        assertEquals(run.ends().toString(), 1, run.ends().size)
        assertEquals("stop", run.ends()[0].reason)
    }

    @Test fun toolResultGoesBackToTheEngine() = runBlocking {
        val run = harness(
            Reply.toolCall("shell", "{\"command\":\"id\"}"),
            Reply.text("看过了。"),
        )
        assertEquals(run.bodies.toString(), 2, run.bodies.size)
        val second = run.bodies[1].getJSONArray("messages")
        val roles = (0 until second.length()).map { second.getJSONObject(it).optString("role") }
        assertTrue("第二轮没有把工具结果喂回去：" + roles, "tool" in roles)
        // assistant 那一轮也要带 tool_calls，否则上游会拒。
        assertTrue("assistant 消息缺 tool_calls：" + roles, roles.contains("assistant"))
    }

    @Test fun engineRouteCanRunSubagent() = runBlocking {
        val run = harness(
            Reply.toolCall("subagent", "{\"prompt\":\"只回 PONG\",\"name\":\"探针\"}"),
            Reply.text("PONG"),
            Reply.text("子代理说 PONG。"),
        )
        val sub = run.tools().filter { it.call.kind == "subagent" }
        assertEquals(run.tools().toString(), 1, sub.size)
        assertTrue(sub[0].call.title, sub[0].call.title.contains("探针"))
        val text = run.text()
        assertTrue(text, text.contains("子代理说 PONG。"))
        assertFalse("subagent 不该再报不支持：" + text, text.contains("不支持工具调用"))
        // 子代理自己那次请求单独发了一份工具表（白名单里没有 subagent）。
        assertEquals(run.bodies.toString(), 3, run.bodies.size)
    }

    // ---- 夹具 ----

    /** 一次引擎回复：正文 + 可选的结构化工具调用（OpenAI 形状）。 */
    private data class Reply(val text: String, val tools: List<Pair<String, String>>) {
        companion object {
            fun text(text: String) = Reply(text, emptyList())
            fun toolCall(name: String, args: String) = Reply("", listOf(name to args))
        }
    }

    private class Run(
        val events: List<DshEvent>,
        val bodies: List<JSONObject>,
        val commands: List<String>,
    ) {
        fun text() = events.filterIsInstance<DshEvent.MessageChunk>().joinToString("") { it.text }
        /** 同一个调用会先发 InProgress 再发终态，按 id 折成一条。 */
        fun tools() = events.filterIsInstance<DshEvent.ToolCallEvent>().distinctBy { it.call.id }
        fun ends() = events.filterIsInstance<DshEvent.TurnEnd>()
    }

    private suspend fun harness(vararg replies: Reply): Run = coroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val bodies = Collections.synchronizedList(mutableListOf<JSONObject>())
        val commands = Collections.synchronizedList(mutableListOf<String>())
        val cursor = intArrayOf(0)
        val transport = LocalEngineTransport(
            scope = scope,
            configProvider = {
                LlmConfig(
                    "engine://deepseek-v4.1-flash",
                    "test-key",
                    "deepseek-v4.1-flash",
                    systemPrompt = "test system",
                    maxTokens = 256,
                    supportsTools = true,
                )
            },
            historyProvider = { emptyList<ChatMessage>() },
            shellExec = { cmd ->
                commands += cmd
                "ok"
            },
            autoApprove = { true },
            engineChat = { payload ->
                bodies += JSONObject(payload.toString())
                val reply = replies.getOrElse(cursor[0]++) { Reply.text("收尾。") }
                val value = JSONObject().put("content", reply.text)
                if (reply.tools.isNotEmpty()) {
                    val arr = JSONArray()
                    reply.tools.forEachIndexed { i, one ->
                        arr.put(
                            JSONObject()
                                .put("id", "call_" + i)
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject().put("name", one.first).put("arguments", one.second),
                                ),
                        )
                    }
                    value.put("toolCalls", arr)
                }
                JSONObject().put("ok", true).put("value", value)
            },
            engineRpc = null,
        )
        val events = Collections.synchronizedList(mutableListOf<DshEvent>())
        val done = CompletableDeferred<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            transport.events.collect {
                events += it
                if (it is DshEvent.TurnEnd) done.complete(Unit)
            }
        }
        try {
            transport.prompt("session", "question", emptyList(), "turn")
            withTimeout(30_000) { done.await() }
            Run(events.toList(), bodies.toList(), commands.toList())
        } finally {
            collector.cancelAndJoin()
            scope.cancel()
        }
    }
}