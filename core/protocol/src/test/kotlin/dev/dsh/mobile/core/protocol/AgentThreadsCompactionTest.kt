package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.model.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0-1：真压缩（LLM 结构化摘要插到保留段之前 + 持久化）；P0-2：/compact 入口。 */
class AgentThreadsCompactionTest {
    private fun msg(role: String, text: String) = buildJsonObject { put("role", role); put("content", text) }
    private fun text(m: JsonObject) = m["content"]!!.jsonPrimitive.content

    private fun bigHistory(n: Int = 12) =
        (1..n).map { msg(if (it % 2 == 0) "assistant" else "user", "$it:" + "a".repeat(200)) }

    @Test
    fun noPlanWhenUnderBudget() {
        val threads = AgentThreads()
        val plan = threads.planCompaction("s", 100_000, "sys", bigHistory(), listOf(msg("user", "问")))
        assertNull(plan)
    }

    @Test
    fun planSplitsDroppedFromKeptTail() {
        val threads = AgentThreads()
        val history = bigHistory(12)
        val plan = threads.planCompaction("s", 500, "sys", history, listOf(msg("user", "本轮")), keepRecent = 3)!!
        assertEquals(3, plan.kept)
        assertEquals(history.dropLast(3), plan.dropped)
        assertTrue(plan.beforeChars > 0)
        // 提示词必须带五段结构，否则模型会退回「复述原文」。
        val prompt = threads.compactionPrompt(plan)
        listOf("角色：", "任务：", "已决定：", "未完成：", "关键文件路径：").forEach {
            assertTrue("缺段落 $it", prompt.contains(it))
        }
        assertTrue(prompt.contains("1:" + "a".repeat(20)))
    }

    @Test
    fun storedSummaryIsInsertedBeforeRetainedTail() {
        val threads = AgentThreads()
        val history = bigHistory(12)
        val user = listOf(msg("user", "本轮问题"))
        threads.setSummary("s", "任务：压缩上下文\n未完成：跑测试")
        val result = threads.assembleForSingleChat("s", 500, "sys", history, user, keepRecent = 2)
        // 摘要作为 system 消息排在保留尾部之前，而不是被当成普通历史丢掉。
        val summaryIdx = result.messages.indexOfFirst { text(it).startsWith("上下文摘要") }
        assertTrue(summaryIdx > 0)
        assertTrue(text(result.messages[summaryIdx]).contains("未完成：跑测试"))
        assertTrue(summaryIdx < result.messages.size - 2)
        assertEquals(history.takeLast(2) + user, result.messages.takeLast(3))
        // 摘要计入预算，不是白送的。
        assertEquals(result.usedChars, result.messages.sumOf { text(it).length })
        assertEquals("任务：压缩上下文\n未完成：跑测试", threads.summary("s"))
    }

    @Test
    fun clearDropsStoredSummary() {
        val threads = AgentThreads()
        threads.setSummary("s", "摘要")
        threads.clear("s")
        assertEquals("", threads.summary("s"))
    }

    @Test
    fun compactGoesThroughTransportAndUpdatesProjection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = LocalEngineTransport(
            scope = scope,
            // cfg 为 null：走本地节选兜底，仍然必须产出一份摘要并记账（离线也要能压）。
            configProvider = { null },
            historyProvider = {
                listOf<ChatMessage>(
                    ChatMessage("m0", "s", ChatMessage.Role.SYSTEM, "sys"),
                ) + (1..40).map {
                    ChatMessage(
                        "m" + it, "s",
                        if (it % 2 == 0) ChatMessage.Role.ASSISTANT else ChatMessage.Role.USER,
                        "早期" + it + "：" + "内容".repeat(120),
                    )
                }
            },
            shellExec = { "ok" },
            // 压缩预算现在由模型窗口推导（默认 32k → 25600 tokens），不再看 contextBudgetProvider 的字符数。
            // 这里显式给一个小预算，验证「超预算就真压」的路径；窗口口径另有单测。
            contextWindowProvider = { 8_000 },
        )
        val result = transport.compact("s", budgetTokens = 2_000)
        assertTrue(result.message, result.compacted)
        assertTrue(result.summaryChars > 0)
        assertTrue(result.beforeTokens > result.afterTokens)
        assertTrue(result.message, result.message.contains("已压缩"))
        val snap = transport.projections.snapshot("s")
        assertEquals(1, snap.compaction.count)
        assertEquals(result.summaryChars, snap.summaryChars)
        assertFalse(snap.compaction.savedTokens < 0)

        // 没超预算时明确回报「不需要压缩」，不假装压过。
        val small = LocalEngineTransport(
            scope = scope,
            configProvider = { null },
            historyProvider = { listOf(ChatMessage("m1", "s2", ChatMessage.Role.USER, "短")) },
            shellExec = { "ok" },
            contextBudgetProvider = { 100_000 },
        ).compact("s2", budgetTokens = 0)
        assertFalse(small.compacted)
        assertTrue(small.message, small.message.contains("不需要压缩"))
        // 回执里的两个数必须跟顶栏同一口径（token、窗口 32k），不能再冒出「预算 6000」这种字符换算。
        assertTrue(small.message, small.message.contains("预算 25600 tokens"))
        assertTrue(small.message, small.message.contains("窗口 32000"))
    }

    @Test
    fun windowDerivedBudgetCompactsTwentyKHistory() {
        // 32k 窗口 → 预算 25600 tokens（留 20% 给回答）；窗口缺失时按 32k 兜底。
        assertEquals(25_600, ContextBudget.budgetTokens(32_000))
        assertEquals(102_400, ContextBudget.budgetChars(32_000))
        assertEquals(25_600, ContextBudget.budgetTokens(0))

        // 20k tokens 历史（4 字符/token ≈ 80k 字符）+ 真实体量的系统提示词（24k 字符带工具说明）。
        val threads = AgentThreads()
        val history = (1..20).map {
            msg(if (it % 2 == 0) "assistant" else "user", "轮" + it + "：" + "历".repeat(4_000))
        }
        val system = "s".repeat(24_000)
        val plan = threads.planCompaction(
            "s20k", ContextBudget.budgetChars(32_000), system, history, listOf(msg("user", "本轮")),
        )
        // 旧实现在这里返回 null（固定段 24k 字符就被判超预算），于是 /compact 永远「不需要压缩」。
        assertTrue("20k tokens 历史 + 32k 窗口必须判定需要压缩", plan != null)
        assertEquals(history.size - 8, plan!!.dropped.size)
    }
}
