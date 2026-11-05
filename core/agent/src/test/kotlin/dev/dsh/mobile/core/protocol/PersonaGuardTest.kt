package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.model.event.DshEvent
import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.core.protocol.web.HttpReply
import dev.dsh.mobile.core.protocol.web.LocalHttpServer
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * 人设会话的拒答判定与自动重试：判定是纯函数，重试在 transport 收尾处（正文上屏前）。
 */
class PersonaGuardTest {

    // ---- 判定 ----

    @Test
    fun chineseRefusalsHit() {
        listOf("我不能提供这个内容", "我无法继续", "我不会写这种", "抱歉，这个我做不到", "不予回应",
            "不宜展开", "无法生成", "不会生成正文", "无法提供帮助", "不便提供").forEach {
            assertTrue(it, RefusalGuard.isRefusal(it) != null)
        }
    }

    @Test
    fun englishRefusalsHit() {
        listOf("I can't help with that.", "I cannot assist with this request.", "I won't do that.",
            "I'm not able to continue.", "I\u2019M NOT ABLE to continue.", "Unable to comply.",
            "cannot assist", "can't help").forEach {
            assertTrue(it, RefusalGuard.isRefusal(it) != null)
        }
    }

    @Test
    fun normalProseIsNotARefusal() {
        val prose = "风从山谷里吹过来，他抬起头，看见远处的灯一盏一盏亮起来。"
        assertNull(RefusalGuard.isRefusal(prose))
        assertNull(RefusalGuard.isRefusal(""))
        assertNull(RefusalGuard.isRefusal("   "))
    }

    @Test
    fun longTextIsNeverARefusal() {
        // 正文里出现「我不能」是写作，不是拒答：超过 180 字一律不判。
        val long = "我不能".repeat(70) // 210 字
        assertTrue(long.length > RefusalGuard.MAX_REFUSAL_CHARS)
        assertNull(RefusalGuard.isRefusal(long))
    }

    @Test
    fun blankIsAnEmptyTurn() {
        assertTrue(RefusalGuard.isEmptyTurn(""))
        assertTrue(RefusalGuard.isEmptyTurn(" \n\t "))
        assertFalse(RefusalGuard.isEmptyTurn("正文"))
    }

    // ---- 重试循环 ----

    @Test
    fun refusalThenSuccessRetriesOnce() = runBlocking {
        var calls = 0
        val outcome = RefusalRetry().run("我不能提供这个内容。", "primary") { _, _ ->
            calls++
            "正文。"
        }
        assertEquals(1, calls)
        assertEquals(2, outcome.attempts)
        assertTrue(outcome.accepted)
        assertEquals("正文。", outcome.text)
        assertEquals("primary", outcome.model)
    }

    @Test
    fun emptyFirstTurnAlsoRetries() = runBlocking {
        var calls = 0
        val outcome = RefusalRetry().run("", "primary") { _, _ ->
            calls++
            "正文。"
        }
        assertEquals(1, calls)
        assertEquals(2, outcome.attempts)
        assertTrue(outcome.accepted)
    }

    @Test
    fun goodFirstTurnDoesNotRetry() = runBlocking {
        var calls = 0
        val outcome = RefusalRetry().run("正文。", "primary") { _, _ ->
            calls++
            "不该被调用"
        }
        assertEquals(0, calls)
        assertEquals(1, outcome.attempts)
        assertTrue(outcome.accepted)
    }

    @Test
    fun exhaustedRetriesUseFallbackModelAndReportHit() = runBlocking {
        val models = mutableListOf<String>()
        val outcome = RefusalRetry(maxRetries = 2, fallbackModels = listOf("fallback-1")).run("我不能。", "primary") { _, model ->
            models += model
            "抱歉，我无法生成。"
        }
        assertEquals(listOf("primary", "fallback-1"), models)
        assertEquals(3, outcome.attempts)
        assertFalse(outcome.accepted)
        assertEquals("fallback-1", outcome.model)
        // 短语表按顺序首命中：「抱歉，我无法生成。」先命中「我无法」。
        assertEquals("我无法", outcome.refusalHit)
    }

    // ---- transport 级：只落一条正文 ----

    @Test
    fun emptyFirstTurnIsReplacedByRetryText() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<JSONObject>())
        val outcome = harness(persona = "你是猫娘，用第一人称和主人说话", engineChat = { req ->
            seen += req
            if (seen.size == 1) JSONObject("""{"ok":true,"value":{"content":""}}""")
            else JSONObject("""{"ok":true,"value":{"content":"主人，我回来了。"}}""")
        })
        assertEquals(2, seen.size)
        assertEquals("主人，我回来了。", outcome.text())
        assertFalse(outcome.text(), outcome.text().contains("本轮没有任何输出"))
        // 重试请求里必须带上强框架提示。
        val retried = seen[1].getJSONArray("messages")
        val last = retried.getJSONObject(retried.length() - 1).getString("content")
        assertTrue(last, last.contains("创作模式"))
        // 框架在前、用户原话在后。
        assertTrue(last, last.trimEnd().endsWith("写一段夜色的描写"))
    }

    @Test
    fun refusalTextNeverReachesTheUser() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<JSONObject>())
        val outcome = harness(persona = "你是猫娘", engineChat = { req ->
            seen += req
            if (seen.size == 1) JSONObject("""{"ok":true,"value":{"content":"我不能提供这个内容。"}}""")
            else JSONObject("""{"ok":true,"value":{"content":"夜色落在窗台上。"}}""")
        })
        assertEquals(2, seen.size)
        val text = outcome.text()
        assertEquals("夜色落在窗台上。", text)
        assertFalse(text, text.contains("我不能"))
    }

    @Test
    fun noPersonaMeansNoRetry() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<JSONObject>())
        val outcome = harness(persona = "", engineChat = { req ->
            seen += req
            JSONObject("""{"ok":true,"value":{"content":"我不能提供这个内容。"}}""")
        })
        assertEquals(1, seen.size)
        assertTrue(outcome.text(), outcome.text().contains("我不能"))
    }

    // ---- 上游错误原因透传 ----

    @Test
    fun http401ReasonIsVisibleToCaller() = runBlocking {
        val server = LocalHttpServer {
            HttpReply(
                status = 401,
                headers = mapOf("Content-Type" to "application/json"),
                body = """{"error":{"type":"authentication_error","message":"Invalid API key"}}""".toByteArray(),
            )
        }.start()
        try {
            val outcome = harness(
                persona = "",
                config = LlmConfig(server.baseUrl + "/v1", "bad-key", "m", systemPrompt = "s"),
                engineChat = { JSONObject("""{"ok":false}""") },
            )
            val text = outcome.text()
            assertTrue(text, text.contains("HTTP 401 — authentication failed"))
            assertTrue(text, text.contains("authentication_error"))
            assertFalse(text, text.contains("本轮没有产生任何回复"))
        } finally {
            server.close()
        }
    }

    // ---- 夹具 ----

    private class Outcome(val events: List<DshEvent>) {
        fun text() = events.filterIsInstance<DshEvent.MessageChunk>().joinToString("") { it.text }
    }

    private fun defaultConfig() =
        LlmConfig("engine://deepseek-v4.1-flash", "test-key", "configured-model", systemPrompt = "test system", maxTokens = 256)

    private suspend fun harness(
        persona: String,
        config: LlmConfig = defaultConfig(),
        engineChat: suspend (JSONObject) -> JSONObject,
    ): Outcome = coroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = LocalEngineTransport(
            scope = scope,
            configProvider = { config },
            historyProvider = { emptyList<ChatMessage>() },
            shellExec = { "ok" },
            personaProvider = { persona },
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
            transport.prompt("session", "写一段夜色的描写", emptyList(), "turn")
            withTimeout(30_000) { done.await() }
            Outcome(events.toList())
        } finally {
            collector.cancelAndJoin()
            scope.cancel()
        }
    }
}
