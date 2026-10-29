package dev.dsh.mobile.core.data.repo

import dev.dsh.mobile.core.protocol.DshEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * 轮次状态机：只负责 [TurnState] 的代际正确性（turnId 关联 + 结算幂等），不含 DB 落库。
 *
 * [ChatRepository.handleEvent] / [ChatRepository.sendMessage] / [ChatRepository.cancel]
 * 全部经这里写轮次状态，保证「迟到事件不污染新轮」「同一轮只结算一次」只有这一份实现，
 * 可以在纯 JVM 单测里直接驱动（不需要 Room / Robolectric）。
 */
internal class TurnLedger {
    private val _turns = MutableStateFlow<Map<String, TurnState>>(emptyMap())
    val turns: StateFlow<Map<String, TurnState>> = _turns.asStateFlow()

    /** 已结算轮次 (sessionId|turnId)，线程安全，容量有上界。 */
    private val finalized = ConcurrentHashMap.newKeySet<String>()

    /** 已封口的会话（clear/delete 后）：拒绝一切迟到写回，防 clear 后被 in-flight 结算重新落库（N2）。 */
    private val closed = ConcurrentHashMap.newKeySet<String>()

    fun current(sessionId: String): TurnState? = _turns.value[sessionId]

    /** 起轮建档（sendMessage 用，turnId 由上层生成）。起轮即撤销 clear/delete 的封口。 */
    fun seed(sessionId: String, turnId: String) {
        closed.remove(sessionId)
        _turns.update { it + (sessionId to TurnState(running = true, turnId = turnId)) }
    }

    /**
     * 一条流式事件写入。[turnId] 是事件携带的代际 id（ACP / 旧生产者传 null）：
     * - 当前无轮 + 带 turnId：按该 turnId 建档（远程发起的轮次）。
     * - 当前无轮 + null：按随机 turnId 建档（旧行为，兼容）。
     * - 已结算（!running）：迟到事件直接丢，不复活幽灵轮（H3）。
     * - 带 turnId 但与当前轮不符：上一轮的迟到事件，丢（H1/H2 的污染源）。
     */
    fun update(sessionId: String, turnId: String?, block: (TurnState) -> TurnState) {
        if (sessionId in closed) return // clear/delete 已封口：迟到事件一律丢，不复活幽灵轮
        _turns.update { map -> mergeTurn(map, sessionId, turnId, block) }
    }

    /** 只改已存在的轮次（权限答复用）；无轮则不动，绝不凭空建档。 */
    fun updateExisting(sessionId: String, block: (TurnState) -> TurnState) {
        _turns.update { map -> map[sessionId]?.let { map + (sessionId to block(it)) } ?: map }
    }

    /**
     * 结算前的代际校验。返回「能否结算当前轮」。
     * [expectTurnId]==null 表示调用方没点名（旧 cancel / 未来生产者），只能结算「在跑」的轮，
     * 已结算的空轮直接拒绝——这是兜底，不是结算权威（见 [ChatRepository.cancel] 直接传捕获值）。
     */
    fun canFinalize(sessionId: String, expectTurnId: String?): Boolean {
        val state = _turns.value[sessionId] ?: return false
        return if (expectTurnId != null) state.turnId == expectTurnId else state.running
    }

    fun markFinalized(sessionId: String, turnId: String): Boolean {
        val key = sessionId + "|" + turnId
        if (!finalized.add(key)) return false
        if (finalized.size > 200) {
            val it = finalized.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        return true
    }

    /** 结算后重置为全新空轮（turnId 换新、running=false）。 */
    fun seal(sessionId: String) {
        _turns.update { it + (sessionId to TurnState()) }
    }

    /**
     * 成员结束：校验代际后找到该成员 part 并标记已持久化。
     * [turnId] 与当前轮不符（上一轮成员的迟到 End）返回 null，绝不误结算新轮成员。
     */
    fun finishPart(sessionId: String, authorId: String, turnId: String?): AuthorPart? {
        val state = _turns.value[sessionId] ?: return null
        if (turnId != null && state.turnId != turnId) return null
        val part = state.parts.firstOrNull { it.id == authorId } ?: return null
        _turns.update { map ->
            val st = map[sessionId] ?: return@update map
            map + (sessionId to st.finishPart(part))
        }
        return part
    }

    fun remove(sessionId: String) {
        _turns.update { it - sessionId }
    }

    /**
     * 封口（clearMessages / delete 用）：标记该会话不再接受任何写回，并清掉在跑轮次。
     * 迟到的 chunk/End（[update]）与结算写库（persistAssistant）都会看到 isClosed 而丢弃。
     */
    fun close(sessionId: String) {
        closed.add(sessionId)
        _turns.update { it - sessionId }
    }

    fun isClosed(sessionId: String): Boolean = sessionId in closed
}

/** 整轮 TurnEnd 的三种去向：结算整轮 / 只结算成员 / 忽略（无 turnId 的整轮 End 不给结算权）。 */
internal sealed interface TurnEndAction {
    data class FinishPart(val authorId: String) : TurnEndAction
    data class FinalizeWhole(val turnId: String) : TurnEndAction
    data object Ignore : TurnEndAction
}

/** ACP 本就不发 TurnEnd；带 authorId 的是成员结束；无 turnId 的整轮 End 视为未知事件，不结算。 */
internal fun DshEvent.TurnEnd.action(): TurnEndAction {
    // 先落局部变量再判空：authorId/turnId 是另一模块的 public API 属性，跨模块不能 smart cast。
    val author = authorId
    val tid = turnId
    return when {
        author != null -> TurnEndAction.FinishPart(author)
        tid != null -> TurnEndAction.FinalizeWhole(tid)
        else -> TurnEndAction.Ignore
    }
}

/** 纯函数：把一条事件按代际规则合入轮次 map（[TurnLedger.update] 的核心）。 */
internal fun mergeTurn(
    map: Map<String, TurnState>,
    sessionId: String,
    turnId: String?,
    block: (TurnState) -> TurnState,
): Map<String, TurnState> {
    val cur = map[sessionId]
    return when {
        cur == null -> {
            val seed = if (turnId == null) TurnState(running = true) else TurnState(running = true, turnId = turnId)
            map + (sessionId to block(seed))
        }
        !cur.running -> map
        turnId != null && cur.turnId != turnId -> map
        else -> map + (sessionId to block(cur))
    }
}
