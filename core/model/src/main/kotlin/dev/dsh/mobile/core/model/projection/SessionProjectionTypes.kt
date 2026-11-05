package dev.dsh.mobile.core.model.projection

import kotlinx.coroutines.flow.StateFlow

/** 模型侧待办的一条（与桌面 dsh-tool-todo 的 status 词汇一致）。 */
data class ProjectedTodo(val content: String, val status: String = STATUS_PENDING) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_DOING = "in_progress"
        const val STATUS_DONE = "completed"
        val STATUSES = setOf(STATUS_PENDING, STATUS_DOING, STATUS_DONE)
    }
}

/** 模型侧目标（goal 工具的只读投影）。 */
data class ProjectedGoal(
    val objective: String,
    val phase: String = PHASE_ACTIVE,
    val revision: Int = 1,
    val maxRounds: Int = 0,
    val blockedReason: String? = null,
) {
    companion object {
        const val PHASE_ACTIVE = "active"
        const val PHASE_PAUSED = "paused"
        const val PHASE_BLOCKED = "blocked"
        const val PHASE_COMPLETE = "complete"
        val PHASES = setOf(PHASE_ACTIVE, PHASE_PAUSED, PHASE_BLOCKED, PHASE_COMPLETE)
    }
}

/** 压缩统计：次数 / 前后 token 粗估 / 摘要长度。 */
data class CompactionStats(
    val count: Int = 0,
    val beforeTokens: Int = 0,
    val afterTokens: Int = 0,
    val summaryChars: Int = 0,
) {
    val savedTokens: Int get() = (beforeTokens - afterTokens).coerceAtLeast(0)
}

/** 上下文用量：装配用的字符预算口径（与 AgentThreads 完全一致）。 */
data class ContextUsage(val usedChars: Int = 0, val fullChars: Int = 0, val budgetChars: Int = 0) {
    /** 0f..1f；预算未知时为 0。 */
    val ratio: Float get() = if (budgetChars <= 0) 0f else usedChars.toFloat() / budgetChars
    val overBudget: Boolean get() = budgetChars > 0 && usedChars > budgetChars
}

/**
 * 会话投影（只读快照）：模型侧 todo / goal、上下文用量、压缩统计。
 *
 * 这些原本只能躺在 ChatScreen 的内存 state 里（换个屏就没了），现在统一在这里，
 * UI 通过 [SessionProjections.observe] 拿 StateFlow，non-UI 侧只写不读。
 */
data class SessionProjection(
    val sessionId: String = "",
    val todos: List<ProjectedTodo> = emptyList(),
    val goal: ProjectedGoal? = null,
    val usage: ContextUsage = ContextUsage(),
    val compaction: CompactionStats = CompactionStats(),
    /** 当前生效的上下文摘要长度（字符）。 */
    val summaryChars: Int = 0,
) {
    val hasTodos: Boolean get() = todos.isNotEmpty()
    val doneCount: Int get() = todos.count { it.status == ProjectedTodo.STATUS_DONE }
    val runningTodo: ProjectedTodo? get() = todos.firstOrNull { it.status == ProjectedTodo.STATUS_DOING }
}
