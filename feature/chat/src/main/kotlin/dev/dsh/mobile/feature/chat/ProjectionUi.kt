package dev.dsh.mobile.feature.chat

import dev.dsh.mobile.core.protocol.CompactionStats
import dev.dsh.mobile.core.protocol.CompactResult
import dev.dsh.mobile.core.protocol.ContextUsage
import dev.dsh.mobile.core.protocol.ProjectedGoal
import dev.dsh.mobile.core.protocol.ProjectedTodo
import dev.dsh.mobile.design.R as DesignR
import dev.dsh.mobile.design.UiText
import kotlin.math.roundToInt

// 投影 → UI 文案的纯函数层：Composable 里只摆位置，所有取舍都在这里，能被单测覆盖。
// T9.19：文案一律返回 [UiText.Res]，由 UI 侧 .text() 解析；这里不出现面向用户的字面量。

/** 本页最多保留几条系统回执（换页即丢，别让它无限长）。 */
internal const val SYSTEM_NOTE_LIMIT = 20

/**
 * /compact 的结果回执。
 *
 * 压了：给条数 / 省下的 token / 前后对比 / 摘要长度，全部取 [CompactResult] 的真实字段。
 * 没压：原样透出引擎的理由（「还没超预算，不需要压缩」「当前引擎不支持」），空理由补「无需压缩」。
 */
internal fun compactNote(result: CompactResult): UiText {
    if (!result.compacted) {
        return if (result.message.isBlank()) UiText.Res(DesignR.string.chat_compact_nothing) else UiText.Raw(result.message)
    }
    val saved = (result.beforeTokens - result.afterTokens).coerceAtLeast(0)
    val args = listOf(
        result.droppedMessages,
        compact(saved.toLong()),
        compact(result.beforeTokens.toLong()),
        compact(result.afterTokens.toLong()),
    )
    return if (result.summaryChars > 0) {
        UiText.Res(DesignR.string.chat_compact_note_summary, args + compact(result.summaryChars.toLong()))
    } else {
        UiText.Res(DesignR.string.chat_compact_note, args)
    }
}

/** 顶栏压缩徽标：没压过返回 null，调用方整块不渲染。 */
internal fun compactionBadge(stats: CompactionStats): UiText? =
    if (stats.count <= 0) null
    else UiText.Res(DesignR.string.chat_compact_badge, listOf(stats.count, compact(stats.savedTokens.toLong())))

/** 用量弹窗里的压缩明细（次数 / 最近一次节省 / 摘要长度）。 */
internal fun compactionDetail(stats: CompactionStats): UiText =
    UiText.Res(
        DesignR.string.chat_compact_detail,
        listOf(stats.count, compact(stats.savedTokens.toLong()), compact(stats.summaryChars.toLong())),
    )

/**
 * 顶栏上下文占比：优先投影口径（装配用的字符预算），投影没数据返回 null，
 * 调用方退回 [ChatUiState.contextPercent]（usage token 口径），不改变原有行为。
 */
internal fun usagePercent(usage: ContextUsage): Int? {
    if (usage.budgetChars <= 0) return null
    return (usage.ratio * 100f).roundToInt().coerceIn(0, 100)
}

/**
 * 顶栏那行「上下文 X% · used / budget · 速度」。
 *
 * 投影有预算时用 usedChars/budgetChars（真的装配口径），否则原样返回 [ChatUiState.tokenText]，
 * tok/s 与流式文案一字不改。
 */
internal fun usageLine(state: ChatUiState, usage: ContextUsage): UiText {
    val pct = usagePercent(usage) ?: return UiText.Raw(state.tokenText)
    val speed = if (state.tokensPerSecond > 0.05) " · " + speedText(state.tokensPerSecond) else ""
    val used = if (usage.budgetChars > 0) compact(usage.usedChars.toLong()) else compact(state.contextUsed)
    return UiText.Res(
        DesignR.string.chat_usage_line,
        listOf(pct, used, compact(usage.budgetChars.toLong()), speed),
    )
}

/** 一条待办归属的分组（渲染顺序即列表顺序）。 */
internal data class TodoGroup(val title: UiText, val status: String, val todos: List<ProjectedTodo>)

/**
 * 待办分组：进行中 → 待办 → 已完成；空组不产出，所以全空时调用方拿到空列表、整块不渲染。
 * 组内保持模型给出的原始顺序（模型自己排的优先级比 UI 猜的准）。
 */
internal fun todoGroups(todos: List<ProjectedTodo>): List<TodoGroup> =
    listOf(
        TodoGroup(UiText.Res(DesignR.string.chat_todo_doing), ProjectedTodo.STATUS_DOING, emptyList()),
        TodoGroup(UiText.Res(DesignR.string.chat_todo_pending), ProjectedTodo.STATUS_PENDING, emptyList()),
        TodoGroup(UiText.Res(DesignR.string.chat_todo_done), ProjectedTodo.STATUS_DONE, emptyList()),
    ).map { group -> group.copy(todos = todos.filter { it.status == group.status }) }
        .filter { it.todos.isNotEmpty() }

/** 三态待办的行首记号：进行中 ▶ / 待办 ○ / 已完成 ✓。 */
internal fun todoBullet(status: String): String = when (status) {
    ProjectedTodo.STATUS_DOING -> "▶"
    ProjectedTodo.STATUS_DONE -> "✓"
    else -> "○"
}

/** goal.phase 的标签；未知 phase 原样显示（引擎加新状态也不用改 UI）。 */
internal fun goalPhaseLabel(goal: ProjectedGoal): UiText = when (goal.phase) {
    ProjectedGoal.PHASE_ACTIVE -> UiText.Res(DesignR.string.chat_phase_active)
    ProjectedGoal.PHASE_PAUSED -> UiText.Res(DesignR.string.chat_phase_paused)
    ProjectedGoal.PHASE_BLOCKED -> UiText.Res(DesignR.string.chat_phase_blocked)
    ProjectedGoal.PHASE_COMPLETE -> UiText.Res(DesignR.string.chat_phase_complete)
    else -> UiText.Raw(goal.phase)
}

/** 投影面板折叠时的抬头：「目标 · 进行中 · 待办 2/5」，只有目标时只显目标段。 */
internal fun projectionHead(goal: ProjectedGoal?, todos: List<ProjectedTodo>): UiText {
    val done = todos.count { it.status == ProjectedTodo.STATUS_DONE }
    val phase = goal?.let { goalPhaseLabel(it) }
    return when {
        phase != null && todos.isNotEmpty() ->
            UiText.Res(DesignR.string.chat_proj_head_both, listOf(phase, done, todos.size))
        phase != null -> UiText.Res(DesignR.string.chat_proj_head_goal, listOf(phase))
        todos.isNotEmpty() -> UiText.Res(DesignR.string.chat_proj_head_todos, listOf(done, todos.size))
        else -> UiText.Raw("")
    }
}
