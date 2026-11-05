package dev.dsh.mobile.core.protocol.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T9.8-S4 回捞的纯函数层：抽词、该不该查、块形态与预算。全部无 IO。 */
class RecallPlannerTest {

    @Test
    fun pathAndExtensionWinOverPlainWords() {
        val keys = RecallPlanner.keywords("看下 core/data/db/WarmLayerDao.kt 里的 searchInSession 为什么返回空")
        assertEquals("core/data/db/WarmLayerDao.kt", keys.first())
        assertTrue(keys.toString(), keys.contains("searchInSession"))
        // 虚词切完不留碎片：里的 / 为什么 都不该成为检索词。
        assertFalse(keys.toString(), keys.contains("里的"))
        assertFalse(keys.toString(), keys.contains("为什么"))
    }

    @Test
    fun inlineCodeIdentifiersArePicked() {
        val keys = RecallPlanner.keywords("报错在 `fetchRecall` 里，代码块里是 WarmAssembly")
        assertTrue(keys.toString(), keys.contains("fetchRecall"))
    }

    @Test
    fun chineseTermsKeepTailWindow() {
        val keys = RecallPlanner.keywords("历史被截断以后就再也找不到之前说过的接口约定了")
        assertTrue(keys.toString(), keys.contains("接口约定"))
    }

    @Test
    fun stopWordsAndShortTokensAreDropped() {
        assertEquals(emptyList<String>(), RecallPlanner.keywords("的 了 我 你 please a b ok"))
    }

    @Test
    fun keywordsDedupeAndRespectLimit() {
        val keys = RecallPlanner.keywords("WarmLayerDao WarmLayerDao warmlayerdao 温层 温层", limit = 3)
        assertTrue(keys.toString(), keys.size <= 3)
        assertEquals(keys.size, keys.map { it.lowercase() }.distinct().size)
    }

    @Test
    fun needsRecallRequiresBothTruncationAndTerms() {
        assertFalse(RecallPlanner.needsRecall("温层怎么刷新", historyChars = 100, budgetChars = 1_000))
        assertFalse(RecallPlanner.needsRecall("的 了 是", historyChars = 2_000, budgetChars = 1_000))
        assertTrue(RecallPlanner.needsRecall("温层怎么刷新", historyChars = 2_000, budgetChars = 1_000))
    }

    @Test
    fun formatHasFixedFrameAndDropsOldestFirst() {
        val hits = listOf(
            RecallHit(880L, "assistant", "新的一条"),
            RecallHit(120L, "user", "旧的一条"),
        )
        val lines = RecallPlanner.format(hits)!!.split("\n")
        assertEquals("[recall R1]", lines.first())
        assertEquals("[/recall]", lines.last())
        assertEquals("- [rowid 880] assistant: 新的一条", lines[1])
        val tiny = RecallPlanner.format(hits, capChars = 60)!!
        assertTrue(tiny, tiny.contains("rowid 880"))
        assertFalse(tiny, tiny.contains("rowid 120"))
        assertNull(RecallPlanner.format(emptyList()))
    }

    @Test
    fun formatClipsSingleBody() {
        val body = RecallPlanner.format(listOf(RecallHit(1L, "user", "x".repeat(2_000))))!!.split("\n")[1]
        assertTrue(body, body.length <= RecallPlanner.BODY_LIMIT + 32)
    }

    @Test
    fun tightBudgetShrinksRecallNotWarm() {
        assertEquals(RecallPlanner.CAP_CHARS, RecallPlanner.capFor(usedChars = 100, budgetChars = 40_000))
        assertEquals(RecallPlanner.TIGHT_CAP_CHARS, RecallPlanner.capFor(usedChars = 30_000, budgetChars = 31_000))
    }

    @Test
    fun roundMarkerAndLikeEscaping() {
        assertEquals(2, RecallPlanner.roundOf("R2"))
        assertEquals(12, RecallPlanner.roundOf("r12"))
        assertNull(RecallPlanner.roundOf("温层"))
        assertEquals("a\\%b\\_c\\\\d", RecallPlanner.escapeLike("a%b_c\\d"))
    }

    @Test
    fun matchFiltersByTermAndKeepsNewest() {
        val rows = listOf(
            RecallHit(1L, "user", "温层怎么刷"),
            RecallHit(2L, "user", "无关内容"),
            RecallHit(3L, "assistant", "温层水位"),
        )
        assertEquals(listOf(3L, 1L), RecallPlanner.match(rows, listOf("温层")).map { it.rowId })
        assertEquals(emptyList<RecallHit>(), RecallPlanner.match(rows, emptyList()))
    }
}