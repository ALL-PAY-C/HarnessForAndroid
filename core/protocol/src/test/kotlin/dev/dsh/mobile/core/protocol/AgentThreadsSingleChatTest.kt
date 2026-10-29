package dev.dsh.mobile.core.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class AgentThreadsSingleChatTest {
    private fun msg(role: String, text: String) = buildJsonObject { put("role", role); put("content", text) }
    private fun text(m: JsonObject) = m["content"]!!.jsonPrimitive.content

    @Test fun underBudgetPreservesAllOriginalMessages() {
        val history = listOf(msg("user", "你好"), msg("assistant", "您好"))
        val user = listOf(msg("user", "继续"))
        val result = AgentThreads().assembleForSingleChat("s", 100, "sys", history, user)
        assertEquals(listOf(msg("system", "sys")) + history + user, result.messages)
        assertEquals(9, result.fullChars)
        assertEquals(9, result.usedChars)
    }

    @Test fun overBudgetSummarizesOlderHistoryAndRetainsTailAndUser() {
        val history = (1..12).map { msg(if (it % 2 == 0) "assistant" else "user", "$it:" + "a".repeat(100)) }
        val user = listOf(msg("user", "本轮问题"))
        val result = AgentThreads().assembleForSingleChat("s", 500, "sys", history, user, keepRecent = 2)
        assertTrue(result.usedChars <= 500)
        assertTrue(result.usedChars < result.fullChars / 2)
        assertEquals(3 + history.sumOf { text(it).length } + 4, result.fullChars)
        assertEquals(history.takeLast(2) + user, result.messages.takeLast(3))
        assertTrue(result.messages.any { text(it).startsWith("历史摘要") })
        assertEquals(result.usedChars, result.messages.sumOf { text(it).length })
        assertEquals(result, AgentThreads().assembleForSingleChat("s", 500, "sys", history, user, 2))
    }

    @Test fun tightBudgetStillKeepsUserUnchanged() {
        val user = listOf(msg("user", "必须保留"))
        val result = AgentThreads().assembleForSingleChat("s", 7, "sys", listOf(msg("assistant", "a".repeat(100))), user)
        assertEquals(listOf(msg("system", "sys")) + user, result.messages)
        assertEquals(7, result.usedChars)
        assertEquals(107, result.fullChars)
    }

    @Test fun impossibleBudgetReportsOverflowWithoutDroppingCurrentUser() {
        val user = listOf(msg("user", "必须保留"))
        val result = AgentThreads().assembleForSingleChat("s", 1, "sys", emptyList(), user)
        assertEquals(listOf(msg("system", "sys")) + user, result.messages)
        assertTrue(result.overBudget)
        assertEquals(6, result.overflowChars)
        assertEquals(7, result.usedChars)
    }
}
