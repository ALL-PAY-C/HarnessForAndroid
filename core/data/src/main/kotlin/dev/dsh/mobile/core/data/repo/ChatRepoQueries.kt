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
import dev.dsh.mobile.core.model.event.DshEvent
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

// ---------------------------------------------------------------- 观察流与历史查询

internal fun ChatRepository.observeSessionsImpl(): Flow<List<Session>> =
    db.sessionDao().observeAll().map { list -> list.map { it.toModel() } }

/**
 * 会话消息流。列表查询只取 text 前缀（CursorWindow 保护，见 [MESSAGE_TEXT_READ_CAP]），
 * 命中前缀上限的行在这里按 id 逐条补读全文 —— 消费方（UI 展开 / 复制 / 导出）拿到的就是原文，
 * 不必再自己判断哪条需要补读。短消息一次额外查询都不发。
 */
internal fun ChatRepository.observeMessagesImpl(sessionId: String): Flow<List<ChatMessage>> =
    db.messageDao().observeBySession(sessionId).map { list ->
        list.map { entity ->
            val model = entity.toModel()
            if (entity.text.length < MESSAGE_TEXT_READ_CAP) model
            else model.copy(text = fullText(entity.id) ?: model.text)
        }
    }

internal suspend fun ChatRepository.sessionImpl(id: String): Session? = db.sessionDao().byId(id)?.toModel()

/**
 * 单条消息的正文全文（T4）。列表流里的 text 只是前缀（CursorWindow 保护），
 * 展开 / 复制 / 导出要拿完整原文时按 id 补读这一行。
 */
internal suspend fun ChatRepository.messageTextImpl(messageId: String): String? = db.messageDao().textById(messageId)

/**
 * 抽屉「聊天记录」分区：正文 LIKE 命中，最近的在前。
 * 空查询直接短路——'%%' 会扫全表，抽屉每次打开都点一下搜索框不该付这个代价。
 */
internal suspend fun ChatRepository.searchMessagesImpl(query: String, limit: Int = 60): List<ChatSearchHit> {
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
internal suspend fun ChatRepository.searchSessionsImpl(query: String, limit: Int = 30): List<SessionEntity> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return db.sessionDao().search(escapeLike(q), limit)
}

/**
 * 冷启动自愈：进程被杀（系统回收、覆盖安装、崩溃）时，正在跑的轮次没有任何机会写回
 * Idle，会话列表会永远转圈、通知也一直把它算成「运行中」。启动时统一抹平一次。
 */
internal suspend fun ChatRepository.resetStaleStatusesImpl() {
    runCatching { db.sessionDao().resetRunning() }
        .onSuccess { if (it > 0) AppLog.i("resetStaleStatuses 抹平 "+it+" 条残留 Running") }
        .onFailure { AppLog.w("resetStaleStatuses 失败 "+it.message) }
}

/**
 * 会话运行态：会话列表/聊天头部的四色点都读它。
 * 只在值真的变了才写库，避免每次事件都刷一遍 session 行。
 */
internal suspend fun ChatRepository.setStatus(sessionId: String, status: SessionStatus) {
    val row = db.sessionDao().byId(sessionId) ?: return
    if (row.status == status.name) return
    db.sessionDao().upsert(row.copy(status = status.name))
}

