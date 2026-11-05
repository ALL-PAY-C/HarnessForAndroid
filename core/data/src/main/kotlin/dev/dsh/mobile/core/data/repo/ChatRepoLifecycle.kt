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

// ---------------------------------------------------------------- 会话生命周期与远程会话映射

internal suspend fun ChatRepository.attachImpl(t: DshTransport?, endpointId: String? = null) {
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
internal suspend fun ChatRepository.reloadSessionIds() {
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
internal suspend fun ChatRepository.remoteIdOrNull(localId: String, isRemote: Boolean): String? =
    if (isRemote) sessionIds.remote(localId) else localId

/**
 * B2：ACP 首次实际发送前，若本地会话还没有远端映射，真实补建一次并持久化。
 * per-session 锁保证并发只建一次；连接代际校验保证旧连接的补建结果不污染当前端点。
 */
internal suspend fun ChatRepository.ensureRemoteSession(sessionId: String, gen: Long, endpointId: String?): String? {
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
