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

// ---------------------------------------------------------------- 会话增删改与同步

internal suspend fun ChatRepository.refreshSessionsImpl(cwd: String?): DshResult<Int> = runCatchingDsh {
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
internal suspend fun ChatRepository.createSessionImpl(
    cwd: String,
    agentId: String? = null,
    /** 默认标题由调用方（带 Context 的 UI 层）按当前语言取资源传进来，core:data 不带文案。 */
    title: String = "",
): DshResult<Session> = runCatchingDsh {
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
        // 调用方给的标题优先，其次远端会话自带的标题；典型情况下两者都空，UI 显示「未命名会话」。
        title = remote?.title?.ifBlank { title } ?: title,
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
internal suspend fun ChatRepository.createGroupImpl(
    cwd: String,
    title: String,
    participants: List<Participant>,
): DshResult<Session> = runCatchingDsh {
    val now = System.currentTimeMillis()
    val model = Session(
        id = UUID.randomUUID().toString(),
        title = title,
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

internal suspend fun ChatRepository.setAgentImpl(id: String, agentId: String?) {
    db.sessionDao().setAgent(id, agentId)
}

internal suspend fun ChatRepository.setParticipantsImpl(id: String, participants: List<Participant>) {
    db.sessionDao().setParticipants(
        id,
        toolJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ParticipantDto.serializer()),
            participants.map { ParticipantDto(it.id, it.modelId, it.name, it.persona, it.slot) },
        ),
    )
}
