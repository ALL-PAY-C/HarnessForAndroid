package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.model.ChatMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

/**
 * engine:// 路由的正文净化：引擎插件没有 function calling 通道，模型会把 DSML 标记当正文吐回来，
 * 这里要保证 UI 只看到干净正文 + 一行「为什么不执行」的说明。
 */
class DsmlEngineRouteTest {

    private val markup = """
        <｜｜DSML｜｜tool_calls>
        <｜｜DSML｜｜invoke name="shell">
        <｜｜DSML｜｜parameter name="command" string="true">which chromium wkhtmltoimage 2>/dev/null</｜｜DSML｜｜parameter>
        </｜｜DSML｜｜invoke>
        </｜｜DSML｜｜tool_calls>
    """.trimIndent()

    @Test fun pureMarkupLeavesOnlyTheNoticeLine() = runBlocking {
        val result = harness(markup)
        val text = result.text().trim()
        // 一个标记字符都不许漏。
        assertFalse(text, text.contains("DSML"))
        assertFalse(text, text.contains("｜"))
        assertFalse(text, text.contains("invoke"))
        assertFalse(text, text.contains("parameter"))
        // 只剩那一行提示：列出被请求的工具名，并说明 engine:// 没有工具通道。
        assertEquals(1, text.lines().size)
        assertTrue(text, text.contains("shell"))
        assertTrue(text, text.contains("engine://"))
        assertTrue(text, text.contains("不支持工具调用"))
        assertEquals(1, occurrences(text, "模型请求调用"))
    }

    @Test fun markupInsideSentencesKeepsBothSentences() = runBlocking {
        val content = "先看看环境里有没有可用工具。\n" + markup + "\n那就换个思路，直接问用户。"
        val text = harness(content).text()
        assertFalse(text, text.contains("DSML"))
        val first = text.indexOf("先看看环境里有没有可用工具。")
        val second = text.indexOf("那就换个思路，直接问用户。")
        assertTrue(text, first == 0)
        assertTrue(text, second > first)
        // 句子之间只隔换行，不许并成一行怪文本。
        assertTrue(text, text.contains("先看看环境里有没有可用工具。\n"))
        // 提示行在句子之后，单独一行。
        assertTrue(text.lines().last(), text.trimEnd().lines().last().contains("模型请求调用"))
    }

    @Test fun plainTextComesThroughUnchanged() = runBlocking {
        val content = "引擎正常回复：第一行。\n第二行 <没有标记> 也照旧。"
        assertEquals(content, harness(content).text())
    }

    @Test fun multipleInvokesGetOneNoticeListingEveryTool() = runBlocking {
        val content = """
            <｜｜DSML｜｜tool_calls>
            <｜｜DSML｜｜invoke name="shell">
            <｜｜DSML｜｜parameter name="command" string="true">ls</｜｜DSML｜｜parameter>
            </｜｜DSML｜｜invoke>
            <｜｜DSML｜｜invoke name="fs_read">
            <｜｜DSML｜｜parameter name="path" string="true">/sdcard/x.txt</｜｜DSML｜｜parameter>
            </｜｜DSML｜｜invoke>
            <｜｜DSML｜｜invoke name="shell">
            <｜｜DSML｜｜parameter name="command" string="true">df -h</｜｜DSML｜｜parameter>
            </｜｜DSML｜｜invoke>
            </｜｜DSML｜｜tool_calls>
        """.trimIndent()
        val text = harness(content).text().trim()
        assertFalse(text, text.contains("DSML"))
        // 同一次回复只提示一次，名字去重。
        assertEquals(1, occurrences(text, "模型请求调用"))
        assertEquals(1, occurrences(text, "shell"))
        assertTrue(text, text.contains("fs_read"))
        assertEquals(1, text.lines().size)
    }

    @Test fun brokenMarkupIsPassedThroughWithoutThrowing() = runBlocking {
        // 缺闭合标签：解析不出来就原样留着，不能让异常顶掉整轮回复。
        val content = "命令失败了。\n<｜｜DSML｜｜tool_calls>\n<｜｜DSML｜｜invoke name=\"shell\">"
        val result = harness(content)
        assertEquals(content, result.text())
        assertNull(result.failure())
    }

    // ---- 夹具 ----

    private fun occurrences(text: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    private class Outcome(val events: List<DshEvent>) {
        fun text() = events.filterIsInstance<DshEvent.MessageChunk>().joinToString("") { it.text }
        fun failure(): String? = text().takeIf { it.contains("[请求失败]") }?.removePrefix("\n\n")
    }

    private suspend fun harness(engineContent: String): Outcome = coroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = LocalEngineTransport(
            scope = scope,
            configProvider = {
                LlmConfig("engine://deepseek-v4.1-flash", "test-key", "configured-model", systemPrompt = "test system", maxTokens = 256)
            },
            historyProvider = { emptyList<ChatMessage>() },
            shellExec = { "ok" },
            autoApprove = { true },
            engineChat = { _ ->
                JSONObject().put("ok", true).put("value", JSONObject().put("content", engineContent))
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
            Outcome(events.toList())
        } finally {
            collector.cancelAndJoin()
            scope.cancel()
        }
    }
}
