package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.model.ChatMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

/**
 * 引擎路由（baseUrl = engine://<模型 id>）：请求交给注入的 engineChat，不再走 HTTP。
 */
class EngineRouteTransportTest {

    @Test fun engineRouteCallsInjectedEngineAndUsesItsContent() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<JSONObject>())
        val result = harness(
            baseUrl = "engine://deepseek-v4.1-flash",
            engineChat = { payload ->
                calls += payload
                JSONObject("""{"ok":true,"value":{"content":"引擎回复正文","usage":{"prompt_tokens":7,"completion_tokens":3}}}""")
            },
        )
        assertEquals(1, calls.size)
        val payload = calls.single()
        // engine:// 后面那段是插件声明的模型 id，而且请求体其它字段原样带过去。
        assertEquals("deepseek-v4.1-flash", payload.getString("model"))
        assertTrue(payload.getJSONArray("messages").length() > 0)
        assertEquals("引擎回复正文", result.text())
        assertNull(result.failure())
        assertFalse(result.events.toString(), result.events.any { it is DshEvent.TurnEnd && it.reason == "error" })
    }

    @Test fun engineRouteFallsBackToConfiguredModelWhenProbeIdIsEmpty() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<JSONObject>())
        harness(
            baseUrl = "engine://",
            configuredModel = "plugin-model",
            engineChat = { payload ->
                calls += payload
                JSONObject("""{"ok":true,"value":{"content":"ok"}}""")
            },
        )
        assertEquals("plugin-model", calls.single().getString("model"))
    }

    @Test fun engineRouteWithoutBridgeFailsWithNotReadyHint() = runBlocking {
        val result = harness(baseUrl = "engine://deepseek-v4.1-flash", engineChat = null)
        assertNotNull(result.failure())
        assertTrue(result.failure()!!, result.failure()!!.contains("引擎未就绪"))
        // 只有那一行失败提示：引擎没就绪时不能编出正文。
        assertEquals("\n\n[请求失败] 引擎未就绪：先在插件页打开一次，让 :node 进程起来", result.text())
    }

    @Test fun plainHttpBaseUrlNeverTouchesTheEngine() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<JSONObject>())
        // 127.0.0.1:1 一定连不上：这条用例只验证「非 engine:// 不走引擎」。
        val result = harness(
            baseUrl = "http://127.0.0.1:1/v1",
            engineChat = { payload -> calls += payload; JSONObject("""{"ok":true,"value":{"content":"不该出现"}}""") },
        )
        assertEquals(0, calls.size)
        assertNotNull(result.failure())
        assertTrue(result.text(), result.text().contains("[请求失败]"))
        assertFalse(result.text(), result.text().contains("不该出现"))
    }

    @Test fun engineModelsReadsProviderIdsAndNeedsBridge() = runBlocking {
        val transport = transport(
            baseUrl = "engine://deepseek-v4.1-flash",
            configuredModel = "m",
            engineChat = null,
            engineRpc = { path, endpoint, payload ->
                assertEquals("/llm", path)
                assertEquals("models", endpoint)
                assertEquals(0, payload.length())
                JSONObject("""{"ok":true,"value":{"models":[{"id":"deepseek-v4.1-flash"},"qwen3-8b"]}}""")
            },
        )
        assertEquals(listOf("deepseek-v4.1-flash", "qwen3-8b"), transport.engineModels())
        // 没有 engineRpc 时给同一句「引擎未就绪」，别抛别的错。
        val noRpc = transport(baseUrl = "engine://x", configuredModel = "m", engineChat = null, engineRpc = null)
        val e = runCatching { noRpc.engineModels() }.exceptionOrNull()
        assertTrue(e?.message.orEmpty(), e?.message.orEmpty().contains("引擎未就绪"))
    }

    // ---- 夹具 ----

    private class Outcome(val events: List<DshEvent>) {
        fun text() = events.filterIsInstance<DshEvent.MessageChunk>().joinToString("") { it.text }
        /** chat 侧把请求异常翻成「[请求失败] ...」正文，这里取出那一行做断言。 */
        fun failure(): String? = text().takeIf { it.contains("[请求失败]") }?.removePrefix("\n\n")
    }

    private fun transport(
        baseUrl: String,
        configuredModel: String = "configured-model",
        engineChat: (suspend (JSONObject) -> JSONObject)?,
        engineRpc: (suspend (String, String, JSONObject) -> JSONObject)? = null,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) = LocalEngineTransport(
        scope = scope,
        configProvider = { LlmConfig(baseUrl, "test-key", configuredModel, systemPrompt = "test system", maxTokens = 256) },
        historyProvider = { emptyList<ChatMessage>() },
        shellExec = { "ok" },
        autoApprove = { true },
        engineChat = engineChat,
        engineRpc = engineRpc,
    )

    private suspend fun harness(
        baseUrl: String,
        configuredModel: String = "configured-model",
        engineChat: (suspend (JSONObject) -> JSONObject)?,
        engineRpc: (suspend (String, String, JSONObject) -> JSONObject)? = null,
    ): Outcome = coroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = transport(baseUrl, configuredModel, engineChat, engineRpc, scope)
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
