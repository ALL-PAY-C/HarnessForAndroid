package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.model.ChatMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * 空回合：上游这一轮什么都没给时，界面必须拿到一句可读原因 + TurnEnd("error")，
 * 而不是一片空白 + TurnEnd("stop")。
 */
class EmptyTurnTest {

    @Test
    fun upstreamErrorFieldIsTheReason() {
        val reason = upstreamEmptyReason(json("""{"error":{"message":"rate limit exceeded"}}"""), null)
        assertTrue(reason, reason.contains("上游报错"))
        assertTrue(reason, reason.contains("rate limit exceeded"))
    }

    @Test
    fun missingChoicesIsTheReason() {
        assertTrue(upstreamEmptyReason(json("""{"id":"x"}"""), null).contains("上游没有返回任何 choices"))
        assertTrue(upstreamEmptyReason(json("""{"choices":[]}"""), null).contains("上游没有返回任何 choices"))
    }

    @Test
    fun truncationAndContentFilterAreDistinguished() {
        val choices = json("""{"choices":[{"message":{"content":""}}]}""")
        assertTrue(upstreamEmptyReason(choices, "length").contains("finish_reason=length"))
        assertTrue(upstreamEmptyReason(choices, "content_filter").contains("finish_reason=content_filter"))
        // 上游正常收尾但正文为空：不是错误，交给调用方兜底成「空内容」。
        assertEquals("", upstreamEmptyReason(choices, "stop"))
        assertEquals("", upstreamEmptyReason(choices, null))
        // error 字段优先于 finish_reason：报错就是报错。
        assertTrue(upstreamEmptyReason(json("""{"error":"boom","choices":[]}"""), "length").contains("boom"))
    }

    @Test
    fun emptyEngineTurnEmitsNoticeAndErrorEnd() = runBlocking {
        val outcome = harness { JSONObject("""{"ok":true,"value":{"content":""}}""") }

        val text = outcome.text()
        assertTrue(text, text.contains("本轮没有任何输出"))
        assertTrue(text, text.contains("上游返回了空内容（没有正文也没有工具调用）"))
        val end = outcome.events.filterIsInstance<DshEvent.TurnEnd>().single()
        assertEquals("error", end.reason)
    }

    // ---- 夹具 ----

    private class Outcome(val events: List<DshEvent>) {
        fun text() = events.filterIsInstance<DshEvent.MessageChunk>().joinToString("") { it.text }
    }

    private fun json(raw: String): JsonObject = DshJson.parseToJsonElement(raw).jsonObject

    private suspend fun harness(engineChat: suspend (JSONObject) -> JSONObject): Outcome = coroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = LocalEngineTransport(
            scope = scope,
            configProvider = {
                LlmConfig("engine://deepseek-v4.1-flash", "test-key", "configured-model", systemPrompt = "test system", maxTokens = 256)
            },
            historyProvider = { emptyList<ChatMessage>() },
            shellExec = { "ok" },
            autoApprove = { true },
            engineChat = engineChat,
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
