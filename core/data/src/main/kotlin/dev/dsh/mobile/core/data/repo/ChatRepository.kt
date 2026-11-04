package dev.dsh.mobile.core.data.repo

import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.common.DshResult
import dev.dsh.mobile.core.common.runCatchingDsh
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
import dev.dsh.mobile.core.protocol.context.WarmRefreshReport
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

/**
 * Single source of truth for conversations. Persists everything through Room so a
 * cold start renders instantly from cache, then reconciles with the runtime.
 */
class ChatRepository(
    internal val db: DshDatabase,
    internal val scope: CoroutineScope,
    /**
     * 会话投影（待办 / 目标 / 上下文用量 / 压缩统计）。
     *
     * 归属权在 transport（它才是写入方），仓库只是只读出口，让 UI 不必知道 transport 长什么样。
     * 默认空投影：只做持久化、不接模型的场景（部分单测）也能构造。
     */
    internal val projections: SessionProjections = SessionProjections(),
) {
    internal val _transport = MutableStateFlow<DshTransport?>(null)
    val transport: StateFlow<DshTransport?> = _transport.asStateFlow()

    internal val ledger = TurnLedger()
    val turns: StateFlow<Map<String, TurnState>> = ledger.turns

    /**
     * 每会话短临界区锁：persistAssistant 与 clearMessages/delete 共用。
     * 结算的「代际校验 + 落库」与清库互斥，杜绝「clear 后 in-flight 结算又把消息写回」的窗口（N2）。
     * 不覆盖 prompt/cancel 全程，只护住「判断 + 一次写」这个原子段。
     */
    internal val sessionLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    internal suspend fun <T> sessionCritical(sessionId: String, block: suspend () -> T): T =
        sessionLocks.getOrPut(sessionId) { Mutex() }.withLock { block() }

    /** M2：本地 id ↔ 远端 id 映射（持久源 sessions.remoteId）。 */
    internal val sessionIds = SessionIdMapper()

    internal val _commands = MutableStateFlow<List<String>>(emptyList())
    val commands: StateFlow<List<String>> = _commands.asStateFlow()

    internal var eventJob: Job? = null

    /** ensureRemoteSession 并发只建一次：per-session 锁（持锁跨 newSession 网络调用）。 */
    internal val ensureLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /**
     * 每会话当前「挂在 prompt 上的异步子任务」：cancel 用它终止被挂起的 prompt，
     * 让 sendMessage 立刻从 await 抛出（CancellationException），从而释放 sendGate，
     * 不必等远端 session/cancel 的 15s 超时。
     */
    internal val promptJobs = java.util.concurrent.ConcurrentHashMap<String, PromptJob>()

    /**
     * N-1/m00611 终态屏障：每会话「上一轮终态尚未确认」时挡住新 seed。
     * 双条件 latch（markerConsumed + promptJobFinished）避免 Set add/remove 竞态误隔离。
     * 只允许「同 turn 已消费 marker + prompt 已结束」解除；断连不自动放行（保守阻塞 + 有界 Err）。
     */
    internal val barriers = TurnBarrierRegistry()

    /**
     * 正文全文缓存（T4）：正文落库后不再改写，取回的全文按 id 复用，
     * 流每次发射（流式回复期间很密）都不重复打这条单行查询。
     * ponytail: 只留 3 条，够「展开一条、翻回上一条」；真要同时展开更多就调大这个数。
     */
    internal val fullTextCache = object : LinkedHashMap<String, String>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 3
    }

    internal suspend fun fullText(id: String): String? {
        synchronized(fullTextCache) { fullTextCache[id] }?.let { return it }
        val text = db.messageDao().textById(id) ?: return null
        synchronized(fullTextCache) { fullTextCache[id] = text }
        return text
    }

    // ================================================================ public API（薄转发）
    // T7.3：实现按职责拆到同包的 ChatRepo*.kt（ChatRepository 的 internal 扩展函数）。
    // 本类只留状态字段 + 构造 + 转发；每个签名与原实现逐字一致，调用点零改动。

    suspend fun attach(t: DshTransport?, endpointId: String? = null) = attachImpl(t, endpointId)

    fun observeSessions(): Flow<List<Session>> = observeSessionsImpl()

    fun observeMessages(sessionId: String): Flow<List<ChatMessage>> = observeMessagesImpl(sessionId)

    suspend fun session(id: String): Session? = sessionImpl(id)

    suspend fun messageText(messageId: String): String? = messageTextImpl(messageId)

    suspend fun searchMessages(query: String, limit: Int = 60): List<ChatSearchHit> =
        searchMessagesImpl(query, limit)

    suspend fun searchSessions(query: String, limit: Int = 30): List<SessionEntity> =
        searchSessionsImpl(query, limit)

    suspend fun resetStaleStatuses() = resetStaleStatusesImpl()

    suspend fun refreshSessions(cwd: String?): DshResult<Int> = refreshSessionsImpl(cwd)

    suspend fun createSession(cwd: String, agentId: String? = null): DshResult<Session> =
        createSessionImpl(cwd, agentId)

    suspend fun createGroup(
        cwd: String,
        title: String,
        participants: List<Participant>,
    ): DshResult<Session> = createGroupImpl(cwd, title, participants)

    suspend fun setAgent(id: String, agentId: String?) = setAgentImpl(id, agentId)

    suspend fun setParticipants(id: String, participants: List<Participant>) =
        setParticipantsImpl(id, participants)

    suspend fun sendMessage(
        sessionId: String,
        text: String,
        images: List<String> = emptyList(),
        messageId: String? = null,
    ): DshResult<Unit> = sendMessageImpl(sessionId, text, images, messageId)

    suspend fun cancel(sessionId: String): DshResult<Unit> = cancelImpl(sessionId)

    suspend fun interject(sessionId: String, text: String): DshResult<Unit> = interjectImpl(sessionId, text)

    suspend fun answerPermission(
        sessionId: String,
        requestId: String,
        optionId: String?,
        ownerAgentId: String? = null,
    ): DshResult<Unit> = answerPermissionImpl(sessionId, requestId, optionId, ownerAgentId)

    suspend fun setConfigOption(sessionId: String, id: String, value: String): DshResult<Unit> =
        setConfigOptionImpl(sessionId, id, value)

    suspend fun setPinned(id: String, pinned: Boolean) = setPinnedImpl(id, pinned)

    suspend fun setPersona(id: String, persona: String) = setPersonaImpl(id, persona)

    suspend fun setArchived(id: String, archived: Boolean) = setArchivedImpl(id, archived)

    suspend fun setCwd(id: String, cwd: String) = setCwdImpl(id, cwd)

    suspend fun setGroup(id: String, group: String) = setGroupImpl(id, group)

    suspend fun rename(id: String, title: String) = renameImpl(id, title)

    suspend fun delete(id: String) = deleteImpl(id)

    suspend fun clearMessages(id: String) = clearMessagesImpl(id)

    suspend fun compact(sessionId: String, budgetTokens: Int = 0): CompactResult =
        compactImpl(sessionId, budgetTokens)

    /** T9.8-S2 /warm：返回 null = 当前引擎没有温层能力（AcpTransport 之类）。 */
    suspend fun refreshWarm(sessionId: String): WarmRefreshReport? = refreshWarmImpl(sessionId)

    fun projection(sessionId: String): StateFlow<SessionProjection> = projectionImpl(sessionId)
}
