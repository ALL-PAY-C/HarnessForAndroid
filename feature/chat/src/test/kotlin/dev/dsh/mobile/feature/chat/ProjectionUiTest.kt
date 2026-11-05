package dev.dsh.mobile.feature.chat

import dev.dsh.mobile.core.model.projection.CompactionStats
import dev.dsh.mobile.core.model.compaction.CompactResult
import dev.dsh.mobile.core.model.projection.ContextUsage
import dev.dsh.mobile.core.model.projection.ProjectedGoal
import dev.dsh.mobile.core.model.projection.ProjectedTodo
import dev.dsh.mobile.design.R as DesignR
import dev.dsh.mobile.design.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 投影 → UI 文案的纯函数层：/compact 回执、压缩徽标、用量口径、待办分组。
 *
 * T9.19：函数只产出 [UiText]（资源 id + 参数 / 原样串），这里按 UiText 断言，
 * 不绑死任何一种语言的渲染结果；语言本身由 design 的 strings.xml 决定。
 */
class ProjectionUiTest {

    // ---------------------------------------------------------------- /compact 回执

    @Test fun compactNoteReportsRealNumbersWhenCompacted() {
        val note = compactNote(
            CompactResult(compacted = true, summaryChars = 1_200, beforeTokens = 9_000, afterTokens = 1_500, droppedMessages = 14),
        )
        assertEquals(
            UiText.Res(DesignR.string.chat_compact_note_summary, listOf(14, "7.5k", "9k", "1.5k", "1.2k")),
            note,
        )
    }

    @Test fun compactNoteKeepsEngineReasonWhenNothingCompacted() {
        val reason = "上下文还没超预算（已用约 900 tokens，预算 8000），不需要压缩。"
        assertEquals(UiText.Raw(reason), compactNote(CompactResult(compacted = false, message = reason)))
    }

    @Test fun compactNoteFallsBackWhenReasonBlank() {
        assertEquals(
            UiText.Res(DesignR.string.chat_compact_nothing),
            compactNote(CompactResult(compacted = false, message = "")),
        )
    }

    @Test fun compactNoteOmitsSummaryWhenAbsent() {
        val note = compactNote(CompactResult(compacted = true, beforeTokens = 4_000, afterTokens = 3_000, droppedMessages = 2))
        assertEquals(
            UiText.Res(DesignR.string.chat_compact_note, listOf(2, "1k", "4k", "3k")),
            note,
        )
    }

    // ---------------------------------------------------------------- 压缩徽标 / 明细

    @Test fun badgeHiddenUntilSomethingWasCompacted() {
        assertNull(compactionBadge(CompactionStats(count = 0, beforeTokens = 0, afterTokens = 0, summaryChars = 0)))
        assertEquals(
            UiText.Res(DesignR.string.chat_compact_badge, listOf(3, "2.5k")),
            compactionBadge(CompactionStats(3, 5_000, 2_500, 800)),
        )
    }

    @Test fun detailShowsCountSavingAndSummary() {
        assertEquals(
            UiText.Res(DesignR.string.chat_compact_detail, listOf(2, "1.5k", "600")),
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
        assertEquals(
            UiText.Res(
                DesignR.string.chat_usage_line,
                listOf(50, "25k", "50k", " · " + speedText(12.0)),
            ),
            line,
        )
    }

    // ---------------------------------------------------------------- 待办分组

    @Test fun groupsOrderRunningThenPendingThenDoneAndDropEmpty() {
        val todos = listOf(
            ProjectedTodo("写测试", ProjectedTodo.STATUS_PENDING),
            ProjectedTodo("改 UI", ProjectedTodo.STATUS_DONE),
            ProjectedTodo("接线", ProjectedTodo.STATUS_DOING),
        )
        val groups = todoGroups(todos)
        assertEquals(
            listOf(
                UiText.Res(DesignR.string.chat_todo_doing),
                UiText.Res(DesignR.string.chat_todo_pending),
                UiText.Res(DesignR.string.chat_todo_done),
            ),
            groups.map { it.title },
        )
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
        assertEquals(
            UiText.Res(DesignR.string.chat_proj_head_both, listOf(UiText.Res(DesignR.string.chat_phase_active), 1, 2)),
            projectionHead(goal, todos),
        )
        assertEquals(
            UiText.Res(DesignR.string.chat_proj_head_goal, listOf(UiText.Res(DesignR.string.chat_phase_active))),
            projectionHead(goal, emptyList()),
        )
        assertEquals(
            UiText.Res(DesignR.string.chat_proj_head_todos, listOf(0, 1)),
            projectionHead(null, listOf(ProjectedTodo("a", ProjectedTodo.STATUS_PENDING))),
        )
    }

    @Test fun blockedGoalUsesBlockedPhaseLabel() {
        val goal = ProjectedGoal(objective = "x", phase = ProjectedGoal.PHASE_BLOCKED, revision = 1, blockedReason = "等权限")
        assertEquals(UiText.Res(DesignR.string.chat_phase_blocked), goalPhaseLabel(goal))
    }
}
