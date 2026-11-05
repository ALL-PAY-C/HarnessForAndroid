package dev.dsh.mobile.core.data.repo

import dev.dsh.mobile.core.platform.AppLog
import dev.dsh.mobile.core.platform.DshResult
import dev.dsh.mobile.core.platform.runCatchingDsh
import dev.dsh.mobile.core.data.db.DshDatabase
import dev.dsh.mobile.core.data.db.MESSAGE_TEXT_READ_CAP
import dev.dsh.mobile.core.data.db.MessageEntity
import dev.dsh.mobile.core.data.db.SessionEntity
import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.core.model.Participant
import dev.dsh.mobile.core.model.PermissionRequest
import dev.dsh.mobile.core.model.Session
import dev.dsh.mobile.core.model.SessionKind
import dev.dsh.mobile.core.model.SessionStatus
import dev.dsh.mobile.core.model.TokenUsage
import dev.dsh.mobile.core.model.ToolCall
import dev.dsh.mobile.core.protocol.AcpTransport
import dev.dsh.mobile.core.protocol.CompactResult
import dev.dsh.mobile.core.protocol.Compactor
import dev.dsh.mobile.core.protocol.DshEvent
import dev.dsh.mobile.core.protocol.DshTransport
import dev.dsh.mobile.core.protocol.LocalEngineTransport
import dev.dsh.mobile.core.protocol.SessionProjection
import dev.dsh.mobile.core.protocol.SessionProjections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

// ---------------------------------------------------------------- 事件处理与落库

internal suspend fun ChatRepository.handleEvent(e0: DshEvent, isRemote: Boolean, transport: DshTransport) {
    val e = translateToLocal(e0, isRemote) ?: return
    when (e) {
        // 三路事件各走各的归并（T2）：正文/思考/工具互不兜底，见 TurnParts.kt。
        is DshEvent.MessageChunk -> ledger.update(e.sessionId, e.turnId) { st ->
            st.applyText(e.authorId, e.authorName, e.text)
        }
        is DshEvent.ThoughtChunk -> ledger.update(e.sessionId, e.turnId) { st ->
            st.applyThought(e.authorId, e.authorName, e.text)
        }
        is DshEvent.ToolCallEvent -> ledger.update(e.sessionId, e.turnId) { st ->
            st.applyToolCall(e.authorId, e.authorName, e.call)
        }
        is DshEvent.Plan -> ledger.update(e.sessionId, e.turnId) { it.copy(plan = e.steps) }
        is DshEvent.Permission -> {
            if (ledger.current(e.sessionId)?.running != true) return
            ledger.updateExisting(e.sessionId) { st -> st.requestPermission(e.request) }
            setStatus(e.sessionId, SessionStatus.WaitingPermission)
        }
        is DshEvent.PermissionResolved -> {
            if (ledger.current(e.sessionId)?.permissions?.any { it.requestId == e.requestId } != true) return
            ledger.updateExisting(e.sessionId) { st -> st.resolvePermission(e.requestId) }
            if (ledger.current(e.sessionId)?.running == true && ledger.current(e.sessionId)?.permissions.isNullOrEmpty()) setStatus(e.sessionId, SessionStatus.Running)
        }
        is DshEvent.Commands -> _commands.value = e.commands
        is DshEvent.Usage -> {
            val session = db.sessionDao().byId(e.sessionId) ?: return
            val previous = TokenUsage(input = session.inputTokens, output = session.outputTokens, cached = session.cachedTokens, lastPrompt = session.lastPrompt)
            val delta = usageDelta(previous, e)
            db.sessionDao().upsert(session.copy(
                inputTokens = previous.input + delta.input,
                outputTokens = previous.output + delta.output,
                cachedTokens = previous.cached + delta.cached,
                lastPrompt = delta.lastPrompt,
            ))
            ledger.update(e.sessionId, e.turnId) { it.recordUsage(delta, e.authorId) }
        }
        is DshEvent.TurnEnd -> when (val action = e.action()) {
            is TurnEndAction.FinishPart -> finishPart(e.sessionId, action.authorId, e.turnId)
            is TurnEndAction.FinalizeWhole -> {
                finalizeTurn(e.sessionId, expectTurnId = action.turnId)
                // P5 barrier：collector 消费完合成终态 marker 后，先解封 quarantine 再 ACK。
                // N-1：ACK 路由到「发出该 marker 的捕获 transport」，绝不打到 live _transport.value
                //（live getter 可能已切到新 transport，旧 ACK 会清新会话的阻塞标记）。
                barriers.markerConsumed(e.sessionId, action.turnId)
                transport.ackTurn(action.turnId)
            }
            TurnEndAction.Ignore -> Unit
        }
        else -> Unit
    }
}

/** M2 反向翻译：远端 id → 本地 id；无映射且 ACP 的孤儿事件直接丢弃（绝不回退本地 id）。 */
internal fun ChatRepository.translateToLocal(e: DshEvent, isRemote: Boolean): DshEvent? {
    if (e is DshEvent.Raw) return e
    val sid = e.sessionIdOrNull() ?: return e
    val localId = sessionIds.local(sid)
    // 有映射（含同值映射）→ 翻译；无映射 → 分模式判定。
    if (localId != null) return e.withLocalSessionId(localId)
    // 用捕获的 isRemote（非 live getter）：旧连接在飞事件不会因 transport 已切而误判（G2）。
    // ACP 孤儿远端 id：不查库、不回退本地 id，直接丢弃防幽灵会话（B4：无 db 挂起点，无需挂起后复检代际）。
    if (isRemote) {
        AppLog.w("M2 drop orphan event: session=$sid type=${e::class.simpleName}")
        return null
    }
    // LocalEngine：事件携带的就是本地 id，原样保留。
    return e
}

/** DshEvent 接口没有统一 sessionId（Raw 无），逐子类 smart-cast 取；无则 null。 */
internal fun DshEvent.sessionIdOrNull(): String? = when (this) {
    is DshEvent.MessageChunk -> sessionId
    is DshEvent.ThoughtChunk -> sessionId
    is DshEvent.ToolCallEvent -> sessionId
    is DshEvent.Plan -> sessionId
    is DshEvent.Usage -> sessionId
    is DshEvent.Commands -> sessionId
    is DshEvent.ConfigOptions -> sessionId
    is DshEvent.Permission -> sessionId
    is DshEvent.PermissionResolved -> sessionId
    is DshEvent.TurnEnd -> sessionId
    is DshEvent.Compacted -> sessionId
    is DshEvent.Raw -> null
}

internal fun DshEvent.withLocalSessionId(localId: String): DshEvent = when (this) {
    is DshEvent.MessageChunk -> copy(sessionId = localId)
    is DshEvent.ThoughtChunk -> copy(sessionId = localId)
    is DshEvent.ToolCallEvent -> copy(sessionId = localId)
    is DshEvent.Plan -> copy(sessionId = localId)
    is DshEvent.Usage -> copy(sessionId = localId)
    is DshEvent.Commands -> copy(sessionId = localId)
    is DshEvent.ConfigOptions -> copy(sessionId = localId)
    is DshEvent.Permission -> copy(sessionId = localId, request = request.copy(sessionId = localId))
    is DshEvent.PermissionResolved -> copy(sessionId = localId)
    is DshEvent.TurnEnd -> copy(sessionId = localId)
    is DshEvent.Compacted -> copy(sessionId = localId)
    is DshEvent.Raw -> this
}

/** A group member finished: persist its bubble right away so history stays ordered. */
internal suspend fun ChatRepository.finishPart(sessionId: String, authorId: String, turnId: String? = null) {
    // 代际校验在 ledger 里做：上一轮成员的迟到 End 不会把新轮同 id 成员的气泡提前落库。
    val part = ledger.finishPart(sessionId, authorId, turnId) ?: return
    // 群聊里「（无补充）」是成员之间的内部信号，不是要说给用户看的话：
    // 以前它照常落库，聊天里就多出一条莫名其妙的气泡。这里直接丢掉。
    if (!isNoAddMarker(part.text)) {
        persistAssistant(
            sessionId, part.text.trim(), part.thought.trim(), part.tools, authorId, part.name,
            // 幂等键带上 seq：同一成员一轮里的第二次发言要能落成第二条气泡。
            turnId = ledger.current(sessionId)?.turnId?.let { it + "#" + part.seq },
            inputTokens = part.inputTokens,
            outputTokens = part.outputTokens,
        )
    }
}

/** 成员表示「没补充」：纯内部信号，不落库、不显示。 */
internal fun ChatRepository.isNoAddMarker(text: String): Boolean {
    val t = text.trim()
    return t.isEmpty() || t == "（无补充）" || t == "(无补充)"
}


internal suspend fun ChatRepository.persistAssistant(
    sessionId: String,
    text: String,
    thought: String,
    tools: List<ToolCall>,
    authorId: String? = null,
    authorName: String? = null,
    durationMs: Long = 0L,
    turnId: String? = null,
    inputTokens: Long = 0L,
    outputTokens: Long = 0L,
) {
    if (text.isEmpty() && tools.isEmpty()) return
    val thought = cappedThoughtForPersist(thought)
    // 幂等：同一 (sessionId, turnId, authorId) 只落一条。
    // 旧的「同作者 + 同文本 + 120 秒」启发式已删：换行差异拦不住，还会误杀用户重复提问。
    val tid = turnId ?: ledger.current(sessionId)?.turnId.orEmpty()
    // N2：封口校验 + 幂等 + 落库 全在一个短临界段里，与 clearMessages/delete 的封口互斥。
    sessionCritical(sessionId) {
        if (ledger.isClosed(sessionId)) {
            AppLog.w("skip persist on closed session (turn=$tid)")
        } else if (tid.isNotBlank() && db.messageDao().countTurn(sessionId, tid, authorId) > 0) {
            AppLog.w("skip duplicate assistant message (turn=$tid author=$authorId)")
        } else {
            db.messageDao().upsert(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    role = ChatMessage.Role.ASSISTANT.name,
                    text = text,
                    durationMs = durationMs,
                    thought = thought,
                    turnId = tid,
                    toolCallsJson = toolJson.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(ToolCallDto.serializer()),
                        tools.map {
                            ToolCallDto(
                                it.id, it.title, it.kind, it.status.name, it.detail,
                                it.args, it.requestJson, it.responseJson, it.elapsedMs, it.attempts,
                            )
                        },
                    ),
                    at = System.currentTimeMillis(),
                    authorId = authorId,
                    authorName = authorName,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                ),
            )
        }
    }
}

internal suspend fun ChatRepository.finalizeTurn(
    sessionId: String,
    expectTurnId: String? = null,
    keepPartial: Boolean = false,
) {
    val state = ledger.current(sessionId) ?: return
    // 只有「当前这一轮」能被结算：上一轮的迟到事件（流式通道是异步投递的）
    // 不能把新一轮的 turns 清掉，否则新一轮还在跑、状态却被写成 Idle。
    // canFinalize 无条件按 turnId 校验（无 turnId 时只认「在跑」），不再用 running 兜底放行。
    if (!ledger.canFinalize(sessionId, expectTurnId)) return
    // 同一 turnId 只结算一次：TurnEnd 可能来自多个成员 / 多次回调，重复结算就会重复落库。
    if (!ledger.markFinalized(sessionId, state.turnId)) return
    // 群聊：state.text 是所有成员正文的累加，它没有作者，落库会变成「第 N+1 条回答」。
    // 只有单聊（没有 parts）才允许落这条汇总正文。
    // 被停下时哪怕只有思维链也要落一条，并标注「已停止」，否则那半截内容
    // 随着 TurnState 被重置一起蒸发，用户会觉得刚看到的东西丢了。
    val body = if (keepPartial && isNoAddMarker(state.text)) {
        if (state.thought.isBlank() && state.tools.isEmpty()) "" else PARTIAL_MARK
    } else {
        state.text.trim()
    }
    if (state.parts.isEmpty() && body.isNotEmpty()) {
        persistAssistant(
            sessionId, body, state.thought.trim(), state.tools,
            durationMs = System.currentTimeMillis() - state.startedAt,
            turnId = state.turnId,
            inputTokens = state.inputTokens,
            outputTokens = state.outputTokens,
        )
    }
    // 兜底路径也要过滤「（无补充）」：成员可能在整轮结束时才被结算，
    // 它那句内部信号会因为没走 finishPart 而漏成一条气泡。
    state.parts.filter { it.key !in state.persisted && (keepPartial || !isNoAddMarker(it.text)) }.forEach { part ->
        val partText = if (keepPartial && isNoAddMarker(part.text)) PARTIAL_MARK else part.text.trim()
        persistAssistant(
            sessionId, partText, part.thought.trim(), part.tools, part.id, part.name,
            durationMs = System.currentTimeMillis() - state.startedAt,
            turnId = state.turnId + "#" + part.seq,
            inputTokens = part.inputTokens,
            outputTokens = part.outputTokens,
        )
    }
    // 零正文兜底：正文/parts/工具/思维链全空的一轮以前什么都不落 —— 转圈结束后会话里没有任何痕迹。
    if (state.parts.isEmpty() && body.isEmpty() && state.tools.isEmpty() && state.thought.isBlank()) {
        persistAssistant(
            sessionId, EMPTY_TURN_MARK, "", emptyList(),
            durationMs = System.currentTimeMillis() - state.startedAt,
            turnId = state.turnId,
        )
    }
    ledger.seal(sessionId)
    db.sessionDao().byId(sessionId)?.let {
        db.sessionDao().upsert(it.copy(updatedAt = System.currentTimeMillis()))
    }
    setStatus(sessionId, SessionStatus.Idle)
}

/**
 * 把一次「连 USER 都没来得及落库」的失败落成一条 assistant 气泡。
 * 前置条件阻塞（运行时未连接 / 上一轮收尾 / 补建失败）以前是纯静默：
 * 用户只看到转圈和一条容易被忽略的横幅，重开会话更是查无此事。
 */
internal suspend fun ChatRepository.persistFailureBubble(sessionId: String, e: Throwable, turnId: String? = null) {
    val reason = (e.message ?: e::class.simpleName ?: "未知错误").trim().ifEmpty { "未知错误" }
    runCatchingDsh {
        persistAssistant(
            sessionId,
            "（发送失败 · $reason）",
            "",
            emptyList(),
            turnId = turnId ?: "failed-" + UUID.randomUUID(),
        )
    }
}

internal fun ChatRepository.requireTransport(): DshTransport =
    _transport.value ?: throw IllegalStateException("运行时未连接")
