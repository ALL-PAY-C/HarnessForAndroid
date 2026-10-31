package dev.dsh.mobile.feature.chat

import dev.dsh.mobile.core.protocol.CompactionStats
import dev.dsh.mobile.core.protocol.CompactResult
import dev.dsh.mobile.core.protocol.ContextUsage
import dev.dsh.mobile.core.protocol.ProjectedGoal
import dev.dsh.mobile.core.protocol.ProjectedTodo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 投影 → UI 文案的纯函数层：/compact 回执、压缩徽标、用量口径、待办分组。 */
class ProjectionUiTest {

    // ---------------------------------------------------------------- /compact 回执

    @Test fun compactNoteReportsRealNumbersWhenCompacted() {
        val note = compactNote(
            CompactResult(compacted = true, summaryChars = 1_200, beforeTokens = 9_000, afterTokens = 1_500, droppedMessages = 14),
        )
        assertTrue(note.contains("已压缩 14 条历史"))
        assertTrue(note.contains("省 7.5k tokens"))
        assertTrue(note.contains("9k → 1.5k"))
        assertTrue(note.contains("摘要 1.2k 字"))
    }

    @Test fun compactNoteKeepsEngineReasonWhenNothingCompacted() {
        val reason = "上下文还没超预算（已用约 900 tokens，预算 8000），不需要压缩。"
        assertEquals(reason, compactNote(CompactResult(compacted = false, message = reason)))
    }

    @Test fun compactNoteFallsBackWhenReasonBlank() {
        assertEquals("无需压缩", compactNote(CompactResult(compacted = false, message = "")))
    }

    @Test fun compactNoteOmitsSummaryWhenAbsent() {
        val note = compactNote(CompactResult(compacted = true, beforeTokens = 4_000, afterTokens = 3_000, droppedMessages = 2))
        assertTrue(note.contains("已压缩 2 条历史"))
        assertTrue(!note.contains("摘要"))
    }

    // ---------------------------------------------------------------- 压缩徽标 / 明细

    @Test fun badgeHiddenUntilSomethingWasCompacted() {
        assertNull(compactionBadge(CompactionStats(count = 0, beforeTokens = 0, afterTokens = 0, summaryChars = 0)))
        assertEquals("已压缩 3 次 · 省 2.5k", compactionBadge(CompactionStats(3, 5_000, 2_500, 800)))
    }

    @Test fun detailShowsCountSavingAndSummary() {
        assertEquals(
            "2 次 · 最近省 1.5k tokens · 摘要 600 字",
            compactionDetail(CompactionStats(2, 4_000, 2_500, 600)),
        )
    }

    // ---------------------------------------------------------------- 上下文用量口径

    @Test fun usagePercentUsesCharBudgetAndClamps() {
        assertNull(usagePercent(ContextUsage(usedChars = 100, fullChars = 200, budgetChars = 0)))
        assertEquals(50, usagePercent(ContextUsage(usedChars = 500, fullChars = 1_000, budgetChars = 1_000)))
        assertEquals(100, usagePercent(ContextUsage(usedChars = 5_000, fullChars = 6_000, budgetChars = 1_000)) ?: -1)
    }

    @Test fun usageLineFallsBackToTokenTextWithoutBudget() {
        val state = ChatUiState(sessionId = "s", tokensPerSecond = 12.0)
        assertEquals(state.tokenText, usageLine(state, ContextUsage()))
    }

    @Test fun usageLineUsesCharBudgetAndKeepsSpeed() {
        val state = ChatUiState(sessionId = "s", tokensPerSecond = 12.0)
        val line = usageLine(state, ContextUsage(usedChars = 25_000, fullChars = 40_000, budgetChars = 50_000))
        assertTrue(line.startsWith("上下文 50%"))
        assertTrue(line.contains("25k / 50k"))
        assertTrue(line.contains(speedText(12.0)))
    }

    // ---------------------------------------------------------------- 待办分组

    @Test fun groupsOrderRunningThenPendingThenDoneAndDropEmpty() {
        val todos = listOf(
            ProjectedTodo("写测试", ProjectedTodo.STATUS_PENDING),
            ProjectedTodo("改 UI", ProjectedTodo.STATUS_DONE),
            ProjectedTodo("接线", ProjectedTodo.STATUS_DOING),
        )
        val groups = todoGroups(todos)
        assertEquals(listOf("进行中", "待办", "已完成"), groups.map { it.title })
        assertEquals(listOf("接线"), groups[0].todos.map { it.content })
        assertEquals(listOf("改 UI"), groups[2].todos.map { it.content })
    }

    @Test fun groupsEmptyWhenNoTodos() {
        assertTrue(todoGroups(emptyList()).isEmpty())
    }

    @Test fun bulletsDistinguishThreeStates() {
        assertEquals("▶", todoBullet(ProjectedTodo.STATUS_DOING))
        assertEquals("○", todoBullet(ProjectedTodo.STATUS_PENDING))
        assertEquals("✓", todoBullet(ProjectedTodo.STATUS_DONE))
        assertEquals("○", todoBullet("weird"))
    }

    // ---------------------------------------------------------------- 抬头

    @Test fun headShowsPhaseAndProgress() {
        val goal = ProjectedGoal(objective = "接到 UI", phase = ProjectedGoal.PHASE_ACTIVE, revision = 1)
        val todos = listOf(
            ProjectedTodo("a", ProjectedTodo.STATUS_DONE),
            ProjectedTodo("b", ProjectedTodo.STATUS_PENDING),
        )
        assertEquals("目标 · 进行中 · 待办 1/2", projectionHead(goal, todos))
        assertEquals("目标 · 进行中", projectionHead(goal, emptyList()))
        assertEquals("待办 0/1", projectionHead(null, listOf(ProjectedTodo("a", ProjectedTodo.STATUS_PENDING))))
    }

    @Test fun blockedGoalUsesBlockedPhaseLabel() {
        val goal = ProjectedGoal(objective = "x", phase = ProjectedGoal.PHASE_BLOCKED, revision = 1, blockedReason = "等权限")
        assertEquals("受阻", goalPhaseLabel(goal))
    }
}
