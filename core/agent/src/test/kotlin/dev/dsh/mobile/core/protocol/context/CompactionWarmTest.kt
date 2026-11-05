package dev.dsh.mobile.core.protocol.context

import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.core.model.context.WarmLayer
import dev.dsh.mobile.core.model.context.WarmLayerStore
import dev.dsh.mobile.core.model.context.WarmMessage
import dev.dsh.mobile.core.model.context.WarmSource
import dev.dsh.mobile.core.protocol.AgentThreads
import dev.dsh.mobile.core.protocol.ContextSummaryStore
import dev.dsh.mobile.core.protocol.LlmConfig
import dev.dsh.mobile.core.protocol.SessionProjections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T9.8-S3 压缩侧单测：真压缩后温层水位前进、插件标记在场时一次模型调用都不发。
 *
 * 全部用 fake：模型往返计数、内存温层存储、固定时钟，不联网也不碰 Room。
 */
class CompactionWarmTest {

    private fun msg(role: String, text: String) = buildJsonObject {
        put("role", role)
        put("content", text)
    }

    private fun reply(text: String) = buildJsonObject {
        putJsonArray("choices") {
            addJsonObject { putJsonObject("message") { put("content", text) } }
        }
    }

    private fun systemOf(body: JsonObject): String =
        body["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content

    /** 内存温层存储（core:data 的 Room 实现在单测里用不上）。 */
    private class FakeWarmStore(initial: WarmLayer? = null) : WarmLayerStore {
        var stored: WarmLayer? = initial
        override fun observe(sessionId: String): Flow<WarmLayer?> = MutableStateFlow(stored)
        override suspend fun read(sessionId: String): WarmLayer? = stored
        override suspend fun layer(sessionId: String): WarmLayer? = stored
        override suspend fun write(sessionId: String, layer: WarmLayer) {
            stored = layer
        }

        override suspend fun clear(sessionId: String) {
            stored = null
        }
    }

    private val nineSections = """
        主要目标：压缩后温层跟上新摘要
        硬约束：
        - 温层为准
        待办：
        - 跑四模块测试
        当前工作：写 S3
        下一步：验证水位
    """.trimIndent()

    private val fiveSections = """
        角色：Android 端 agent
        任务：接温层
        已决定：温层按 rowid 水位去重
        未完成：S4 回捞
        关键文件路径：Compaction.kt
    """.trimIndent()

    private val historyRows = (1L..12L).map { WarmMessage(it, if (it % 2 == 0L) "assistant" else "user", "第 " + it + " 条历史内容") }

    private fun compaction(
        store: FakeWarmStore,
        calls: MutableList<String>,
        onCompacted: suspend (String) -> Unit = {},
    ): Compaction {
        val cfg = LlmConfig(baseUrl = "http://127.0.0.1", apiKey = "k", model = "m")
        return Compaction(
            threads = AgentThreads(),
            summaryStore = ContextSummaryStore.None,
            projections = SessionProjections(),
            contextWindowProvider = { 128_000 },
            historyProvider = { emptyList<ChatMessage>() },
            configProvider = { cfg },
            engineRpc = null,
            postOnce = { _, body ->
                val system = systemOf(body)
                calls += system
                // 压缩摘要请求 vs 温层请求：两者都走同一个上游往返，靠 system 提示词区分。
                if (system == WARM_SYSTEM_PROMPT) reply(nineSections) else reply(fiveSections)
            },
            usedTokensOf = { null },
            onCompacted = onCompacted,
            warmStore = store,
            warmHistoryProvider = { historyRows },
            warmClock = { 2_000L },
        )
    }

    private fun overBudgetMessages(): List<JsonObject> = buildList {
        add(msg("system", "sys"))
        (1..12).forEach { add(msg(if (it % 2 == 0) "assistant" else "user", "a".repeat(100))) }
        add(msg("user", "现在"))
    }

    // ------------------------------------------------ 要求 2：真压缩后温层水位前进

    @Test
    fun realCompactionRefreshesWarmLayerAndAdvancesWatermark() = runBlocking {
        val store = FakeWarmStore(
            WarmLayer(primaryGoal = "旧目标", coveredUntilMessageId = 5L, warmVersion = 1),
        )
        val calls = ArrayList<String>()
        val compaction = compaction(store, calls)

        val summary = compaction.maybeSummarize("s1", LlmConfig("http://127.0.0.1", "k", "m"), 500, overBudgetMessages())

        // 摘要真生效（不是退回本地节选）。
        assertNotNull(summary)
        assertTrue(summary!!.contains("任务"))
        // 两次模型往返：一次摘要、一次压缩后的温层刷新（顺序固定）。
        assertEquals(2, calls.size)
        assertEquals(WARM_SYSTEM_PROMPT, calls[1])
        // 温层跟着压缩往前走：水位到本批最后一条 rowid，版本 +1，来源仍是本地。
        val layer = store.stored!!
        assertEquals(12L, layer.coveredUntilMessageId)
        assertEquals(2, layer.warmVersion)
        assertEquals(WarmSource.LOCAL, layer.source)
        assertEquals(2_000L, layer.updatedAt)
        assertTrue(layer.render().contains("压缩后温层跟上新摘要"))
    }

    // ------------------------------------------------ 要求 3：插件在场 = 0 次模型调用

    @Test
    fun pluginCheckpointSkipsLocalModelAndBuildsWarmFromPluginText() = runBlocking {
        val store = FakeWarmStore()
        val calls = ArrayList<String>()
        val compaction = compaction(store, calls)
        val plugin = "<compacted-summary>\n主要目标：插件已经压过\n硬约束：\n- 本地不再调模型\n</compacted-summary>"
        val messages = listOf(
            msg("system", "sys"),
            msg("user", "早"),
            msg("assistant", plugin),
            msg("user", "现在"),
        )

        val summary = compaction.maybeSummarize("s1", LlmConfig("http://127.0.0.1", "k", "m"), 100_000, messages)

        // 插件已压：本地既不写摘要、也不发任何模型请求。
        assertNull(summary)
        assertEquals(0, calls.size)
        // 温层直接用插件给的内容，来源标 PLUGIN，水位取现有历史最大 rowid。
        val layer = store.stored!!
        assertEquals(WarmSource.PLUGIN, layer.source)
        assertEquals(12L, layer.coveredUntilMessageId)
        assertEquals("插件已经压过", layer.primaryGoal)
        assertEquals(1, layer.warmVersion)
        assertEquals(2_000L, layer.updatedAt)
    }
}
