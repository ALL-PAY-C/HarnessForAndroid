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
import dev.dsh.mobile.core.protocol.RecallLookup
import dev.dsh.mobile.core.protocol.RecallReport
import dev.dsh.mobile.core.protocol.SessionProjection
import dev.dsh.mobile.core.protocol.SessionProjections
import dev.dsh.mobile.core.protocol.WarmRefresher
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

// ---------------------------------------------------------------- 权限 / 配置 / 杂项

/**
 * 回确认。[ownerAgentId] 传了就核对——群聊里同一轮的多个 Agent 各自可能有权限卡，
 * 用户在通知里点的选项只能回给发起它的那个 Agent，不能被别的成员消费掉。
 */
internal suspend fun ChatRepository.answerPermissionImpl(
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

internal suspend fun ChatRepository.setConfigOptionImpl(sessionId: String, id: String, value: String): DshResult<Unit> =
    runCatchingDsh {
        val t = requireTransport()
        // M2：ACP 无映射时没有远端会话可配，跳过（绝不把本地 id 当远端 id）。
        remoteIdOrNull(sessionId, t is AcpTransport)?.let { t.setConfigOption(it, id, value) }
    }

internal suspend fun ChatRepository.setPinnedImpl(id: String, pinned: Boolean) {
    db.sessionDao().setPinned(id, if (pinned) 1 else 0)
}

/** 写入会话人设（普通会话与群聊通用）。 */
internal suspend fun ChatRepository.setPersonaImpl(id: String, persona: String) {
    val row = db.sessionDao().byId(id) ?: return
    db.sessionDao().upsert(row.copy(persona = persona))
}

internal suspend fun ChatRepository.setArchivedImpl(id: String, archived: Boolean) {
    db.sessionDao().setArchived(id, if (archived) 1 else 0)
}

internal suspend fun ChatRepository.setCwdImpl(id: String, cwd: String) {
    db.sessionDao().setCwd(id, cwd)
}

internal suspend fun ChatRepository.setGroupImpl(id: String, group: String) {
    db.sessionDao().setGroup(id, group)
}

internal suspend fun ChatRepository.renameImpl(id: String, title: String) {
    db.sessionDao().setTitle(id, title)
}

internal suspend fun ChatRepository.deleteImpl(id: String) {
    // M3：删会话先打断可能在跑的这一轮并清掉它的状态，否则 _turns 残留 running 轮次，
    // 会话列表永远转圈、SendQueue 的下降沿也永远触发不到（队里那条发不出去）。
    cancelImpl(id)
    // N2：封口 + 清库原子化，之后 in-flight 结算看到 isClosed 直接弃写，不会把消息写回已删会话。
    sessionCritical(id) {
        ledger.close(id)
        db.messageDao().deleteBySession(id)
        db.sessionDao().delete(id)
    }
}

internal suspend fun ChatRepository.clearMessagesImpl(id: String) {
    // M3：清消息先打断可能在跑的这一轮。cancel 内部 finalizeTurn(keepPartial=true)
    // 会把轮次封口（running=false），迟到的 chunk/End 被 !running 拦下，不会在清库后写回，
    // 也不会把会话钉在 running 上。
    cancelImpl(id)
    // N2：封口 + 清库原子化，之后 in-flight 结算看到 isClosed 直接弃写（关键在锁 + 代际，非时间戳）。
    sessionCritical(id) {
        ledger.close(id)
        db.messageDao().deleteBySession(id)
    }
}

// ------------------------------------------------------------ compaction / projection

/**
 * P0-2：/compact 真入口。压缩在 transport 侧落地（要动模型、要读写摘要），仓库只做门面。
 *
 * 这里不用 sessionCritical：压缩不写消息库，和 persistAssistant / clearMessages 不争同一份状态。
 */
internal suspend fun ChatRepository.compactImpl(sessionId: String, budgetTokens: Int = 0): CompactResult {
    val t = requireTransport()
    val compactor = t as? Compactor
        ?: return CompactResult(compacted = false, message = "当前引擎不支持上下文压缩。")
    return try {
        compactor.compact(sessionId, budgetTokens)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppLog.w("compact failed: " + (e.message ?: e.javaClass.simpleName))
        CompactResult(compacted = false, message = "压缩失败：" + (e.message ?: e.javaClass.simpleName))
    }
}

/**
 * T9.8-S2：/warm 真入口。温层生成要历史 rowid + 一次模型调用，落地在 transport 侧。
 *
 * 与 [compactImpl] 同形：不支持就返回一句实话，不让 UI 假装刷过。
 */
internal suspend fun ChatRepository.refreshWarmImpl(sessionId: String): WarmRefreshReport? {
    val t = requireTransport()
    val refresher = t as? WarmRefresher ?: return null
    return try {
        refresher.refreshWarm(sessionId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppLog.w("refreshWarm failed: " + (e.message ?: e.javaClass.simpleName))
        WarmRefreshReport(null, "温层刷新失败：" + (e.message ?: e.javaClass.simpleName))
    }
}

/**
 * T9.8-S4：/recall 真入口。查库在 transport 侧（要 messages.rowid），仓库只做门面与异常兜底。
 *
 * 与 [refreshWarmImpl] 同形：不支持就返回 null，让 UI 说「当前引擎不支持回捞」而不是假装查过。
 */
internal suspend fun ChatRepository.recallImpl(sessionId: String, query: String): RecallReport? {
    val t = requireTransport()
    val lookup = t as? RecallLookup ?: return null
    return try {
        lookup.recall(sessionId, query)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppLog.w("recall failed: " + (e.message ?: e.javaClass.simpleName))
        RecallReport(message = "回捞失败：" + (e.message ?: e.javaClass.simpleName))
    }
}

/** P0-6：会话投影的只读流（待办 / 目标 / 用量 / 压缩统计）。 */
internal fun ChatRepository.projectionImpl(sessionId: String): StateFlow<SessionProjection> =
    (_transport.value as? LocalEngineTransport)?.projections?.observe(sessionId)
        ?: projections.observe(sessionId)
