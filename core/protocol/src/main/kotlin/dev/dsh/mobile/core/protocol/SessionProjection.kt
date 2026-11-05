package dev.dsh.mobile.core.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

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
