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
import dev.dsh.mobile.core.protocol.DshEvent
import dev.dsh.mobile.core.protocol.DshTransport
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

@Serializable
private data class ToolCallDto(
    val id: String,
    val title: String,
    val kind: String,
    val status: String,
    val detail: String,
    /** CoT 留存：命令原文 / 请求摘要 / 输出原文 / 耗时 / 重试次数。 */
    val args: String = "",
    val requestJson: String = "",
    val responseJson: String = "",
    val elapsedMs: Long = 0L,
    val attempts: Int = 0,
)

/** 每会话当前挂起的 prompt 子任务 + 它所属的轮次 id：cancel 只按 turnId 取消，绝不误杀新轮。 */
private data class PromptJob(val turnId: String, val job: kotlinx.coroutines.Deferred<String>)

@Serializable
private data class ParticipantDto(
    val id: String,
    val modelId: String,
    val name: String,
    val persona: String = "",
    val slot: Int = 0,
)

private val toolJson = Json { ignoreUnknownKeys = true }

/** Streaming output of one group member. */
data class AuthorPart(
    val id: String,
    val name: String,
    val text: String = "",
    val thought: String = "",
    val tools: List<ToolCall> = emptyList(),
    val done: Boolean = false,
    /** 同一轮里这个成员第几次发言：自动机/接力会让他反复开口，每次都要算独立气泡。 */
    val seq: Int = 0,
    /** 这个成员本段发言消耗的输入 / 输出 token（工具多轮会累加）。 */
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
) {
    /** 幂等键：一次发言一个，不能用 id（同一成员一轮会说好几次）。 */
    val key: String get() = id + "#" + seq
}

/** Live (not yet persisted) state of one running turn. */
data class TurnState(
    val text: String = "",
    val thought: String = "",
    val tools: List<ToolCall> = emptyList(),
    val running: Boolean = false,
    val permissions: List<PermissionRequest> = emptyList(),
    val plan: List<String> = emptyList(),
    /** 已经落库的发言键（[AuthorPart.key]）：避免 finishPart 与 finalizeTurn 各存一遍，气泡变三条。 */
    val persisted: Set<String> = emptySet(),
    /** Group chats: one entry per member currently speaking. */
    val parts: List<AuthorPart> = emptyList(),
    /** 这一轮开始的时间，用来算「用了 3 分 12 秒」。 */
    val startedAt: Long = System.currentTimeMillis(),
    /** 本轮唯一 id：幂等落库按 (sessionId, turnId, authorId) 判定，不再靠文本 + 时间猜。 */
    val turnId: String = UUID.randomUUID().toString(),
    /** 每个成员在本轮已经开过几次口，用来给新气泡分配 seq。 */
    val partSeq: Map<String, Int> = emptyMap(),
    /** 单聊本轮的 token 用量（工具多轮会累加）。 */
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
) {
    internal fun finishPart(part: AuthorPart): TurnState {
        val left = parts.filterNot { it.key == part.key }
        return copy(parts = left, persisted = persisted + part.key)
    }

    val permission: PermissionRequest? get() = permissions.lastOrNull()

    fun requestPermission(request: PermissionRequest): TurnState =
        if (!running) this else copy(permissions = permissions.filterNot { it.requestId == request.requestId } + request)

    fun resolvePermission(requestId: String, ownerAgentId: String? = null): TurnState =
        copy(permissions = permissions.filterNot {
            it.requestId == requestId && (ownerAgentId == null || it.ownerAgentId == null || it.ownerAgentId == ownerAgentId)
        })
}

/**
 * 抽屉搜索「聊天记录」分区的一行：会话标题、角色、命中片段、时间都由一次查询带出，
 * UI 只负责把命中的词加粗，不再二次查库。
 */
data class ChatSearchHit(
    val sessionId: String,
    val sessionTitle: String,
    val role: String,
    val snippet: String,
    val at: Long,
)

/**
 * LIKE 模式转义，配合 SQL 里的 ESCAPE '\'：先转反斜杠再转 % 和 _，顺序不能换
 * （先转 % 会把新加的反斜杠再转一遍）。不转的话用户搜「100%」会把 % 当通配符命中全库。
 */
internal fun escapeLike(raw: String): String = raw
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")

/**
 * Single source of truth for conversations. Persists everything through Room so a
 * cold start renders instantly from cache, then reconciles with the runtime.
 */
class ChatRepository(
    private val db: DshDatabase,
    private val scope: CoroutineScope,
) {
    private val _transport = MutableStateFlow<DshTransport?>(null)
    val transport: StateFlow<DshTransport?> = _transport.asStateFlow()

    private val ledger = TurnLedger()
    val turns: StateFlow<Map<String, TurnState>> = ledger.turns

    /**
     * 每会话短临界区锁：persistAssistant 与 clearMessages/delete 共用。
     * 结算的「代际校验 + 落库」与清库互斥，杜绝「clear 后 in-flight 结算又把消息写回」的窗口（N2）。
     * 不覆盖 prompt/cancel 全程，只护住「判断 + 一次写」这个原子段。
     */
    private val sessionLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private suspend fun <T> sessionCritical(sessionId: String, block: suspend () -> T): T =
        sessionLocks.getOrPut(sessionId) { Mutex() }.withLock { block() }

    /** M2：本地 id ↔ 远端 id 映射（持久源 sessions.remoteId）。 */
    private val sessionIds = SessionIdMapper()

    private val _commands = MutableStateFlow<List<String>>(emptyList())
    val commands: StateFlow<List<String>> = _commands.asStateFlow()

    private var eventJob: Job? = null

    /** ensureRemoteSession 并发只建一次：per-session 锁（持锁跨 newSession 网络调用）。 */
    private val ensureLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /**
     * 每会话当前「挂在 prompt 上的异步子任务」：cancel 用它终止被挂起的 prompt，
     * 让 sendMessage 立刻从 await 抛出（CancellationException），从而释放 sendGate，
     * 不必等远端 session/cancel 的 15s 超时。
     */
    private val promptJobs = java.util.concurrent.ConcurrentHashMap<String, PromptJob>()

    /**
     * N-1/m00611 终态屏障：每会话「上一轮终态尚未确认」时挡住新 seed。
     * 双条件 latch（markerConsumed + promptJobFinished）避免 Set add/remove 竞态误隔离。
     * 只允许「同 turn 已消费 marker + prompt 已结束」解除；断连不自动放行（保守阻塞 + 有界 Err）。
     */
    private val barriers = TurnBarrierRegistry()

    suspend fun attach(t: DshTransport?, endpointId: String? = null) {
        // 先换连接作用域（代际+1 + 换端点 + 清映射），旧 collector 在飞事件立即失效（G2）。
        sessionIds.switch(endpointId)
        val gen = sessionIds.currentGeneration
        eventJob?.cancel()
        _transport.value = t
        _commands.value = emptyList()  // M2：旧连接的命令面板不污染新连接（G3）
        if (t == null) return
        // 同步重建映射（suspend 不用 runBlocking）：collector 启动前映射已就绪，
        // 孤儿守卫不会把「映射还没加载完」的新连接事件误杀（G1）。
        reloadSessionIds()
        val isRemote = t is AcpTransport
        eventJob = scope.launch {
            t.events.collect { e ->
                if (!sessionIds.isCurrent(gen)) return@collect  // 旧连接事件（G2）
                // 单条事件出错不能带走 collector：以前这里一抛，后续所有轮的 chunk/TurnEnd 全丢，
                // 会话永久失聪 —— 界面上只留 USER 行，助手正文再也不落库。
                try {
                    handleEvent(e, isRemote, t)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (err: Throwable) {
                    // 日志本身也不能变成二次故障源（android.util.Log 在 JVM 单测里没有实现）。
                    runCatching { AppLog.w("handleEvent failed: " + err.message) }
                }
            }
        }
    }

    /** 从本地库重建 local↔remote 映射，只认「当前端点」的映射（B1：旧端点 remoteId 不注册）。 */
    private suspend fun reloadSessionIds() {
        val gen = sessionIds.currentGeneration
        db.sessionDao().all().forEach { e ->
            if (!e.remoteId.isNullOrBlank()) {
                // register 内同锁校验「端点 == 当前」且「代际 == 当前」：旧端点/旧代际行不注册（B1/G2）。
                sessionIds.register(e.id, e.remoteId, e.endpointId, gen)
            }
        }
    }

    /**
     * 正向翻译：本地 id → 远端 id。
     * 本地模式（LocalEngine）直接返回本地 id；ACP 无映射返回 null（绝不回退本地 id 发远端），
     * 由调用方决定：prompt 走 [ensureRemoteSession] 补建，cancel/interject/permission/config 跳过。
     */
    private suspend fun remoteIdOrNull(localId: String, isRemote: Boolean): String? =
        if (isRemote) sessionIds.remote(localId) else localId

    /**
     * B2：ACP 首次实际发送前，若本地会话还没有远端映射，真实补建一次并持久化。
     * per-session 锁保证并发只建一次；连接代际校验保证旧连接的补建结果不污染当前端点。
     */
    private suspend fun ensureRemoteSession(sessionId: String, gen: Long, endpointId: String?): String? {
        sessionIds.remote(sessionId)?.let { return it }
        return ensureLocks.getOrPut(sessionId) { Mutex() }.withLock {
            sessionIds.remote(sessionId)?.let { return@withLock it }
            if (!sessionIds.isCurrent(gen)) return@withLock null  // 连接已切换（G2）
            val t = _transport.value as? AcpTransport ?: return@withLock null
            val row = db.sessionDao().byId(sessionId) ?: return@withLock null
            // M2（L）：当前映射里没有、但库里已有 remoteId = 归属未知（legacy endpointId=null 或别的端点）。
            // 绝不静默覆盖旧 remoteId 重绑新远端；没有可信证据不迁移，宁可补建失败也不丢历史。
            // 用 error（IllegalStateException）把「legacy 归属」与通用补建失败区分开，send 侧据此给明确错误。
            if (!row.remoteId.isNullOrBlank()) {
                AppLog.w("M2 ensure: session=$sessionId 已有 remoteId=${row.remoteId} 但当前端点无映射，拒绝覆盖")
                error("会话已有远端归属(legacy)，拒绝覆盖补建")
            }
            val remote = t.newSession(row.cwd)
            // newSession 是挂起网络调用：返回后可能已切端点，旧结果绝不落库/注册。
            if (!sessionIds.isCurrent(gen)) return@withLock null
            val closed = sessionCritical(sessionId) {
                if (ledger.isClosed(sessionId)) true
                else {
                    // 挂起后重读最新行再补 remoteId/endpointId，避免用 newSession 前的旧快照覆盖
                    // 挂起期间其他协程（事件/新轮）更新的 status/preview 等字段（TOCTOU 收窄）。
                    // remoteId+endpointId 属「旧端点」的正确归属（remote 在旧端点建），映射靠 register 的 gen 校验拦截。
                    val fresh = db.sessionDao().byId(sessionId)
                    if (fresh != null) db.sessionDao().upsert(fresh.copy(remoteId = remote.id, endpointId = endpointId))
                    false
                }
            }
            if (closed) return@withLock null
            // 末次代际复检 + register 结果：newSession/upsert 挂起期间可能已切，register false 则本次补建作废。
            if (!sessionIds.isCurrent(gen)) return@withLock null
            if (!sessionIds.register(sessionId, remote.id, endpointId, gen)) return@withLock null
            remote.id
        }
    }

    // ------------------------------------------------------------------ queries

    fun observeSessions(): Flow<List<Session>> =
        db.sessionDao().observeAll().map { list -> list.map { it.toModel() } }

    /**
     * 会话消息流。列表查询只取 text 前缀（CursorWindow 保护，见 [MESSAGE_TEXT_READ_CAP]），
     * 命中前缀上限的行在这里按 id 逐条补读全文 —— 消费方（UI 展开 / 复制 / 导出）拿到的就是原文，
     * 不必再自己判断哪条需要补读。短消息一次额外查询都不发。
     */
    fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
        db.messageDao().observeBySession(sessionId).map { list ->
            list.map { entity ->
                val model = entity.toModel()
                if (entity.text.length < MESSAGE_TEXT_READ_CAP) model
                else model.copy(text = fullText(entity.id) ?: model.text)
            }
        }

    /**
     * 正文全文缓存（T4）：正文落库后不再改写，取回的全文按 id 复用，
     * 流每次发射（流式回复期间很密）都不重复打这条单行查询。
     * ponytail: 只留 3 条，够「展开一条、翻回上一条」；真要同时展开更多就调大这个数。
     */
    private val fullTextCache = object : LinkedHashMap<String, String>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 3
    }

    private suspend fun fullText(id: String): String? {
        synchronized(fullTextCache) { fullTextCache[id] }?.let { return it }
        val text = db.messageDao().textById(id) ?: return null
        synchronized(fullTextCache) { fullTextCache[id] = text }
        return text
    }

    suspend fun session(id: String): Session? = db.sessionDao().byId(id)?.toModel()

    /**
     * 单条消息的正文全文（T4）。列表流里的 text 只是前缀（CursorWindow 保护），
     * 展开 / 复制 / 导出要拿完整原文时按 id 补读这一行。
     */
    suspend fun messageText(messageId: String): String? = db.messageDao().textById(messageId)

    /**
     * 抽屉「聊天记录」分区：正文 LIKE 命中，最近的在前。
     * 空查询直接短路——'%%' 会扫全表，抽屉每次打开都点一下搜索框不该付这个代价。
     */
    suspend fun searchMessages(query: String, limit: Int = 60): List<ChatSearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return db.messageDao().search(escapeLike(q), limit).map {
            ChatSearchHit(
                sessionId = it.sessionId,
                sessionTitle = it.sessionTitle,
                role = it.role,
                snippet = it.snippet,
                at = it.at,
            )
        }
    }

    /** 抽屉「会话」分区：标题或预览命中；pinned 优先、最近活跃在前。空查询同样短路。 */
    suspend fun searchSessions(query: String, limit: Int = 30): List<SessionEntity> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return db.sessionDao().search(escapeLike(q), limit)
    }

    /**
     * 冷启动自愈：进程被杀（系统回收、覆盖安装、崩溃）时，正在跑的轮次没有任何机会写回
     * Idle，会话列表会永远转圈、通知也一直把它算成「运行中」。启动时统一抹平一次。
     */
    suspend fun resetStaleStatuses() {
        runCatching { db.sessionDao().resetRunning() }
            .onSuccess { if (it > 0) AppLog.i("resetStaleStatuses 抹平 "+it+" 条残留 Running") }
            .onFailure { AppLog.w("resetStaleStatuses 失败 "+it.message) }
    }

    /**
     * 会话运行态：会话列表/聊天头部的四色点都读它。
     * 只在值真的变了才写库，避免每次事件都刷一遍 session 行。
     */
    private suspend fun setStatus(sessionId: String, status: SessionStatus) {
        val row = db.sessionDao().byId(sessionId) ?: return
        if (row.status == status.name) return
        db.sessionDao().upsert(row.copy(status = status.name))
    }

    // ----------------------------------------------------------------- mutations

    suspend fun refreshSessions(cwd: String?): DshResult<Int> = runCatchingDsh {
        val t = requireTransport()
        val isRemote = t is AcpTransport
        val gen = sessionIds.currentGeneration
        val endpointId = sessionIds.currentEndpointId
        val remote = t.listSessions(cwd)
        // 挂起后核代际：listSessions 期间若已切连接，旧连接拉回的列表不落库、不注册（G2）。
        // 显式 error（走 Err），绝不 return 0 把「切换」伪装成刷新成功。
        if (!sessionIds.isCurrent(gen)) error("连接已切换，放弃刷新")
        val locals = db.sessionDao().all()
        // M2（B1）：只按「当前端点」匹配。local 模式忽略任何端点 remoteId 匹配，避免跨端点串号。
        // 旧数据 endpointId=null（升级而来）不猜归属 → 不进 localByRemote，不会被旧端点 id 串到新端点。
        val localByRemote = if (isRemote) {
            locals.filter { it.endpointId == endpointId && !it.remoteId.isNullOrBlank() }.associateBy { it.remoteId!! }
        } else emptyMap()
        val localById = locals.associateBy { it.id }
        // H1 + M2：ACP 只按 (endpoint, remoteId) 匹配，绝不回退本地 id（本地 id ≠ 远端 id）；
        // LocalEngine 本地 id == 远端 id，才按本地 id 匹配。
        // 本地为基底，远端只覆盖 status/updatedAt；本地缺失的远端会话整条导入并记 remoteId + endpointId。
        val merged = remote.map { r ->
            val local = if (isRemote) localByRemote[r.id] else localById[r.id]
            when {
                local == null && isRemote -> r.toEntity().copy(id = java.util.UUID.randomUUID().toString(), remoteId = r.id, endpointId = endpointId)
                local == null -> r.toEntity()
                else -> mergeRemoteSession(local, r).let { if (isRemote) it.copy(remoteId = r.id, endpointId = endpointId) else it }
            }
        }
        // all() 挂起后、落库前再核代际：期间若已切连接，旧列表不落库、不注册（B3）。
        if (!sessionIds.isCurrent(gen)) error("连接已切换，放弃刷新")
        db.sessionDao().upsertAll(merged)
        merged.forEach { e -> if (isRemote && !e.remoteId.isNullOrBlank()) sessionIds.register(e.id, e.remoteId, endpointId, gen) }
        merged.size
    }

    /**
     * 新建会话：本地先行。运行时没起来也能立刻建出一条会话并进入聊天页，
     * 等运行时 Ready 后再懒补建远端会话（[ensureRemoteSession]，首次发送前触发）。
     */
    suspend fun createSession(cwd: String, agentId: String? = null): DshResult<Session> = runCatchingDsh {
        val now = System.currentTimeMillis()
        val gen = sessionIds.currentGeneration
        val endpointId = sessionIds.currentEndpointId
        val t = transport.value
        val isRemote = t is AcpTransport
        val remote0 = t?.let { tt -> runCatching { tt.newSession(cwd) }.getOrNull() }
        // newSession 是挂起调用：期间若已切连接，旧连接建的远端会话作废，不绑定、不落库 remoteId（G2）。
        val remote = if (sessionIds.isCurrent(gen)) remote0 else null
        // M2：本地 id 恒为本地 UUID；ACP 时远端 id 另存 remoteId（本地 id 与远端 id 不再混用）。
        val remoteId = if (isRemote) remote?.id else null
        val localId = if (isRemote) java.util.UUID.randomUUID().toString() else (remote?.id ?: java.util.UUID.randomUUID().toString())
        val model = Session(
            id = localId,
            title = remote?.title?.ifBlank { "新会话" } ?: "新会话",
            cwd = cwd,
            createdAt = remote?.createdAt ?: now,
            updatedAt = now,
            status = remote?.status ?: SessionStatus.Idle,
            agentId = agentId,
            remoteId = remoteId,
        )
        db.sessionDao().upsert(model.toEntity().copy(endpointId = if (isRemote && remote != null) endpointId else null))
        if (remoteId != null) sessionIds.register(localId, remoteId, endpointId, gen)
        model
    }

    /** 建一个多 Agent 群组：同一个模型可以被拉进来多次，用于并行干活。 */
    suspend fun createGroup(
        cwd: String,
        title: String,
        participants: List<Participant>,
    ): DshResult<Session> = runCatchingDsh {
        val now = System.currentTimeMillis()
        val model = Session(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { "新群组" },
            cwd = cwd,
            createdAt = now,
            updatedAt = now,
            kind = SessionKind.Group,
            participants = participants,
            preview = participants.joinToString("、") { it.name },
        )
        db.sessionDao().upsert(model.toEntity())
        model
    }

    suspend fun setAgent(id: String, agentId: String?) {
        db.sessionDao().setAgent(id, agentId)
    }

    suspend fun setParticipants(id: String, participants: List<Participant>) {
        db.sessionDao().setParticipants(
            id,
            toolJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ParticipantDto.serializer()),
                participants.map { ParticipantDto(it.id, it.modelId, it.name, it.persona, it.slot) },
            ),
        )
    }

    /**
     * T9：本轮 prompt 挂在仓库作用域（应用级），不挂调用方作用域。
     * - 调用方取消只停止 await：不 cancel 远端、不定稿半截（见 [sendMessage] 的取消分支）。
     * - prompt 真实结束时先记 promptFinished，再由 collector 的终态 marker 收口；
     *   若调用方已离开（[detached]）而 marker 未到，这里兜底按真实结束结算完整正文。
     */
    private fun startTurn(
        sessionId: String,
        turnId: String,
        remoteId: String,
        t: DshTransport,
        text: String,
        images: List<String>,
        isRemote: Boolean,
        detached: AtomicBoolean,
    ): PromptJob {
        // P2：prompt 子任务带 turnId 记入 promptJobs，cancel 只按 turnId 命中，绝不误杀新轮。
        val job = scope.async {
            try {
                // N-1：只在远端 prompt 即将实际发出时注册屏障。本地模式（LocalEngine 无 turnId 合成 marker）、
                // ensureRemoteSession 补建失败、代际切换都在进入此块前 return，不注册屏障 → 不产生无谓阻塞。
                if (isRemote) barriers.seed(sessionId, turnId)
                t.prompt(remoteId, text, images, turnId)
            } finally {
                // N-1/m00652：prompt 结束（成功/失败/真 caller 取消）都记 promptFinished。
                // 真 caller 取消时远端仍活：promptFinished=true 但 markerConsumed=false → 仍挡新 seed。
                // 本地模式/未注册屏障时 no-op（barriers[sessionId] 不存在或 turnId 不匹配）。
                if (isRemote) barriers.promptFinished(sessionId, turnId)
                // 调用方已离开：本轮真实结束，兜底收口（marker 先到则 markFinalized 幂等 no-op）。
                if (detached.get()) withContext(NonCancellable) { runCatchingDsh { finalizeTurn(sessionId, turnId) } }
            }
        }
        val pj = PromptJob(turnId, job)
        promptJobs[sessionId] = pj
        // 先入表再挂完成回调：job 可能在注册前就已结束，回调仍会执行，remove 不会漏。
        job.invokeOnCompletion { promptJobs.remove(sessionId, pj) }
        return pj
    }

    /**
     * 本轮以异常收尾：先记失败终态再按当前轮回写 Failed，最后原样重抛给调用方（Err）。
     */
    private suspend fun failTurn(sessionId: String, turnId: String, gen: Long, e: Throwable): Nothing {
        AppLog.w("prompt failed: ${e.message}")
        // T3：任何异常收尾都是确定性失败终态（断流/网络/协议错误同一口径）。
        barriers.settle(sessionId, turnId, resolveTurnOutcome(completedNormally = true, error = e))
        // 失败收尾：ensure 补建失败/代际切换/网络异常都落这里，seed 的轮次与 Running 状态不挂住。
        // 只有「本轮仍是当前在跑轮」且「连接未切换」才回写 Failed：旧 gen/新轮已起时，状态由新连接接管，不被旧回写覆盖。
        val stillCurrentTurn = ledger.current(sessionId)?.turnId == turnId
        finalizeTurn(sessionId, turnId)
        if (stillCurrentTurn && sessionIds.isCurrent(gen)) setStatus(sessionId, SessionStatus.Failed)
        throw e
    }

    /**
     * 本轮被打断（非调用方离开）：30min 超时是确定性失败终态；用户 cancel 已由 [cancel] 结算。
     * 抽出来是为了让 [sendMessage] 的取消分支只表达「调用方离开不做收尾」。
     */
    private suspend fun settleInterruptedTurn(
        sessionId: String,
        turnId: String,
        gen: Long,
        isRemote: Boolean,
        e: CancellationException,
    ) {
        if (e is TimeoutCancellationException) {
            // 本轮真超时（30min 到点）：是错误，标记 Failed 并返回 Err（不再「Failed 状态 + Ok 结果」）。
            AppLog.w("prompt timeout: ${e.message}")
            // T3：超时是确定性终态，先记下来（首写优先，随后迟到的 marker 不会把它改成成功）。
            barriers.settle(sessionId, turnId, resolveTurnOutcome(completedNormally = true, error = e))
            val stillCurrentTurn = ledger.current(sessionId)?.turnId == turnId
            finalizeTurn(sessionId, turnId)
            if (stillCurrentTurn && sessionIds.isCurrent(gen)) setStatus(sessionId, SessionStatus.Failed)
            throw IllegalStateException("回复生成超时", e)
        }
        // 用户主动取消本轮：本轮已由 cancel() 结算（finalize partial + Idle + seal）。
        // 这里不做收尾、也不重抛 —— 正常返回 Ok，避免把新轮状态覆盖成 Failed。
        AppLog.w("prompt cancelled by user, turn=$turnId")
    }

    /**
     * T9 窄竞态兜底：prompt 已经返回、本轮 job 已经完成，但调用方的 await 还没恢复就被取消。
     * 那一刻 invokeOnCompletion 读到 detached=false 跳过收口，而调用方取消分支只打标记 ——
     * 若这里不补一刀，完整正文会永远不定稿（streaming 停在 1、会话卡 Running）。
     * 只在 job 确实已完成时结算，未完成的轮次仍交给 job 自己（真取消/超时语义不变）。
     */
    private suspend fun recoverCompletedTurn(sessionId: String, turnId: String, pj: PromptJob?) {
        if (pj?.job?.isCompleted != true) return
        withContext(NonCancellable) { runCatchingDsh { finalizeTurn(sessionId, turnId) } }
    }

    /**
     * 发一条用户消息。
     *
     * [messageId] 是客户端生成的消息 id：同一条消息重复调用只会落一条（主键 REPLACE 去重），
     * 不再出现「同一条提问两个气泡」。
     */
    suspend fun sendMessage(
        sessionId: String,
        text: String,
        images: List<String> = emptyList(),
        messageId: String? = null,
    ): DshResult<Unit> = runCatchingDsh {
        val gen = sessionIds.currentGeneration
        val endpointId = sessionIds.currentEndpointId
        val t = requireTransport()
        val isRemote = t is AcpTransport
        // N-1 quarantine：仅 ACP 有旧 chunk 越屏障污染问题；本地模式无 turnId 合成 marker、无归属，不使用屏障。
        // 上一轮终态未被 collector 消费前绝不进 DB/seed。断连不自动放行，故不提示「切换连接」可解除。
        if (isRemote && barriers.isBlocked(sessionId)) error("上一轮回复仍在收尾，请稍后重试")
        val now = System.currentTimeMillis()
        val attachmentsJson = org.json.JSONArray().apply { images.forEach { put(it) } }.toString()
        db.messageDao().upsert(
            MessageEntity(
                id = messageId ?: UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = ChatMessage.Role.USER.name,
                text = text,
                at = now,
                attachmentsJson = attachmentsJson,
            ),
        )
        val turnId = UUID.randomUUID().toString()
        ledger.seed(sessionId, turnId)
        setStatus(sessionId, SessionStatus.Running)
        db.sessionDao().byId(sessionId)?.let {
            db.sessionDao().upsert(it.copy(updatedAt = now, preview = text.take(80), messageCount = it.messageCount + 1))
        }
        // T9：调用方是否已离开本轮（VM 销毁/作用域取消）。离开后本轮照跑，收口交给终态 marker。
        val detached = AtomicBoolean(false)
        // T9 竞态：pj 必须在 try 外可见 —— 调用方取消分支要用它判断「本轮是否其实已经跑完」。
        var pj: PromptJob? = null
        try {
            // M2（B2）：ACP 首次实际发送前真实补建远端会话；本地模式直接本地 id。
            val remoteId = if (isRemote) {
                ensureRemoteSession(sessionId, gen, endpointId) ?: error("远端会话补建失败或连接已切换")
            } else {
                sessionId
            }
            // 补建跨挂起：prompt 前再核代际，旧连接结果不污染当前端点。
            if (!sessionIds.isCurrent(gen)) error("连接已切换，放弃本轮")
            // T9：本轮执行所有权在仓库作用域（应用级），不挂调用方作用域。
            // 调用方（UI/VM）被取消时只停止 await —— 不 cancel 远端、不把半截正文定稿成完整回答。
            pj = startTurn(sessionId, turnId, remoteId, t, text, images, isRemote, detached)
            pj.job.await()
            // 真实结束：完整正文 + 真实 durationMs 一次落库（幂等：终态 marker 先到则 no-op）。
            withContext(NonCancellable) { finalizeTurn(sessionId, turnId) }
        } catch (e: CancellationException) {
            // P3/T9：先区分「调用方离开」与「本轮被打断（cancel() / 30min 超时）」。
            if (!currentCoroutineContext().isActive) {
                // T9：调用方离开 ≠ 本轮结束：只停止 await，不 cancel 远端、不定稿半截、不置 Idle。
                detached.set(true)
                // 但 job 已经跑完、只是 await 没来得及恢复的那一瞬，本轮其实已经真实结束：补收口。
                recoverCompletedTurn(sessionId, turnId, pj)
                throw e
            }
            settleInterruptedTurn(sessionId, turnId, gen, isRemote, e)
        } catch (e: Throwable) {
            failTurn(sessionId, turnId, gen, e)
        }
    }

    suspend fun cancel(sessionId: String): DshResult<Unit> {
        // 先捕获当前轮 id 再按它结算：打断期间即便新轮已启动，也绝不会误结算新轮。
        val turnId = ledger.current(sessionId)?.turnId
        // 已经流出来的东西不能跟着取消一起消失：用户按下停止时最常问的是
        // 「我刚才看到的那半句呢」。keepPartial 让半截正文（哪怕只有思维链）
        // 也落成一条气泡，并标注被中断。
        // 先本地结算（无论远端 cancel 成不成都到 Idle）：本轮已 seal，
        // 随后 sendMessage 的 await 抛出时 stillCurrentTurn==false，不会误写 Failed。
        // 本地结算用 runCatchingDsh 兜住 DB 落库异常，但结果必须保留：本地落库失败时
        // 即便远端 cancel 成功也不能返回 Ok（本地结算失败是硬错误，要对 8c 可见）。
        val localResult = runCatchingDsh {
            finalizeTurn(sessionId, expectTurnId = turnId, keepPartial = true)
            // 用户自己停的，不算故障。
            setStatus(sessionId, SessionStatus.Idle)
        }
        if (localResult is DshResult.Err) return localResult
        // P5：捕获本轮挂在 prompt 上的子任务（若有），但不再取消它 —— cancel 是发通知，
        // prompt 会等 server settle 后以「cancelled」响应 + 合成终态 marker 自然完成。
        val pj = promptJobs[sessionId]?.takeIf { it.turnId == turnId }
        // P4：远端取消结果如实上报（ACP 取消是通知无响应；其它传输失败/超时仍需透 Err）。
        val t = _transport.value
        if (t != null) {
            // M2：ACP 无映射 = 从没建过远端，没有可取消的远端轮；本地模式 remoteId==sessionId。
            val remoteId = remoteIdOrNull(sessionId, t is AcpTransport)
            if (remoteId != null) {
                try {
                    t.cancel(remoteId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLog.w("cancel remote failed: ${e.message}")
                    return DshResult.Err(e.message ?: "取消远端失败", e)
                }
            }
        }
        // P5 barrier 有界等待：本轮 prompt 任务会在「响应到达 + 合成 marker 被 collector 消费」后完成。
        // 这里只观察、不取消；未确认则 Err + 保留 remote/history + sendGate 仍被旧轮持有挡新 prompt。
        if (pj != null) {
            try {
                withTimeout(15_000L) { pj.job.await() }
            } catch (e: TimeoutCancellationException) {
                AppLog.w("cancel barrier timeout: ${e.message}")
                // N-1/N-2：终态未确认 —— barrier 的 promptFinished 仍是 false（prompt 尚未结束），
                // 天然继续挡新 seed，无需额外标记。
                return DshResult.Err("取消未确认，远端可能仍在生成", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 断开/其它异常：prompt 已失败，本地已 Idle，如实上报 Err 不抛裸异常给 VM。
                AppLog.w("cancel barrier failed: ${e.message}")
                return DshResult.Err(e.message ?: "取消失败", e)
            }
        }
        return DshResult.Ok(Unit)
    }

    /**
     * 「插入」：不打断当前生成，把这句话交给运行时，在下一轮请求里生效。
     * 用户消息照常落库，所以气泡会立刻出现，只是不会另起一轮。
     */
    suspend fun interject(sessionId: String, text: String): DshResult<Unit> = runCatchingDsh {
        val now = System.currentTimeMillis()
        db.messageDao().upsert(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = ChatMessage.Role.USER.name,
                text = text,
                at = now,
            ),
        )
        db.sessionDao().byId(sessionId)?.let {
            db.sessionDao().upsert(it.copy(updatedAt = now, preview = text.take(80), messageCount = it.messageCount + 1))
        }
        val t = requireTransport()
        // M2：ACP 无映射时没有远端会话可插，跳过远端调用（本地气泡照常落库）。
        remoteIdOrNull(sessionId, t is AcpTransport)?.let { t.interject(it, text) }
    }

    /**
     * 回确认。[ownerAgentId] 传了就核对——群聊里同一轮的多个 Agent 各自可能有权限卡，
     * 用户在通知里点的选项只能回给发起它的那个 Agent，不能被别的成员消费掉。
     */
    suspend fun answerPermission(
        sessionId: String,
        requestId: String,
        optionId: String?,
        ownerAgentId: String? = null,
    ): DshResult<Unit> = runCatchingDsh {
        val current = ledger.current(sessionId)?.permissions?.firstOrNull { it.requestId == requestId }
        if (current == null) return@runCatchingDsh
        if (ownerAgentId != null && current.ownerAgentId != null && current.ownerAgentId != ownerAgentId) return@runCatchingDsh
        val t = requireTransport()
        // M2：ACP 无映射时没有远端权限卡可回，跳过（绝不把本地 id 当远端 id）。
        remoteIdOrNull(sessionId, t is AcpTransport)?.let { t.replyPermission(it, requestId, optionId) }
        ledger.updateExisting(sessionId) { it.resolvePermission(requestId, ownerAgentId) }
        if (ledger.current(sessionId)?.running == true && ledger.current(sessionId)?.permissions.isNullOrEmpty()) {
            setStatus(sessionId, SessionStatus.Running)
        }
    }

    suspend fun setConfigOption(sessionId: String, id: String, value: String): DshResult<Unit> =
        runCatchingDsh {
            val t = requireTransport()
            // M2：ACP 无映射时没有远端会话可配，跳过（绝不把本地 id 当远端 id）。
            remoteIdOrNull(sessionId, t is AcpTransport)?.let { t.setConfigOption(it, id, value) }
        }

    suspend fun setPinned(id: String, pinned: Boolean) {
        db.sessionDao().setPinned(id, if (pinned) 1 else 0)
    }

    /** 写入会话人设（普通会话与群聊通用）。 */
    suspend fun setPersona(id: String, persona: String) {
        val row = db.sessionDao().byId(id) ?: return
        db.sessionDao().upsert(row.copy(persona = persona))
    }

    suspend fun setArchived(id: String, archived: Boolean) {
        db.sessionDao().setArchived(id, if (archived) 1 else 0)
    }

    suspend fun setCwd(id: String, cwd: String) {
        db.sessionDao().setCwd(id, cwd)
    }

    suspend fun setGroup(id: String, group: String) {
        db.sessionDao().setGroup(id, group)
    }

    suspend fun rename(id: String, title: String) {
        db.sessionDao().setTitle(id, title)
    }

    suspend fun delete(id: String) {
        // M3：删会话先打断可能在跑的这一轮并清掉它的状态，否则 _turns 残留 running 轮次，
        // 会话列表永远转圈、SendQueue 的下降沿也永远触发不到（队里那条发不出去）。
        cancel(id)
        // N2：封口 + 清库原子化，之后 in-flight 结算看到 isClosed 直接弃写，不会把消息写回已删会话。
        sessionCritical(id) {
            ledger.close(id)
            db.messageDao().deleteBySession(id)
            db.sessionDao().delete(id)
        }
    }

    suspend fun clearMessages(id: String) {
        // M3：清消息先打断可能在跑的这一轮。cancel 内部 finalizeTurn(keepPartial=true)
        // 会把轮次封口（running=false），迟到的 chunk/End 被 !running 拦下，不会在清库后写回，
        // 也不会把会话钉在 running 上。
        cancel(id)
        // N2：封口 + 清库原子化，之后 in-flight 结算看到 isClosed 直接弃写（关键在锁 + 代际，非时间戳）。
        sessionCritical(id) {
            ledger.close(id)
            db.messageDao().deleteBySession(id)
        }
    }

    // ------------------------------------------------------------------- events

    private suspend fun handleEvent(e0: DshEvent, isRemote: Boolean, transport: DshTransport) {
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
    private fun translateToLocal(e: DshEvent, isRemote: Boolean): DshEvent? {
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
    private fun DshEvent.sessionIdOrNull(): String? = when (this) {
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
        is DshEvent.Raw -> null
    }

    private fun DshEvent.withLocalSessionId(localId: String): DshEvent = when (this) {
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
        is DshEvent.Raw -> this
    }

    /** A group member finished: persist its bubble right away so history stays ordered. */
    private suspend fun finishPart(sessionId: String, authorId: String, turnId: String? = null) {
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
    private fun isNoAddMarker(text: String): Boolean {
        val t = text.trim()
        return t.isEmpty() || t == "（无补充）" || t == "(无补充)"
    }

    private companion object {
        /** 被用户中断的那一轮留下的占位正文。 */
        const val PARTIAL_MARK = "（已停止 · 这条回复被中途打断）"

        /**
         * 正文不截断：原文必须整条落库（T4）。CursorWindow 默认 2MB 的保护挪到读侧——
         * 列表 / 历史查询只取 MESSAGE_COLUMNS 里的 substr 前缀，全文走 MessageDao.textById。
         * 思维链仍按 64K 字符裁，避免一条 thought 把整行顶爆。
         */
        private const val MAX_PERSIST_THOUGHT_CHARS = 64 * 1024

        private fun cappedThoughtForPersist(text: String): String {
            if (text.length <= MAX_PERSIST_THOUGHT_CHARS) return text
            return text.take(MAX_PERSIST_THOUGHT_CHARS) +
                "\n（思维链过长，已截断保存）"
        }
    }

    private suspend fun persistAssistant(
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

    private suspend fun finalizeTurn(
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
        ledger.seal(sessionId)
        db.sessionDao().byId(sessionId)?.let {
            db.sessionDao().upsert(it.copy(updatedAt = System.currentTimeMillis()))
        }
        setStatus(sessionId, SessionStatus.Idle)
    }

    private fun requireTransport(): DshTransport =
        _transport.value ?: throw IllegalStateException("运行时未连接")
}

// ---------------------------------------------------------------- mapping

fun SessionEntity.toModel(): Session = Session(
    id = id,
    title = title,
    cwd = cwd,
    updatedAt = updatedAt,
    createdAt = createdAt,
    status = runCatching { SessionStatus.valueOf(status) }.getOrDefault(SessionStatus.Idle),
    preview = preview,
    messageCount = messageCount,
    usage = TokenUsage(input = inputTokens, output = outputTokens, cached = cachedTokens, lastPrompt = lastPrompt),
    pinned = pinned == 1,
    archived = archived == 1,
    group = grp,
    kind = runCatching { SessionKind.valueOf(kind) }.getOrDefault(SessionKind.Single),
    agentId = agentId,
    persona = persona,
    participants = runCatching {
        toolJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(ParticipantDto.serializer()),
            participantsJson.ifBlank { "[]" },
        ).map { Participant(it.id, it.modelId, it.name, it.persona, it.slot) }
    }.getOrDefault(emptyList()),
    remoteId = remoteId,
    endpointId = endpointId,
)

fun Session.toEntity(): SessionEntity = SessionEntity(
    id = id,
    title = title,
    cwd = cwd,
    updatedAt = updatedAt,
    createdAt = createdAt,
    status = status.name,
    preview = preview,
    messageCount = messageCount,
    inputTokens = usage.input,
    outputTokens = usage.output,
    pinned = if (pinned) 1 else 0,
    archived = if (archived) 1 else 0,
    grp = group,
    kind = kind.name,
    agentId = agentId,
    participantsJson = toolJson.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(ParticipantDto.serializer()),
        participants.map { ParticipantDto(it.id, it.modelId, it.name, it.persona, it.slot) },
    ),
    persona = persona,
    remoteId = remoteId,
    endpointId = endpointId,
)

/**
 * 远端会话只补它权威的两列：时间戳只前进、状态以远端为准。
 * 本地字段（标题、人设、绑定模型、分组）不被远端覆盖。
 */
fun mergeRemoteSession(local: SessionEntity?, remote: Session): SessionEntity {
    if (local == null) return remote.toEntity()
    return local.copy(
        updatedAt = maxOf(local.updatedAt, remote.updatedAt),
        status = remote.status.name,
    )
}

fun MessageEntity.toModel(): ChatMessage = ChatMessage(
    id = id,
    sessionId = sessionId,
    role = runCatching { ChatMessage.Role.valueOf(role) }.getOrDefault(ChatMessage.Role.SYSTEM),
    text = text,
    thought = thought,
    toolCalls = runCatching {
        toolJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(ToolCallDto.serializer()),
            toolCallsJson,
        ).map {
            ToolCall(
                id = it.id,
                title = it.title,
                kind = it.kind,
                status = runCatching { ToolCall.ToolStatus.valueOf(it.status) }
                    .getOrDefault(ToolCall.ToolStatus.Completed),
                detail = it.detail,
                args = it.args,
                requestJson = it.requestJson,
                responseJson = it.responseJson,
                elapsedMs = it.elapsedMs,
                attempts = it.attempts,
            )
        }
    }.getOrDefault(emptyList()),
    at = at,
    streaming = streaming == 1,
    authorId = authorId,
    authorName = authorName,
    attachments = runCatching {
        org.json.JSONArray(attachmentsJson).let { a -> (0 until a.length()).map { a.optString(it) } }
    }.getOrDefault(emptyList()).filter { it.isNotBlank() },
    durationMs = durationMs,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
)