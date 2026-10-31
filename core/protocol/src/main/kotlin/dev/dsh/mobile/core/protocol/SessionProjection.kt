package dev.dsh.mobile.core.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

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

/**
 * 投影仓库：todo_write / goal / 每次压缩 / 每次上下文装配都会更新这里。
 * 每个会话一条 StateFlow，UI 单独 collect，不会因为别的会话变动而重组。
 */
class SessionProjections {
    private val flows = ConcurrentHashMap<String, MutableStateFlow<SessionProjection>>()

    fun observe(sessionId: String): StateFlow<SessionProjection> = flow(sessionId).asStateFlow()

    fun snapshot(sessionId: String): SessionProjection = flow(sessionId).value

    fun writeTodos(sessionId: String, todos: List<ProjectedTodo>) =
        update(sessionId) { it.copy(todos = todos) }

    fun writeGoal(sessionId: String, goal: ProjectedGoal?) = update(sessionId) { it.copy(goal = goal) }

    fun noteUsage(sessionId: String, usedChars: Int, fullChars: Int, budgetChars: Int) =
        update(sessionId) { it.copy(usage = ContextUsage(usedChars, fullChars, budgetChars)) }

    fun noteSummaryChars(sessionId: String, chars: Int) =
        update(sessionId) { it.copy(summaryChars = chars) }

    /** 记一次压缩：次数累加，前后 token 与摘要长度取本次结果。 */
    fun noteCompaction(sessionId: String, beforeTokens: Int, afterTokens: Int, summaryChars: Int) =
        update(sessionId) {
            val c = it.compaction
            it.copy(
                compaction = c.copy(
                    count = c.count + 1,
                    beforeTokens = beforeTokens,
                    afterTokens = afterTokens,
                    summaryChars = summaryChars,
                ),
                summaryChars = summaryChars,
            )
        }

    fun clear(sessionId: String) {
        flows.remove(sessionId)
    }

    private fun flow(sessionId: String): MutableStateFlow<SessionProjection> =
        flows.computeIfAbsent(sessionId) { MutableStateFlow(SessionProjection(sessionId = sessionId)) }

    /** 单线程 UI 与协程执行池会同时写：CAS 重试，别让 todo 与压缩统计互相覆盖。 */
    private fun update(sessionId: String, block: (SessionProjection) -> SessionProjection) {
        val flow = flow(sessionId)
        while (true) {
            val cur = flow.value
            val next = block(cur).copy(sessionId = sessionId)
            if (flow.compareAndSet(cur, next)) return
        }
    }
}

/**
 * 解析 todo_write 参数：{"todos":[{"content":"…","status":"pending"}]}。
 * 形状不对返回 null——调用方把错误文案回灌模型，让它自己改参数重试。
 */
internal fun parseTodos(args: JsonObject): List<ProjectedTodo>? {
    val raw = args["todos"] as? JsonArray ?: return null
    val out = ArrayList<ProjectedTodo>(raw.size)
    for (el in raw) {
        val o = el as? JsonObject ?: return null
        val content = (o["content"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (content.isEmpty()) return null
        val status = (o["status"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val status2 = if (status.isEmpty()) ProjectedTodo.STATUS_PENDING else status
        if (status2 !in ProjectedTodo.STATUSES) return null
        out += ProjectedTodo(content, status2)
    }
    return out
}
