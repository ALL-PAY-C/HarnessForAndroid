package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.model.ChatMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * engine:// 路由的工具调用：插件 provider 把 tools 原样转给上游（OpenAI 形状），回包可能是
 * 结构化 toolCalls，也可能是正文里的 DSML 标记 —— 两种都要变成真的工具执行，正文只留人话。
 */
class DsmlEngineRouteTest {

    private val markup = """
        <｜｜DSML｜｜tool_calls>
        <｜｜DSML｜｜invoke name="shell">
        <｜｜DSML｜｜parameter name="command" string="true">which chromium wkhtmltoimage 2>/dev/null</｜｜DSML｜｜parameter>
        </｜｜DSML｜｜invoke>
        </｜｜DSML｜｜tool_calls>
    """.trimIndent()

    @Test fun pureMarkupExecutesTheRequestedTool() = runBlocking {
        val run = harness(markup, "看完了。")
        val text = run.text()
        // 一个标记字符都不许漏进正文。
        assertFalse(text, text.contains("DSML"))
        assertFalse(text, text.contains("｜"))
        // 工具真的跑了：命令行原样交给 shell。
        assertEquals(run.commands.toString(), 1, run.commands.size)
        assertTrue(run.commands[0], run.commands[0].contains("which chromium"))
        assertEquals(run.tools().toString(), 1, run.tools().count { it.call.kind == "shell" })
        // 不再是「不支持工具调用」：工具通道已经打通。
        assertFalse(text, text.contains("不支持工具调用"))
        assertTrue(text, text.contains("看完了。"))
    }

    @Test fun markupInsideSentencesKeepsBothSentences() = runBlocking {
        val content = "先看看环境里有没有可用工具。\n" + markup + "\n那就换个思路，直接问用户。"
        val run = harness(content, "收尾。")
        val text = run.text()
        assertFalse(text, text.contains("DSML"))
        val first = text.indexOf("先看看环境里有没有可用工具。")
        val second = text.indexOf("那就换个思路，直接问用户。")
        assertTrue(text, first == 0)
        assertTrue(text, second > first)
        assertEquals(run.commands.toString(), 1, run.commands.size)
    }

    @Test fun plainTextComesThroughUnchanged() = runBlocking {
        val content = "引擎正常回复：第一行。\n第二行 <没有标记> 也照旧。"
        val run = harness(content)
        assertEquals(content, run.text())
        // 工具表必须发到引擎（以前 engine:// 是不发的）。
        assertTrue("引擎请求里没有 tools：" + run.bodies.first().keys().asSequence().toList(), run.bodies.first().has("tools"))
    }

    @Test fun multipleInvokesAllExecute() = runBlocking {
        val content = """
            <｜｜DSML｜｜tool_calls>
            <｜｜DSML｜｜invoke name="shell">
            <｜｜DSML｜｜parameter name="command" string="true">pwd</｜｜DSML｜｜parameter>
            </｜｜DSML｜｜invoke>
            <｜｜DSML｜｜invoke name="shell">
            <｜｜DSML｜｜parameter name="command" string="true">df -h</｜｜DSML｜｜parameter>
            </｜｜DSML｜｜invoke>
            </｜｜DSML｜｜tool_calls>
        """.trimIndent()
        val run = harness(content, "都跑完了。")
        assertEquals(run.commands.toString(), 2, run.commands.size)
        assertEquals("pwd", run.commands[0])
        assertEquals("df -h", run.commands[1])
        assertFalse(run.text(), run.text().contains("DSML"))
    }

    @Test fun brokenMarkupDoesNotBreakTheTurn() = runBlocking {
        // 缺闭合标签：解析不出来就原样留着，不能让异常顶掉整轮回复。
        val content = "命令失败了。\n<｜｜DSML｜｜tool_calls>\n<｜｜DSML｜｜invoke name=\"shell\">"
        val run = harness(content)
        assertTrue(run.text(), run.text().contains("命令失败了。"))
        assertEquals(run.ends().toString(), 1, run.ends().size)
    }

    // ---- 夹具 ----

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

    private suspend fun harness(vararg replies: String): Run = coroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val bodies = Collections.synchronizedList(mutableListOf<JSONObject>())
        val commands = Collections.synchronizedList(mutableListOf<String>())
        val calls = AtomicInteger(0)
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
                val content = replies.getOrElse(calls.getAndIncrement()) { "收尾。" }
                JSONObject().put("ok", true).put("value", JSONObject().put("content", content))
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