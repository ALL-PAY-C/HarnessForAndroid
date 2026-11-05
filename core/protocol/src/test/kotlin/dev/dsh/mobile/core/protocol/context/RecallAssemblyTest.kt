package dev.dsh.mobile.core.protocol.context

import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.core.model.context.WarmLayer
import dev.dsh.mobile.core.model.context.WarmLayerStore
import dev.dsh.mobile.core.model.event.DshEvent
import dev.dsh.mobile.core.protocol.LlmConfig
import dev.dsh.mobile.core.protocol.LocalEngineTransport
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * T9.8-S4 装配锁：回捞块插在哪、以及**不插**的时候提示词逐字节不变。
 *
 * 断言对象是真正发给上游的请求体（engineChat 收到的 messages 数组），不是本类里拼的中间变量
 * —— 与 S3 的 WarmAssemblyTest 同一手法：比的是最终字节，不是意图。
 */
class RecallAssemblyTest {

    @Test
    fun withoutRecallChannelsPromptIsByteIdentical() = runBlocking {
        val history = shortHistory()
        val base = capture(history = history, budget = 5_000)
        val withChannels = capture(history = history, budget = 5_000, search = { _, _, _ -> hits() })
        // 没注入通道 vs 注入了通道但历史没被截断：两条请求体必须一个字都不差。
        assertEquals(base.messages, withChannels.messages)
        // 顺带证明「通道在但没查」：一次 SQL 都没发。
        assertEquals(0, withChannels.searchCalls.get())
    }

    @Test
    fun truncatedHistoryButNoHitIsByteIdentical() = runBlocking {
        val history = longHistory()
        val base = capture(history = history, budget = 500)
        val empty = capture(history = history, budget = 500, search = { _, _, _ -> emptyList() })
        assertEquals(base.messages, empty.messages)
        assertTrue(empty.searchCalls.get() > 0)
    }

    @Test
    fun recallBlockSitsBetweenSystemAndHistory() = runBlocking {
        val out = capture(history = longHistory(), budget = 500, search = { _, _, _ -> hits() })
        val msgs = out.messages
        // 没有温层时：system → 回捞 → 历史 → 尾部 user（回捞落到 index 1）。
        assertEquals(1, out.recallIndex)
        assertTrue(msgs[out.recallIndex].contains("[recall R1]"))
        assertTrue(msgs[out.recallIndex].contains("[/recall]"))
        assertTrue(msgs[out.recallIndex].contains("[rowid 120]"))
        assertEquals("user", out.lastRole)
        assertEquals("system", out.firstRole)
        assertFalse(out.recallIndex == msgs.size - 1)
    }

    @Test
    fun recallBlockFollowsWarmLayer() = runBlocking {
        val out = capture(
            history = longHistory(),
            budget = 500,
            search = { _, _, _ -> hits() },
            warm = WarmLayer(primaryGoal = "旧目标一句话", coveredUntilMessageId = 5L, updatedAt = 1L),
        )
        // 有温层时：system → 温层 → 回捞 → 历史 → user，回捞让到 index 2。
        assertTrue(out.messages[1].contains("## 温层"))
        assertEquals(2, out.recallIndex)
        assertTrue(out.messages[out.recallIndex].contains("[recall R1]"))
    }

    @Test
    fun recallIsCappedWhenBudgetIsTight() = runBlocking {
        // 预算吃紧（历史远超预算）：上限收到 800 —— 回捞先让路，温层 LOCKED 不动。
        val tight = capture(history = longHistory(), budget = 500, search = { _, _, _ -> hits() })
        assertTrue(tight.recallIndex > 0)
        assertTrue(
            "块长 " + tight.messages[tight.recallIndex].length,
            tight.messages[tight.recallIndex].length <= RecallPlanner.TIGHT_CAP_CHARS + 64,
        )
    }

    // ---- 夹具 ----

    private class Captured(
        val messages: List<String>,
        val recallIndex: Int,
        val firstRole: String,
        val lastRole: String,
        val searchCalls: AtomicInteger,
    )

    private fun shortHistory(): List<ChatMessage> = listOf(
        ChatMessage("m1", "session", ChatMessage.Role.USER, "短问题一"),
        ChatMessage("m2", "session", ChatMessage.Role.ASSISTANT, "短回答一"),
    )

    /** 三条长历史：字符数远超 500 的预算，装配一定会裁，回捞才有理由发生。 */
    private fun longHistory(): List<ChatMessage> = (1..3).map {
        ChatMessage("h" + it, "session", ChatMessage.Role.ASSISTANT, "历史" + it + " " + "x".repeat(300))
    }

    private fun hits(): List<RecallHit> = listOf(
        RecallHit(120L, "user", "早先说过的接口约定：upload 走分片"),
        RecallHit(880L, "assistant", "上一轮结论：先写测试再改装配"),
    )

    private fun chatJson(): JSONObject = JSONObject(
        """{"ok":true,"value":{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":1}}}""",
    )

    private suspend fun capture(
        history: List<ChatMessage>,
        budget: Int,
        search: (suspend (String, String, Int) -> List<RecallHit>)? = null,
        warm: WarmLayer? = null,
    ): Captured = coroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        val calls = AtomicInteger(0)
        val searchFn: (suspend (String, String, Int) -> List<RecallHit>)? =
            if (search == null) {
                null
            } else {
                { sid: String, like: String, limit: Int ->
                    calls.incrementAndGet()
                    search(sid, like, limit)
                }
            }
        val transport = LocalEngineTransport(
            scope = scope,
            configProvider = {
                LlmConfig("engine://deepseek-v4.1-flash", "test-key", "configured-model", systemPrompt = "test system", maxTokens = 256)
            },
            historyProvider = { history },
            shellExec = { "ok" },
            autoApprove = { true },
            contextBudgetProvider = { budget },
            engineChat = { payload ->
                bodies += payload.getJSONArray("messages").toString()
                chatJson()
            },
            warmStore = warm?.let { layer -> FixedWarmStore(layer) },
            recallSearch = searchFn,
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
            transport.prompt("session", "上次那个 upload 分片接口怎么写的", emptyList(), "turn")
            withTimeout(30_000) { done.await() }
            val body = bodies.firstOrNull() ?: error("engineChat 没收到请求")
            val arr = org.json.JSONArray(body)
            val contents = (0 until arr.length()).map { i ->
                arr.getJSONObject(i).optString("content")
            }
            val roles = (0 until arr.length()).map { i -> arr.getJSONObject(i).optString("role") }
            Captured(
                messages = contents,
                recallIndex = contents.indexOfFirst { it.contains("[recall R1]") },
                firstRole = roles.first(),
                lastRole = roles.last(),
                searchCalls = calls,
            )
        } finally {
            collector.cancelAndJoin()
            scope.cancel()
        }
    }

    private class FixedWarmStore(private val layer: WarmLayer) : WarmLayerStore {
        override fun observe(sessionId: String) = kotlinx.coroutines.flow.flowOf(layer)
        override suspend fun read(sessionId: String): WarmLayer = layer
        override suspend fun layer(sessionId: String): WarmLayer = layer
        override suspend fun write(sessionId: String, layer: WarmLayer) = Unit
        override suspend fun clear(sessionId: String) = Unit
    }
}