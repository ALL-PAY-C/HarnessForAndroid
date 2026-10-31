package dev.dsh.mobile.feature.chat

import dev.dsh.mobile.core.protocol.CompactionStats
import dev.dsh.mobile.core.protocol.CompactResult
import dev.dsh.mobile.core.protocol.ContextUsage
import dev.dsh.mobile.core.protocol.ProjectedGoal
import dev.dsh.mobile.core.protocol.ProjectedTodo
import kotlin.math.roundToInt

// 投影 → UI 文案的纯函数层：Composable 里只摆位置，所有取舍都在这里，能被单测覆盖。

/** 本页最多保留几条系统回执（换页即丢，别让它无限长）。 */
internal const val SYSTEM_NOTE_LIMIT = 20

/**
 * /compact 的结果回执。
 *
 * 压了：给条数 / 省下的 token / 前后对比 / 摘要长度，全部取 [CompactResult] 的真实字段。
 * 没压：原样透出引擎的理由（「还没超预算，不需要压缩」「当前引擎不支持」），空理由补「无需压缩」。
 */
internal fun compactNote(result: CompactResult): String {
    if (!result.compacted) return result.message.ifBlank { "无需压缩" }
    val saved = (result.beforeTokens - result.afterTokens).coerceAtLeast(0)
    return buildString {
        append("已压缩 ").append(result.droppedMessages).append(" 条历史")
        append(" · 省 ").append(compact(saved.toLong())).append(" tokens")
        append("（").append(compact(result.beforeTokens.toLong()))
        append(" → ").append(compact(result.afterTokens.toLong())).append("）")
        if (result.summaryChars > 0) {
            append(" · 摘要 ").append(compact(result.summaryChars.toLong())).append(" 字")
        }
    }
}

/** 顶栏压缩徽标：没压过返回 null，调用方整块不渲染。 */
internal fun compactionBadge(stats: CompactionStats): String? =
    if (stats.count <= 0) null
    else "已压缩 " + stats.count + " 次 · 省 " + compact(stats.savedTokens.toLong())

/** 用量弹窗里的压缩明细（次数 / 最近一次节省 / 摘要长度）。 */
internal fun compactionDetail(stats: CompactionStats): String =
    stats.count.toString() + " 次 · 最近省 " + compact(stats.savedTokens.toLong()) +
        " tokens · 摘要 " + compact(stats.summaryChars.toLong()) + " 字"

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
internal fun usageLine(state: ChatUiState, usage: ContextUsage): String {
    val pct = usagePercent(usage) ?: return state.tokenText
    val speed = if (state.tokensPerSecond > 0.05) " · " + speedText(state.tokensPerSecond) else ""
    val used = if (usage.budgetChars > 0) compact(usage.usedChars.toLong()) else compact(state.contextUsed)
    return "上下文 " + pct + "% · " + used + " / " + compact(usage.budgetChars.toLong()) + speed
}

/** 一条待办归属的分组（渲染顺序即列表顺序）。 */
internal data class TodoGroup(val title: String, val status: String, val todos: List<ProjectedTodo>)

/**
 * 待办分组：进行中 → 待办 → 已完成；空组不产出，所以全空时调用方拿到空列表、整块不渲染。
 * 组内保持模型给出的原始顺序（模型自己排的优先级比 UI 猜的准）。
 */
internal fun todoGroups(todos: List<ProjectedTodo>): List<TodoGroup> =
    listOf(
        TodoGroup("进行中", ProjectedTodo.STATUS_DOING, emptyList()),
        TodoGroup("待办", ProjectedTodo.STATUS_PENDING, emptyList()),
        TodoGroup("已完成", ProjectedTodo.STATUS_DONE, emptyList()),
    ).map { group -> group.copy(todos = todos.filter { it.status == group.status }) }
        .filter { it.todos.isNotEmpty() }

/** 三态待办的行首记号：进行中 ▶ / 待办 ○ / 已完成 ✓。 */
internal fun todoBullet(status: String): String = when (status) {
    ProjectedTodo.STATUS_DOING -> "▶"
    ProjectedTodo.STATUS_DONE -> "✓"
    else -> "○"
}

/** goal.phase 的中文标签；未知 phase 原样显示（引擎加新状态也不用改 UI）。 */
internal fun goalPhaseLabel(goal: ProjectedGoal): String = when (goal.phase) {
    ProjectedGoal.PHASE_ACTIVE -> "进行中"
    ProjectedGoal.PHASE_PAUSED -> "已暂停"
    ProjectedGoal.PHASE_BLOCKED -> "受阻"
    ProjectedGoal.PHASE_COMPLETE -> "已完成"
    else -> goal.phase
}

/** 投影面板折叠时的抬头：「目标 · 进行中 · 待办 2/5」，只有目标时只显目标段。 */
internal fun projectionHead(goal: ProjectedGoal?, todos: List<ProjectedTodo>): String = buildString {
    if (goal != null) append("目标 · ").append(goalPhaseLabel(goal))
    if (todos.isNotEmpty()) {
        if (goal != null) append(" · ")
        append("待办 ").append(todos.count { it.status == ProjectedTodo.STATUS_DONE })
        append("/").append(todos.size)
    }
}
